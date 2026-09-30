package simon.pyridium.worldgen.exact;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import org.jspecify.annotations.Nullable;

/**
 * Exakte BIOMES-Stufe auf der GPU (ChunkGenerator.doCreateBiomes mit MultiNoiseBiomeSource):
 * sechs Klimafunktionen auf dem Quart-Raster des Chunks, Climate.target und R-Baum-Suche
 * (wg_exact_biome.cu). Der R-Baum-Startkandidat ist je Chunk zurueckgesetzt; Vanilla traegt ihn
 * thread-lokal von Chunk zu Chunk weiter und ist bei Gleichstaenden selbst nicht deterministisch.
 * Ergebnis je Chunk: Biom je Quart, Index qy + (qx + qz * 4) * qh. Nicht threadsicher.
 */
public final class ExactBiomePipeline implements AutoCloseable {
    public final int minY, height, qh, quarts, capacity;
    public final ExactPlan plan;
    public final ExactBiomes biomes;
    private final GpuCompute vk;
    private final long[] bChunk, bReadback, bOut;
    private final GpuCompute.Kernel coords, taskPipe, biomePipe;
    private final List<GpuCompute.Kernel> groupPipes = new ArrayList<>();
    private final List<GpuCompute.Kernel> pipes = new ArrayList<>();
    public double lastMs;

    /** Plan (nur CPU); leer = unterstuetzt. */
    public record Model(ExactPlan plan, ExactBiomes biomes, List<String> unsupported) {
    }

    public static Model model(RandomState rs, NoiseGeneratorSettings settings, BiomeSource source, int minY, int height) {
        var router = settings.noiseRouter();
        List<DensitySampler> roots = new ArrayList<>();
        List<ExactPlan.Vol> vols = new ArrayList<>();
        for (var f : List.of(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges())) {
            roots.add(rs.getSampler(f));
            vols.add(ExactPlan.quartVol(minY, height));
        }
        ExactPlan plan = ExactPlan.forRoots(roots, vols, false);
        ExactBiomes eb = new ExactBiomes(source);
        List<String> unsup = new ArrayList<>();
        unsup.addAll(plan.unsupported);
        unsup.addAll(eb.unsupported);
        if (plan.tasks.stream().anyMatch(t -> t.op == ExactPlan.OP_BEARD)) unsup.add("Klima liest den Beardifier");
        return new Model(plan, eb, unsup);
    }

    public ExactBiomePipeline(GpuCompute vk, Model m, int minY, int height, int capacity, GpuCompute.BinarySource spv, boolean codegen)
            throws Exception {
        if (!m.unsupported().isEmpty()) throw new IllegalArgumentException("nicht unterstuetzt: " + m.unsupported());
        this.vk = vk;
        this.minY = minY;
        this.height = height;
        this.qh = height >> 2;
        this.quarts = 16 * qh;
        this.capacity = capacity;
        this.plan = m.plan();
        this.biomes = m.biomes();
        int[] tasks = plan.encodeTasks();
        long[] bTask = vk.upload(tasks), bParam = vk.upload(plan.params.toIntArray());
        long[] bLayer = vk.upload(plan.encodeLayers()), bStack = vk.upload(plan.encodeStacks());
        long[] bSeg = vk.upload(plan.encodeSegs());
        bChunk = vk.buffer(capacity * 8L);
        long[] bCoord = vk.deviceBuffer((long) capacity * Math.max(1, plan.coordEntries) * 12L);
        long[] bScratch = vk.deviceBuffer((long) capacity * plan.scratchFloats * 4L);
        long[] bZero = vk.buffer(64 + capacity * 16L); // Bindungen 8-10 (Punktcode, Beardifier) ungenutzt
        int[] bp = new int[9];
        bp[0] = biomes.nodeCount;
        bp[1] = quarts;
        bp[2] = qh;
        for (int i = 0; i < 6; i++) bp[3 + i] = plan.tasks.get(plan.roots.getInt(i)).offset;
        long[] bBp = vk.upload(bp), bNode = vk.upload(biomes.nodes);
        long[] bTmp = vk.deviceBuffer((long) capacity * quarts * 17 * 4L);
        bOut = vk.deviceBuffer((long) capacity * quarts * 4L);
        bReadback = vk.readbackBuffer((long) capacity * quarts * 4L);
        coords = pipe(spv, "wg_exact_coords", 10, 64);
        taskPipe = pipe(spv, "wg_exact_task", 11, 128);
        biomePipe = pipe(spv, "wg_exact_biome", 12, 64);
        long[][] base = {bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bZero, bZero, bZero};
        coords.bind(java.util.Arrays.copyOf(base, 10));
        taskPipe.bind(base);
        biomePipe.bind(bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bBp, bNode, bTmp, bOut);
        groupPipes.addAll(codegen ? vk.groupKernels(plan, false, 11)
                : java.util.Collections.nCopies(plan.groups.size(), null));
        for (GpuCompute.Kernel p : groupPipes) {
            if (p != null) {
                p.bind(base);
                pipes.add(p);
            }
        }
    }

    private GpuCompute.Kernel pipe(GpuCompute.BinarySource spv, String name, int bindings, int block) throws Exception {
        GpuCompute.Kernel p = vk.kernel(spv, name, bindings, block);
        pipes.add(p);
        return p;
    }

    /** Rechnet n Chunks (Blockminima x/z in chunkXZ); danach {@link #biome}. */
    public void run(int n, int[] chunkXZ) {
        if (n <= 0 || n > capacity) throw new IllegalArgumentException("Stapel " + n + " / " + capacity);
        ExactTerrainPipeline.ints(bChunk[1], n * 2).put(0, chunkXZ, 0, n * 2);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        int[] push = {0, n, plan.scratchFloats, plan.coordEntries, plan.coordSegs.size(), 0, 0, 0};
        if (plan.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords, push.clone(), (plan.coordEntries + 63) / 64, n));
        GpuCompute.addGroups(list, plan, groupPipes, taskPipe, null, push, n);
        int[] p1 = push.clone();
        p1[0] = 1;
        list.add(new GpuCompute.Dispatch(biomePipe, push.clone(), (quarts + 63) / 64, n));
        list.add(new GpuCompute.Dispatch(biomePipe, p1, 1, n));
        lastMs = vk.run(list, List.<long[]>of(new long[]{bOut[0], bReadback[0], (long) n * quarts * 4}));
    }

    /** Biom-Indizes von Chunk c (quarts Werte, Index qy + (qx + qz * 4) * qh). */
    public int[] copyChunk(int c) {
        int[] out = new int[quarts];
        ExactTerrainPipeline.ints(bReadback[1] + (long) c * quarts * 4, quarts).get(out);
        return out;
    }

    public @Nullable Holder<Biome> biome(int index) {
        return index >= 0 && index < biomes.biomes.size() ? biomes.biomes.get(index) : null;
    }

    @Override
    public void close() {
        for (GpuCompute.Kernel p : pipes) p.close();
        pipes.clear();
    }
}
