package simon.pyridium.worldgen.structures.tools;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import simon.pyridium.worldgen.structures.StructureGpu;
import simon.pyridium.worldgen.structures.StructureTables;

/**
 * Testwerkzeug: Umgebungs-Kacheln aus Vanillas echter Welt (NoiseBasedChunkGenerator.getBaseHeight, MultiNoise-Biomquelle) fuer
 * wenige Kandidaten. Zeigt den Kachelvertrag mit echten Daten; die Produktionsanbieter schreiben dasselbe Format aus der GPU-Pipeline.
 */
public final class VanillaEnvProvider implements StructureGpu.EnvProvider {
    private final ChunkGenerator gen;
    private final BiomeSource source;
    private final RandomState rs;
    private final Map<String, Integer> universeIndex = new HashMap<>();
    private final StructureTables t;
    private final int maxTiles;
    private final String want;
    private final Set<Long> filledKeys = new HashSet<>();
    private final Map<Long, Integer> heightCache = new HashMap<>();
    private final LevelHeightAccessor acc = LevelHeightAccessor.create(-64, 384);
    public int filled;
    public double seconds;

    public VanillaEnvProvider(ChunkGenerator gen, BiomeSource source, RandomState rs, List<Holder.Reference<Biome>> universe, StructureTables t, int maxTiles, String want) {
        this.gen = gen;
        this.source = source;
        this.rs = rs;
        this.t = t;
        this.maxTiles = maxTiles;
        this.want = want;
        for (int i = 0; i < universe.size(); i++) universeIndex.put(universe.get(i).key().identifier().toString(), i);
    }

    private static long key(int set, int cx, int cz) {
        return ((long) set << 56) ^ ((long) cx << 28) ^ (cz & 0xfffffffL);
    }

    public boolean isFilled(int set, int cx, int cz) {
        return filledKeys.contains(key(set, cx, cz));
    }

    public int height(int x, int z) {
        return heightCache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, acc, rs));
    }

    @Override
    public void fillTiles(StructureGpu sg) {
        var gpu = sg.gpu();
        gpu.sync();
        long bytes = sg.candidateBuffer()[2];
        long[] rb = gpu.readbackBuffer(bytes);
        gpu.downloadAsync(rb[1], sg.candidateBuffer()[0], bytes);
        gpu.sync();
        MemorySegment c = MemorySegment.ofAddress(rb[1]).reinterpret(bytes);
        int n = Math.min(c.get(ValueLayout.JAVA_INT, 0), sg.capCand);
        long t0 = System.nanoTime();
        BiomeResolver res = source.createResolver(rs.createClimateSampler(SamplerContext.builder().enableCaches().build()));
        for (int i = 0; i < n && filled < maxTiles; i++) {
            int set = c.get(ValueLayout.JAVA_INT, StructureGpu.CAND_HDR * 4L + i * 16L);
            int cx = c.get(ValueLayout.JAVA_INT, StructureGpu.CAND_HDR * 4L + i * 16L + 4);
            int cz = c.get(ValueLayout.JAVA_INT, StructureGpu.CAND_HDR * 4L + i * 16L + 8);
            int off = c.get(ValueLayout.JAVA_INT, StructureGpu.CAND_HDR * 4L + i * 16L + 12);
            if (off < 0 || !t.setNames.get(set).contains(want)) continue;
            int b = set * StructureTables.SET_W;
            int R = t.sets.getInt(b + StructureTables.SET_ENV_R), flags = t.sets.getInt(b + StructureTables.SET_ENV_FLAGS), ints = t.sets.getInt(b + StructureTables.SET_ENV_INTS);
            int hw = 2 * R + 16, surf = (hw * hw + 1) / 2;
            boolean floor = (flags & 1) != 0;
            int[] tile = new int[ints];
            tile[0] = 1;
            tile[1] = cx * 16 - R; tile[2] = cz * 16 - R; tile[3] = hw; tile[4] = 1;
            tile[5] = 16; tile[6] = floor ? 16 + surf : -1;
            tile[7] = (cx * 16 - 16) >> 2; tile[8] = (cz * 16 - 16) >> 2; tile[9] = 12; tile[10] = t.minY >> 2; tile[11] = t.height >> 2;
            tile[12] = 16 + surf + (floor ? surf : 0);
            for (int k = 0; k < hw * hw; k++) {
                int x = tile[1] + k % hw, z = tile[2] + k / hw;
                int h = height(x, z);
                tile[16 + (k >> 1)] |= (h & 0xffff) << ((k & 1) * 16);
                if (floor) {
                    int f = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, acc, rs);
                    tile[16 + surf + (k >> 1)] |= (f & 0xffff) << ((k & 1) * 16);
                }
            }
            int bqh = t.height >> 2;
            byte[] bio = new byte[12 * 12 * bqh];
            for (int k = 0; k < bio.length; k++) {
                int qx = tile[7] + k % 12, qz = tile[8] + (k / 12) % 12, qy = tile[10] + k / 144;
                Integer idx = universeIndex.get(res.getNoiseBiome(qx, qy, qz).unwrapKey().get().identifier().toString());
                bio[k] = (byte) (idx == null ? 255 : idx);
            }
            for (int k = 0; k < bio.length; k++) tile[tile[12] + (k >> 2)] |= (bio[k] & 0xff) << ((k & 3) * 8);
            // hochladen
            long[] up = gpu.readbackBuffer(ints * 4L);
            MemorySegment.copy(MemorySegment.ofArray(tile), 0, MemorySegment.ofAddress(up[1]).reinterpret(ints * 4L), 0, ints * 4L);
            gpu.uploadAsync(sg.envBuffer()[0] + (StructureGpu.ENV_BASE + (long) off) * 4L, up[1], ints * 4L);
            gpu.sync();
            filledKeys.add(key(set, cx, cz));
            filled++;
        }
        seconds += (System.nanoTime() - t0) / 1e9;
    }

    @Override
    public void fillRingPlanes(StructureGpu sg) {
    }
}
