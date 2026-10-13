package simon.pyridium.worldgen.structures;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Backt eine Struktur-Vorlage (NBT) in kompakte GPU-Tabellen: dichtes Voxelgitter mit lokaler
 * Palette (0 = unberuehrt, sonst Palettenindex + 1), Jigsaw-Liste in Vanillas Reihenfolge und eine
 * dyadische Mip-Kette fuer grobe LOD-Silhouetten. Einmaliges Vorbereiten beim Laden, keine Generierung.
 *
 * <p>Voxelsemantik wie {@code StructureTemplate.placeInWorld} mit den Jigsaw-Prozessoren: Struktur-Bloecke
 * (Datenmarker) und structure_void werden ignoriert, Jigsaw-Bloecke werden durch ihren final_state ersetzt.
 * Luft bleibt als Eintrag erhalten (Vorlagenluft raeumt Gelaende, ausser bei "legacy"-Elementen).
 */
public final class TemplateBaker {
    /** Ein gebackenes Template */
    public static final class Baked {
        public int sx, sy, sz;
        public short[] voxels = new short[0];
        public int[] palette = new int[0];
        /** je Jigsaw JIG_W Worte, in Vanillas Reihenfolge (y, x, z) */
        public final List<int[]> jigsaws = new ArrayList<>();
        /** Mip-Ebenen 1..: Groesse + Daten */
        public final List<short[]> mips = new ArrayList<>();
        public final List<int[]> mipDims = new ArrayList<>();
        public boolean missing;
    }

    private final StructureTables t;
    private final Map<String, Integer> index = new HashMap<>();

    public TemplateBaker(StructureTables t) {
        this.t = t;
    }

