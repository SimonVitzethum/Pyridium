package simon.pyridium.worldgen.exact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import simon.pyridium.worldgen.full.TerrainSource;

/**
 * Fast terrain stage (not bit-exact): the surface comes from the preliminary surface function on a coarse 4-block grid
 * (bilinear + hash noise) instead of the per-block 3D density; aquifers are replaced by sea-level water; the datapack's
 * SurfaceRules still run (copy of the exact surface kernel, wg_full_surface.cu) but skip deep rock and ore veins.
 * Cost is a few percent of {@link ExactTerrainPipeline}. Caves come from the carver stage.
 */
public final class FastTerrainPipeline implements TerrainSource {
    public final int minY, height, capacity, qh;
    public final ExactPlan plan;
    public final ExactBiomes biomes;
    private final Map<ResourceKey<Biome>, Integer> biomeByKey = new HashMap<>();
    private final GpuCompute vk;
    private final long[] bChunk, bWin, bState;
    private final GpuCompute.Kernel coords, taskPipe, taskPt, hf, sf;
    private final List<GpuCompute.Kernel> groupPipes;
    private final List<GpuCompute.Kernel> pipes = new ArrayList<>();
    private final int gapA, gapB, skipDepth;
    private List<GpuCompute.Dispatch> lastList = List.of();

    public record Model(ExactPlan plan, ExactBiomes biomes, ExactMaterials materials, List<String> unsupported) {
    }

    /** Approximate device bytes per chunk (VRAM sizing), without the biome/pack stages. */
    public static long bytesPerChunk(Model m, int height, int qh) {
        return 1L * 256 * height * 4 + (long) m.plan().scratchFloats * 4 + (long) m.plan().coordEntries * 12 + 256L * 16 + 36L * qh * 4 * 2;
    }

    public static Model model(RandomState rs, NoiseGeneratorSettings settings, BiomeSource source, WorldGenerationContext ctx, int minY, int height) {
        DensitySampler prelim = rs.getSampler(rs.surfaceSystem().preliminarySurfaceFunction());
        List<DensitySampler> roots = List.of(prelim, prelim);
        List<ExactPlan.Vol> vols = List.of(new ExactPlan.Vol(16, 1, 16, 0, 0, 0, 1, 1, 1, 0), System.getProperty("pyridium.fastDebug", "").contains("vol0") ? new ExactPlan.Vol(6, 1, 6, 0, 0, 0, 4, 1, 4, 0) : new ExactPlan.Vol(6, 1, 6, -4, 0, -4, 4, 1, 4, 0));
        ExactPlan plan = ExactPlan.forRoots(roots, vols, false);
        if (!System.getProperty("pyridium.fastDebug", "").contains("nop2")) for (ExactPlan.Task t : plan.tasks) if (t.op == ExactPlan.OP_FINDTOP) t.p2 = Math.min(t.p2, 2);   // finer top-surface search
        ExactBiomes eb = new ExactBiomes(source);
        ExactMaterials mats = new ExactMaterials(rs, settings, ctx, eb, plan, plan.points);
        mats.prepareSimplex();
        mats.preliminarySurfaceTask = plan.tasks.get(plan.roots.getInt(0)).offset;
        List<String> unsup = new ArrayList<>(plan.unsupported);
        unsup.addAll(plan.points.unsupported);
        unsup.addAll(eb.unsupported);
        unsup.addAll(mats.unsupported);
        if (plan.points.regCount > 384) unsup.add("Punktprogramm braucht " + plan.points.regCount + " Register");
        return new Model(plan, eb, mats, unsup);
    }

