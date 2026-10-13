package simon.pyridium.worldgen.structures;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Virtuelles Bauteil im Vorlagen-Koordinatensystem eines Streu-Bauwerks (Breite x Hoehe x Tiefe, y darf negativ
 * werden). Die Methoden entsprechen den Bau-Primitiven der Strukturen ({@code generateBox}, {@code placeBlock},
 * {@code fillColumnDown}, {@code createChest}) und schreiben in ein Voxelgitter statt in eine Welt; daraus backt
 * {@link ScatteredBake} eine GPU-Vorlage. Einmaliges Vorbereiten beim Laden.
 *
 * <p>Annahmen des "virtuellen Bodens": unterhalb von y = 0 ist Fels (Boxen mit skipAir greifen dort), darueber Luft;
 * fillColumnDown fuellt vier Bloecke tief (die echte Tiefe haengt vom Gelaende ab).
 */
abstract class VirtualPiece {
    static final int PAD = 24;
    final RandomSource random;
    final int width, height, depth;
    private final BlockState[] grid;
    private final int gx, gy, gz;
    // Zustandsfelder, die die Bauanweisungen kennen
    final boolean[] hasPlacedChest = new boolean[4];
    final List<BlockPos> potentialSuspiciousSandWorldPositions = new ArrayList<>();
    BlockPos randomCollapsedRoofPos = BlockPos.ZERO;
    boolean placedMainChest, placedHiddenChest, placedTrap1, placedTrap2, spawnedWitch, spawnedCat, isNeedingChest, hasPlacedSpawner;

    VirtualPiece(RandomSource random, int width, int height, int depth) {
        this.random = random;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.gx = width + 2 * PAD;
        this.gy = height + 2 * PAD;
        this.gz = depth + 2 * PAD;
        this.grid = new BlockState[gx * gy * gz];
    }

    abstract void build();

    private int idx(int x, int y, int z) {
        int i = x + PAD, j = y + PAD, k = z + PAD;
        if (i < 0 || j < 0 || k < 0 || i >= gx || j >= gy || k >= gz) return -1;
        return i + gx * (k + gz * j);
    }

    BlockState at(int x, int y, int z) {
        int i = idx(x, y, z);
        return i < 0 ? null : grid[i];
    }

    int[] extents() {
        int[] e = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int y = -PAD; y < gy - PAD; y++)
            for (int z = -PAD; z < gz - PAD; z++)
                for (int x = -PAD; x < gx - PAD; x++)
                    if (grid[idx(x, y, z)] != null) {
                        e[0] = Math.min(e[0], x); e[1] = Math.min(e[1], y); e[2] = Math.min(e[2], z);
                        e[3] = Math.max(e[3], x); e[4] = Math.max(e[4], y); e[5] = Math.max(e[5], z);
                    }
        return e;
    }

    // --- Primitive (Signaturen wie in den Bauanweisungen) ---
    void placeBlock(Object level, BlockState state, int x, int y, int z, Object chunkBB) {
        int i = idx(x, y, z);
        if (i >= 0) grid[i] = state;
    }

    BlockState getBlock(Object level, int x, int y, int z, Object chunkBB) {
        BlockState s = at(x, y, z);
        if (s != null) return s;
        return y < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    void generateBox(Object level, Object chunkBB, int x0, int y0, int z0, int x1, int y1, int z1, BlockState edge, BlockState fill, boolean skipAir) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    if (!skipAir || !getBlock(level, x, y, z, chunkBB).isAir()) {
                        if (y != y0 && y != y1 && x != x0 && x != x1 && z != z0 && z != z1) placeBlock(level, fill, x, y, z, chunkBB);
                        else placeBlock(level, edge, x, y, z, chunkBB);
                    }
    }

    void generateBox(Object level, Object chunkBB, int x0, int y0, int z0, int x1, int y1, int z1, boolean skipAir, RandomSource random, BlockSelector sel) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++)
                    if (!skipAir || !getBlock(level, x, y, z, chunkBB).isAir()) {
                        sel.next(random, x, y, z, y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1);
                        placeBlock(level, sel.getNext(), x, y, z, chunkBB);
                    }
    }

    void generateAirBox(Object level, Object chunkBB, int x0, int y0, int z0, int x1, int y1, int z1) {
        generateBox(level, chunkBB, x0, y0, z0, x1, y1, z1, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState(), false);
    }

    void fillColumnDown(Object level, BlockState state, int x, int startY, int z, Object chunkBB) {
        for (int y = startY; y > startY - 4; y--) placeBlock(level, state, x, y, z, chunkBB);
    }

    boolean createChest(Object level, Object chunkBB, RandomSource random, int x, int y, int z, Object loot) {
        placeBlock(level, Blocks.CHEST.defaultBlockState(), x, y, z, chunkBB);
        return true;
    }

    boolean createDispenser(Object level, Object chunkBB, RandomSource random, int x, int y, int z, Direction dir, Object loot) {
        placeBlock(level, Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, dir), x, y, z, chunkBB);
        return true;
    }

    int getWorldX(int x, int z) {
        return x;
    }

    int getWorldY(int y) {
        return y;
    }

    int getWorldZ(int x, int z) {
        return z;
    }

    boolean isInsideStub(BlockPos pos) {
        return true;
    }

    BlockPos getWorldPos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** wie StructurePiece.BlockSelector */
    abstract static class BlockSelector {
        protected BlockState next = Blocks.AIR.defaultBlockState();

        abstract void next(RandomSource random, int x, int y, int z, boolean isEdge);

        BlockState getNext() {
            return next;
        }
    }

    static int stateId(BlockState s) {
        return Block.BLOCK_STATE_REGISTRY.getId(s);
    }
}