    /** Index der Vorlage in den Tabellen (backt beim ersten Aufruf); -1 wenn die NBT-Datei fehlt */
    public int get(StructureData data, String id) {
        Integer i = index.get(id);
        if (i != null) return i;
        byte[] raw = data.bytes("structure", id, ".nbt");
        Baked b;
        if (raw == null) {
            b = new Baked();
            b.missing = true;
        } else {
            try {
                b = bake(NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap()));
            } catch (IOException e) {
                throw new IllegalStateException("Vorlage " + id + ": " + e, e);
            }
        }
        int idx = append(b, id);
        index.put(id, idx);
        return idx;
    }

    /** Eine fertig gebackene Vorlage (z. B. aus VirtualPiece) in die Tabellen aufnehmen */
    public int add(Baked b, String id) {
        Integer i = index.get(id);
        if (i != null) return i;
        buildMips(b);
        int idx = append(b, id);
        index.put(id, idx);
        return idx;
    }

    private int append(Baked b, String id) {
        int idx = t.numTemplates();
        int voxOff = t.voxels.size();
        for (short s : b.voxels) t.voxels.add(s);
        int palOff = t.palette.size();
        for (int p : b.palette) t.palette.add(p);
        int jigOff = t.jigsaws.size() / StructureTables.JIG_W;
        for (int[] j : b.jigsaws) for (int w : j) t.jigsaws.add(w);
        int mipIdx = -1;
        if (!b.mips.isEmpty()) {
            mipIdx = t.mips.size();
            t.mips.add(b.mips.size());
            for (int l = 0; l < b.mips.size(); l++) {
                int[] d = b.mipDims.get(l);
                t.mips.add(d[0]);
                t.mips.add(d[1]);
                t.mips.add(d[2]);
                t.mips.add(t.voxels.size());
                for (short s : b.mips.get(l)) t.voxels.add(s);
            }
        }
        t.templates.add(b.sx);
        t.templates.add(b.sy);
        t.templates.add(b.sz);
        t.templates.add(voxOff);
        t.templates.add(palOff);
        t.templates.add(b.palette.length);
        t.templates.add(jigOff);
        t.templates.add(b.jigsaws.size());
        t.templates.add(mipIdx);
        t.templates.add(b.missing ? 1 : 0);
        t.templateNames.add(id);
        return idx;
    }

    public Baked bake(CompoundTag tag) {
        Baked b = new Baked();
        ListTag size = tag.getListOrEmpty("size");
        b.sx = size.getIntOr(0, 0);
        b.sy = size.getIntOr(1, 0);
        b.sz = size.getIntOr(2, 0);
        List<BlockState[]> palettes = new ArrayList<>();
        var paletteLists = tag.getList("palettes");
        if (paletteLists.isPresent()) {
            // Vorlagen mit mehreren Paletten (Varianten): die erste Palette
            ListTag first = paletteLists.get().getListOrEmpty(0);
            palettes.add(readPalette(first));
        } else {
            palettes.add(readPalette(tag.getListOrEmpty("palette")));
        }
        BlockState[] pal = palettes.get(0);
        // lokale Palette: globale Zustands-Id -> Index + 1
        Map<Integer, Integer> local = new HashMap<>();
        List<Integer> localIds = new ArrayList<>();
        int n = b.sx * b.sy * b.sz;
        b.voxels = new short[Math.max(0, n)];
        ListTag blocks = tag.getListOrEmpty("blocks");
        List<Object[]> jigs = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag bt = blocks.getCompoundOrEmpty(i);
            ListTag pos = bt.getListOrEmpty("pos");
            int x = pos.getIntOr(0, 0), y = pos.getIntOr(1, 0), z = pos.getIntOr(2, 0);
            int si = bt.getIntOr("state", 0);
            if (si < 0 || si >= pal.length || pal[si] == null) continue;
            BlockState st = pal[si];
            CompoundTag nbt = bt.getCompound("nbt").orElse(null);
            if (st.is(Blocks.STRUCTURE_BLOCK)) continue;       // Datenmarker: BlockIgnoreProcessor
            BlockState place = st;
            if (st.is(Blocks.JIGSAW)) {
                jigs.add(new Object[]{x, y, z, st, nbt});
                String fs = nbt == null ? "minecraft:air" : nbt.getStringOr("final_state", "minecraft:air");
                try {
                    place = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, fs, true).blockState();
                } catch (Exception e) {
                    continue;
                }
                if (place.is(Blocks.STRUCTURE_VOID)) continue;
            }
            if (x < 0 || y < 0 || z < 0 || x >= b.sx || y >= b.sy || z >= b.sz) continue;
            int gid = Block.BLOCK_STATE_REGISTRY.getId(place);
            Integer li = local.get(gid);
            if (li == null) {
                li = localIds.size() + 1;
                local.put(gid, li);
                localIds.add(gid);
            }
            b.voxels[x + b.sx * (z + b.sz * y)] = (short) (int) li;
        }
        b.palette = localIds.stream().mapToInt(Integer::intValue).toArray();
        // Jigsaws in Vanillas Reihenfolge: nach (y, x, z)
        jigs.sort(Comparator.<Object[]>comparingInt(o -> (int) o[1]).thenComparingInt(o -> (int) o[0]).thenComparingInt(o -> (int) o[2]));
        for (Object[] j : jigs) b.jigsaws.add(jigsawRow((int) j[0], (int) j[1], (int) j[2], (BlockState) j[3], (CompoundTag) j[4]));
        buildMips(b);
        return b;
    }

    private int palWarn;

    private BlockState[] readPalette(ListTag list) {
        BlockState[] out = new BlockState[list.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, list.getCompoundOrEmpty(i));
            } catch (RuntimeException e) {
                if (palWarn++ < 5) org.slf4j.LoggerFactory.getLogger("pyridium/structures").warn("Vorlagen-Palette: Zustand {} nicht lesbar: {}", list.getCompoundOrEmpty(i), e.toString());
                out[i] = Blocks.AIR.defaultBlockState();
            }
        }
        return out;
    }

    private int[] jigsawRow(int x, int y, int z, BlockState st, CompoundTag nbt) {
        int[] r = new int[StructureTables.JIG_W];
        r[StructureTables.JG_X] = x;
        r[StructureTables.JG_Y] = y;
        r[StructureTables.JG_Z] = z;
        var front = JigsawBlock.getFrontFacing(st);
        r[StructureTables.JG_FRONT] = front.get3DDataValue();
        r[StructureTables.JG_TOP] = JigsawBlock.getTopFacing(st).get3DDataValue();
        String joint = nbt == null ? "" : nbt.getStringOr("joint", "");
        boolean aligned = joint.isEmpty() ? front.getAxis().isHorizontal() : joint.equals("aligned");
        r[StructureTables.JG_JOINT] = aligned ? 1 : 0;
        String name = nbt == null ? "minecraft:empty" : nbt.getStringOr("name", "minecraft:empty");
        String pool = nbt == null ? "minecraft:empty" : nbt.getStringOr("pool", "minecraft:empty");
        String target = nbt == null ? "minecraft:empty" : nbt.getStringOr("target", "minecraft:empty");
        r[StructureTables.JG_NAME] = t.intern(norm(name));
        r[StructureTables.JG_POOL] = t.intern(norm(pool));      // wird vom Compiler zu Pool-Indizes aufgeloest
        r[StructureTables.JG_TARGET] = t.intern(norm(target));
        r[StructureTables.JG_PPRIO] = nbt == null ? 0 : nbt.getIntOr("placement_priority", 0);
        r[StructureTables.JG_SPRIO] = nbt == null ? 0 : nbt.getIntOr("selection_priority", 0);
        String fs = nbt == null ? "minecraft:air" : nbt.getStringOr("final_state", "minecraft:air");
        int fin = 0;
        try {
            BlockState fst = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, fs, true).blockState();
            fin = fst.is(Blocks.STRUCTURE_VOID) ? -1 : Block.BLOCK_STATE_REGISTRY.getId(fst);
        } catch (Exception e) {
            fin = -1;
        }
        r[StructureTables.JG_FINAL] = fin;
        return r;
    }

    static String norm(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** Dyadische Mip-Kette: je 2x2x2 der haeufigste nicht-Luft-Eintrag, sonst Luft-Eintrag, sonst 0 */
    private void buildMips(Baked b) {
        if (b.sx <= 0 || b.sy <= 0 || b.sz <= 0) return;
        short[] cur = b.voxels;
        int cx = b.sx, cy = b.sy, cz = b.sz;
        // Lokaler Index der Luft (0 wenn Luft nicht vorkommt)
        int airLocal = 0;
        int airId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        for (int i = 0; i < b.palette.length; i++) if (b.palette[i] == airId) airLocal = i + 1;
        int[] counts = new int[b.palette.length + 2];
        while (cx > 1 || cy > 1 || cz > 1) {
            int nx = (cx + 1) / 2, ny = (cy + 1) / 2, nz = (cz + 1) / 2;
            short[] nxt = new short[nx * ny * nz];
            for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
                java.util.Arrays.fill(counts, 0);
                boolean anyAir = false;
                for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                    int sx2 = x * 2 + dx, sy2 = y * 2 + dy, sz2 = z * 2 + dz;
                    if (sx2 >= cx || sy2 >= cy || sz2 >= cz) continue;
                    int v = cur[sx2 + cx * (sz2 + cz * sy2)] & 0xffff;
                    if (v == 0) continue;
                    if (v == airLocal) { anyAir = true; continue; }
                    counts[v]++;
                }
                int best = 0, bc = 0;
                for (int v = 1; v < counts.length; v++) if (counts[v] > bc) { bc = counts[v]; best = v; }
                nxt[x + nx * (z + nz * y)] = (short) (best != 0 ? best : anyAir ? airLocal : 0);
            }
            b.mips.add(nxt);
            b.mipDims.add(new int[]{nx, ny, nz});
            cur = nxt;
            cx = nx; cy = ny; cz = nz;
        }
    }
}