    public FastTerrainPipeline(GpuCompute vk, Model m, int minY, int height, int maxY, int sea, long seed, int capacity,
                               GpuCompute.BinarySource src, boolean codegen) throws Exception {
        if (!m.unsupported().isEmpty()) throw new IllegalArgumentException("nicht unterstuetzt: " + m.unsupported());
        this.vk = vk;
        this.minY = minY;
        this.height = height;
        this.capacity = capacity;
        this.qh = height >> 2;
        this.plan = m.plan();
        this.biomes = m.biomes();
        for (int i = 0; i < biomes.biomes.size(); i++) {
            int idx = i;
            biomes.biomes.get(i).unwrapKey().ifPresent(k -> biomeByKey.put(k, idx));
        }
        int[] tasks = plan.encodeTasks();
        long blocks = 256L * height;
        long[] bTask = vk.upload(tasks), bParam = vk.upload(plan.params.toIntArray());
        long[] bLayer = vk.upload(plan.encodeLayers()), bStack = vk.upload(plan.encodeStacks()), bSeg = vk.upload(plan.encodeSegs());
        bChunk = vk.buffer(capacity * 8L);
        long[] bCoord = vk.deviceBuffer((long) capacity * Math.max(1, plan.coordEntries) * 12L);
        long[] bScratch = vk.deviceBuffer((long) capacity * plan.scratchFloats * 4L);
        long[] bCode = vk.upload(plan.points.encodeCode()), bPParam = vk.upload(plan.points.params.toIntArray());
        long[] bZero = vk.buffer(64 + capacity * 16L);
        long[] bSf = vk.upload(m.materials().encode(minY, height, maxY, seed));
        bWin = vk.buffer((long) capacity * 36 * qh * 4L);
        bState = vk.deviceBuffer(capacity * blocks * 4L);
        long[] bCol = vk.deviceBuffer(capacity * 256L * 4 * 4L);
        int[] cfg = new int[11];
        cfg[0] = minY;
        cfg[1] = height;
        cfg[2] = sea;
        cfg[3] = plan.tasks.get(plan.roots.getInt(1)).offset;
        cfg[4] = (int) seed;
        cfg[5] = (int) (seed >>> 32);
        cfg[6] = Float.floatToRawIntBits(2.5f);
        cfg[7] = Float.floatToRawIntBits(1.0f);
        cfg[8] = m.materials().defaultState;
        cfg[9] = m.materials().waterState;
        cfg[10] = m.materials().airState;
        long[] bCfg = vk.upload(cfg);
        org.slf4j.LoggerFactory.getLogger("pyridium/fast-terrain").info("Plan: scratch {} floats/Chunk, coords {}, Gruppen {}, prelim16 offset {}, coarse offset {}, Stapel {}", plan.scratchFloats, plan.coordEntries, plan.groups.size(), plan.tasks.get(plan.roots.getInt(0)).offset, cfg[3], capacity);
        // skip gaps: y ranges without vertical-gradient rules (bedrock / deepslate)
        int[] conds = m.materials().encodeConds();
        List<int[]> bands = new ArrayList<>();
        for (int i = 0; i + 2 < conds.length; i += ExactMaterials.COND_WORDS)
            if (conds[i] == ExactMaterials.C_VGRAD) bands.add(new int[]{Math.min(conds[i + 1], conds[i + 2]), Math.max(conds[i + 1], conds[i + 2])});
        bands.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> gaps = new ArrayList<>();
        int cur = minY;
        for (int[] b : bands) {
            if (b[0] - 1 > cur) gaps.add(new int[]{cur, b[0] - 1});
            cur = Math.max(cur, b[1] + 1);
        }
        gaps.add(new int[]{cur, maxY});
        gaps.sort((a, b) -> Integer.compare(b[1] - b[0], a[1] - a[0]));
        gapA = pack(gaps.size() > 0 ? gaps.get(0) : null);
        gapB = pack(gaps.size() > 1 ? gaps.get(1) : null);

        // rock deeper than skipDepth keeps the default block; clay bands (badlands) reach deep, so they disable the shortcut
        boolean clayBands = false;
        for (int i = 0; i < m.materials().rules.size(); ) {
            int op = m.materials().rules.getInt(i);
            if (op == ExactMaterials.R_BANDS) {
                clayBands = true;
                i += 1;
            } else if (op == ExactMaterials.R_IF) i += 3;
            else if (op == ExactMaterials.R_BLOCK || op == ExactMaterials.R_ORE) i += 2;
            else i += 1;
        }
        skipDepth = clayBands ? 14 : 7;
        coords = pipe(src, "wg_exact_coords", 10, 64);
        taskPipe = pipe(src, "wg_exact_task", 11, 128);
        taskPt = pipe(src, "wg_exact_task_pt", 11, 128);
        hf = pipe(src, "wg_full_hf", 16, 256);
        sf = pipe(src, "wg_full_surface", 16, 64);
        long[][] base = {bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCode, bPParam, bZero};
        coords.bind(java.util.Arrays.copyOf(base, 10));
        taskPipe.bind(base);
        taskPt.bind(base);
        hf.bind(bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCfg, bZero, bZero, bZero, bZero, bState, bZero, bCol);
        sf.bind(bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCode, bPParam, bSf, bZero, bWin, bState, bState, bCol);
        groupPipes = codegen ? vk.groupKernels(plan, false, 11) : new ArrayList<>(java.util.Collections.nCopies(plan.groups.size(), null));
        for (GpuCompute.Kernel p : groupPipes) {
            if (p != null) {
                p.bind(base);
                pipes.add(p);
            }
        }
    }

