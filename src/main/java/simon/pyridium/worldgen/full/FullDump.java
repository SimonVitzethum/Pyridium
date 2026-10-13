package simon.pyridium.worldgen.full;

import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test tool (-Dpyridium.fullDump=dir): top view PNG, vertical slice PNG and raw state dump of generated chunks. */
public final class FullDump {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/full-dump");

    private FullDump() {
    }

    private static int color(BlockState st) {
        if (st.isAir()) return 0x101018;
        if (st.is(Blocks.WATER)) return 0x2040c0;
        if (st.is(Blocks.LAVA)) return 0xff6000;
        String n = Block.BLOCK_STATE_REGISTRY.getId(st) >= 0 ? st.getBlock().getDescriptionId() : "";
        if (n.contains("ore")) {
            if (n.contains("diamond")) return 0x40ffff;
            if (n.contains("iron")) return 0xffc0a0;
            if (n.contains("coal")) return 0x000000;
            if (n.contains("gold")) return 0xffe000;
            if (n.contains("redstone")) return 0xff0000;
            if (n.contains("lapis")) return 0x2040ff;
            if (n.contains("copper")) return 0xe07040;
            return 0xffffff;
        }
        return st.getBlock().defaultMapColor().col;
    }

    public static void dump(ServerLevel level, int cx, int cz, int r, Path dir) {
        try {
            Files.createDirectories(dir);
            int w = (2 * r + 1) * 16;
            BufferedImage top = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            int minY = level.getMinY(), h = level.getHeight();
            BufferedImage slice = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int sliceZ = 8;
            int[] levels = {-40, 0, 40};
            BufferedImage[] plan = new BufferedImage[levels.length];
            for (int i = 0; i < levels.length; i++) plan[i] = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    ChunkAccess ch = level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                    if (ch == null) continue;
                    for (int x = 0; x < 16; x++)
                        for (int z = 0; z < 16; z++) {
                            int y = ch.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                            BlockState st = ch.getBlockState(new BlockPos(ch.getPos().getMinBlockX() + x, y, ch.getPos().getMinBlockZ() + z));
                            int c = color(st);
                            // simple relief shading by height
                            int shade = Math.clamp(y - 60, -40, 100);
                            int rr = Math.clamp(((c >> 16) & 255) + shade / 2, 0, 255), gg = Math.clamp(((c >> 8) & 255) + shade / 2, 0, 255), bb = Math.clamp((c & 255) + shade / 2, 0, 255);
                            top.setRGB((dx + r) * 16 + x, (dz + r) * 16 + z, (rr << 16) | (gg << 8) | bb);
                        }
                    for (int i = 0; i < levels.length; i++)
                        for (int x = 0; x < 16; x++)
                            for (int z = 0; z < 16; z++)
                                plan[i].setRGB((dx + r) * 16 + x, (dz + r) * 16 + z,
                                        color(ch.getBlockState(new BlockPos(ch.getPos().getMinBlockX() + x, levels[i], ch.getPos().getMinBlockZ() + z))));
                    if (dz == 0)
                        for (int x = 0; x < 16; x++)
                            for (int yy = 0; yy < h; yy++)
                                slice.setRGB((dx + r) * 16 + x, h - 1 - yy,
                                        color(ch.getBlockState(new BlockPos(ch.getPos().getMinBlockX() + x, minY + yy, ch.getPos().getMinBlockZ() + sliceZ))));
                }
            ImageIO.write(top, "png", dir.resolve("top.png").toFile());
            ImageIO.write(slice, "png", dir.resolve("slice.png").toFile());
            for (int i = 0; i < levels.length; i++) ImageIO.write(plan[i], "png", dir.resolve("y" + levels[i] + ".png").toFile());
            // raw states of 5x5 chunks around the centre (for GPU-vs-Vanilla comparison)
            try (var out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(dir.resolve("states.bin"))))) {
                for (int dz = -2; dz <= 2; dz++)
                    for (int dx = -2; dx <= 2; dx++) {
                        ChunkAccess ch = level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                        ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                        for (int yy = 0; yy < h; yy++)
                            for (int x = 0; x < 16; x++)
                                for (int z = 0; z < 16; z++)
                                    out.writeInt(ch == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(ch.getBlockState(m.set(p.getMinBlockX() + x, minY + yy, p.getMinBlockZ() + z))));
                    }
            }
            LOG.info("Dump in {} ({}x{} Chunks um {}/{})", dir, 2 * r + 1, 2 * r + 1, cx, cz);
        } catch (IOException e) {
            LOG.error("Dump fehlgeschlagen", e);
        }
    }
}