    private static int pack(int[] g) {
        if (g == null || g[1] - g[0] < 8) return (4096 + 1) | ((4096 - 1) << 16);   // empty (lo > hi)
        int lo = g[0] + 1, hi = g[1] - 1;
        return (lo + 4096) | ((hi + 4096) << 16);
    }

    private GpuCompute.Kernel pipe(GpuCompute.BinarySource src, String name, int bindings, int block) throws Exception {
        GpuCompute.Kernel p = vk.kernel(src, name, bindings, block);
        pipes.add(p);
        return p;
    }

    @Override
    public List<GpuCompute.Dispatch> prepare(int n, int[] chunkXZ) {
        if (n <= 0 || n > capacity) throw new IllegalArgumentException("Stapel " + n + " / " + capacity);
        ExactTerrainPipeline.ints(bChunk[1], n * 2).put(0, chunkXZ, 0, n * 2);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        int[] push = {0, n, plan.scratchFloats, plan.coordEntries, plan.coordSegs.size(), 0, 0, 0};
        if (plan.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords, push.clone(), (plan.coordEntries + 63) / 64, n));
        GpuCompute.addGroups(list, plan, groupPipes, taskPipe, taskPt, push, n);
        list.add(new GpuCompute.Dispatch(hf, push.clone(), n, 1));
        int[] p = push.clone();
        p[0] = 1;                 // surface pass 1 only (no prediction pass, no aquifer marks)
        p[3] = gapA;
        p[4] = gapB;
        p[7] = skipDepth;
        list.add(new GpuCompute.Dispatch(sf, p, 4, n));
        lastList = list;
        return list;
    }

    @Override
    public List<String> profileLast() {
        double[] t = vk.runTimed(lastList);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            GpuCompute.Dispatch d = lastList.get(i);
            String name = d.kernel() == coords ? "coords" : d.kernel() == hf ? "heightfield" : d.kernel() == sf ? "surface" + d.push()[0] : "plan-group";
            out.add(String.format("%8.3f ms  %s", t[i], name));
        }
        return out;
    }

    @Override
    public int biomeIndex(Holder<Biome> b) {
        int i = biomes.indexOf(b);
        if (i >= 0) return i;
        Integer k = b.unwrapKey().map(biomeByKey::get).orElse(null);
        return k == null ? -1 : k;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int qh() {
        return qh;
    }

    @Override
    public long[] stateBuffer() {
        return bState;
    }

    @Override
    public long[] windowBuffer() {
        return bWin;
    }

    @Override
    public long[] chunkBuffer() {
        return bChunk;
    }

    @Override
    public void close() {
        for (GpuCompute.Kernel p : pipes) p.close();
        pipes.clear();
    }
}
