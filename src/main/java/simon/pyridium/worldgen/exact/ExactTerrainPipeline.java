package simon.pyridium.worldgen.exact;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
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
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;

/**
 * Exakte Terrainstufe auf der GPU (NoiseBasedChunkGenerator.buildTerrain ohne Carver): Dichte,
 * Aquifer (doFill) und MaterialSystem.buildSurface, bitgleich zu Vanilla. Einmal je Generator
 * aufgebaut (Plan, Tabellen, Pipelines, Puffer fuer {@code capacity} Chunks); {@link #run} rechnet
 * einen Stapel. Biome kommen als Fenster (6 x 6 Quarts x Hoehe, x/z -1..4) vom Aufrufer, wie
 * Vanillas BiomeManager sie aus der Region liest. Nicht threadsicher (ein GPU-Thread).
 */
public final class ExactTerrainPipeline implements AutoCloseable {
    /** Binaerdateien aus dem Mod-Jar: PTX (build/cuda, Gradle-Task compileCuda) unter assets/pyridium/shaders/ptx. */
    public static final GpuCompute.BinarySource JAR = name -> {
        try (InputStream in = ExactTerrainPipeline.class.getResourceAsStream("/assets/pyridium/shaders/ptx/" + name)) {
            if (in == null) throw new IOException("Kernel fehlt im Jar: " + name);
            return in.readAllBytes();
        }
    };

    public final int minY, height, capacity, qh;
    public final ExactPlan plan;
    public final ExactBiomes biomes;
    private final Map<ResourceKey<Biome>, Integer> biomeByKey = new HashMap<>();
    private final GpuCompute vk;
    private final int cells, stateWords;
    private final long[] bChunk, bWin, bSurfIdx, bSurfPos, bReadback;
    private final long[] bState;
    /** Beardifier je Chunk (Binding 10 der Aufgaben-Shader): Kopf capacity*4, danach Daten. */
    private final long[] bBeard;
    private final int beardWords;
    private int beardFill;
    /** Punktprogramm liest den Beardifier: Chunks mit Strukturen dann nicht exakt rechenbar. */
    public final boolean pointsUseBeardifier;
    private final GpuCompute.Kernel coords, taskPipe, taskPt, aqPipe, sfPipe;
    private final List<GpuCompute.Kernel> groupPipes;
    private final List<GpuCompute.Kernel> pipes = new ArrayList<>();
    /** GPU-Wandzeit des letzten Stapels (ms). */
    public double lastMs;

    /** Warum der Generator nicht unterstuetzt wird (leer = ok); vor {@link #create} pruefbar. */
    public record Model(ExactPlan plan, ExactAquifer aquifer, ExactBiomes biomes, ExactMaterials materials, List<String> unsupported) {
    }

    /** Plan und Tabellen (nur CPU); unsupported leer, wenn die GPU exakt rechnen kann. */
    public static Model model(RandomState rs, NoiseGeneratorSettings settings, BiomeSource biomeSource, WorldGenerationContext genCtx,
                              int minY, int height) {
        var router = settings.noiseRouter();
        List<OreVeinRule> ores = ExactMaterials.oreVeins(settings.materialRule().value());
        List<DensitySampler> roots = new ArrayList<>();
        List<ExactPlan.Vol> vols = new ArrayList<>();
        roots.add(rs.getSampler(router.finalDensity()));
        vols.add(ExactPlan.chunkVol(minY, height));
        roots.add(rs.getSampler(rs.surfaceSystem().preliminarySurfaceFunction()));
        vols.add(new ExactPlan.Vol(16, 1, 16, 0, 0, 0, 1, 1, 1, 0));
        for (OreVeinRule o : ores) {
            roots.add(rs.getSampler(o.density()));
            vols.add(ExactPlan.chunkVol(minY, height));
            roots.add(rs.getSampler(o.richness()));
            vols.add(ExactPlan.chunkVol(minY, height));
        }
        ExactPlan plan = ExactPlan.forRoots(roots, vols, false);
        ExactAquifer aq = new ExactAquifer(rs, settings, plan.points, minY, height);
        ExactBiomes eb = new ExactBiomes(biomeSource);
        ExactMaterials mats = new ExactMaterials(rs, settings, genCtx, eb, plan, plan.points);
        mats.prepareSimplex();
        mats.preliminarySurfaceTask = plan.tasks.get(plan.roots.getInt(1)).offset;
        for (int k = 0; k < ores.size(); k++)
            mats.setOreTasks(k, plan.tasks.get(plan.roots.getInt(2 + 2 * k)).offset, plan.tasks.get(plan.roots.getInt(3 + 2 * k)).offset);
        List<String> unsup = new ArrayList<>();
        unsup.addAll(plan.unsupported);
        unsup.addAll(plan.points.unsupported);
        unsup.addAll(aq.unsupported);
        unsup.addAll(eb.unsupported);
        unsup.addAll(mats.unsupported);
        if (plan.points.regCount > 384) unsup.add("Punktprogramm braucht " + plan.points.regCount + " Register");
        return new Model(plan, aq, eb, mats, unsup);
    }

    /**
     * Baut Puffer und Kernel. codegen: grosse Gruppen per NVRTC spezialisieren (fehlt NVRTC,
     * rechnet der Interpreter).
     */
    public ExactTerrainPipeline(GpuCompute vk, Model m, int minY, int height, int maxY, long seed, int capacity, GpuCompute.BinarySource spv,
                                boolean codegen) throws Exception {
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
        cells = ExactAquifer.cells(minY, height);
        stateWords = ExactAquifer.stateWords(minY, height);
        int[] tasks = plan.encodeTasks(); // setzt Stapel-Offsets (vor Materialien)
        long blocks = 256L * height;
        long[] bTask = vk.upload(tasks), bParam = vk.upload(plan.params.toIntArray());
        long[] bLayer = vk.upload(plan.encodeLayers()), bStack = vk.upload(plan.encodeStacks());
        long[] bSeg = vk.upload(plan.encodeSegs());
        bChunk = vk.buffer(capacity * 8L);
        long[] bCoord = vk.deviceBuffer((long) capacity * Math.max(1, plan.coordEntries) * 12L);
        long[] bScratch = vk.deviceBuffer((long) capacity * plan.scratchFloats * 4L);
        long[] bCode = vk.upload(plan.points.encodeCode()), bPParam = vk.upload(plan.points.params.toIntArray());
        int[] aqp = m.aquifer().encode();
        aqp[14] = plan.tasks.get(plan.root).offset;
        aqp[15] = stateWords;
        long[] bAq = vk.upload(aqp), bAqState = vk.deviceBuffer((long) capacity * stateWords * 4L);
        long[] bSubst = vk.deviceBuffer(capacity * blocks * 4L);
        bSurfIdx = vk.buffer(capacity * 35L * 4L);
        bSurfPos = vk.buffer(capacity * 35L * 8L);
        long[] bSurfVal = vk.deviceBuffer(capacity * 35L * 16L * 4L);
        long[] bSf = vk.upload(m.materials().encode(minY, height, maxY, seed));
        bWin = vk.buffer((long) capacity * 36 * qh * 4L);
        bState = vk.deviceBuffer(capacity * blocks * 4L);
        long[] bStateTmp = vk.deviceBuffer(capacity * blocks * 4L);
        long[] bCol = vk.deviceBuffer(capacity * 256L * 4 * 4L);
        bReadback = vk.readbackBuffer(capacity * blocks * 4L);
        beardWords = capacity * 4 + capacity * 2048;
        bBeard = vk.buffer(beardWords * 4L);
        beardFill = capacity * 4;
        pointsUseBeardifier = plan.points.usesBeardifier;

        coords = pipe(spv, "wg_exact_coords", 10, 64);
        taskPipe = pipe(spv, "wg_exact_task", 11, 128);
        taskPt = pipe(spv, "wg_exact_task_pt", 11, 128);
        aqPipe = pipe(spv, "wg_exact_aquifer", 16, 64);
        sfPipe = pipe(spv, "wg_exact_surface", 16, 64);
        long[][] base = {bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCode, bPParam, bBeard};
        coords.bind(java.util.Arrays.copyOf(base, 10));
        taskPipe.bind(base);
        taskPt.bind(base);
        aqPipe.bind(bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCode, bPParam, bAq, bAqState, bSubst,
                bSurfIdx, bSurfVal, bSurfPos);
        sfPipe.bind(bTask, bParam, bLayer, bStack, bSeg, bChunk, bCoord, bScratch, bCode, bPParam, bSf, bSubst, bWin, bState,
                bStateTmp, bCol);
        groupPipes = codegen ? vk.groupKernels(plan, false, 11)
                : new ArrayList<>(java.util.Collections.nCopies(plan.groups.size(), null));
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

    /** Biom-Index fuer Fenster (-1: unbekannt, Stapel dann nicht exakt rechenbar). */
    public int biomeIndex(Holder<Biome> b) {
        int i = biomes.indexOf(b);
        if (i >= 0) return i;
        Integer k = b.unwrapKey().map(biomeByKey::get).orElse(null);
        return k == null ? -1 : k;
    }

    /** Fenster-Index eines Quarts: x/z 0..5 (= Quart -1..4 relativ zum Chunk), y 0..qh-1. */
    public int windowIndex(int chunk, int ix, int y, int iz) {
        return chunk * 36 * qh + y + (ix + iz * 6) * qh;
    }

    /** Vor dem Befuellen eines Stapels: Beardifier-Daten aller Chunks leeren. */
    public void clearBeard() {
        mem(bBeard[1], capacity * 16L).fill((byte) 0);
        beardFill = capacity * 4;
    }

    /**
     * Beardifier-Daten (ExactBeard.encode) fuer Chunk c des naechsten Stapels; false, wenn der
     * Puffer voll ist (Chunk dann ueber Vanilla).
     */
    public boolean setBeard(int c, int @org.jspecify.annotations.Nullable [] data) {
        long head = bBeard[1] + c * 16L;
        if (data == null) {
            putInt(head + 12, 0);
            return true;
        }
        int body = data.length - 2; // ohne die beiden Zaehler
        if (beardFill + body > beardWords) return false;
        long at = bBeard[1] + beardFill * 4L;
        for (int i = 0; i < 6; i++) putInt(at + i * 4L, data[i]);
        for (int i = 8; i < data.length; i++) putInt(at + (i - 2) * 4L, data[i]);
        putInt(head, beardFill);
        putInt(head + 4, data[6]);
        putInt(head + 8, data[7]);
        putInt(head + 12, 1);
        beardFill += body;
        return true;
    }

    /** Fenster-Puffer (n * 36 * qh Biom-Indizes) zum Befuellen vor {@link #run}. */
    public IntBuffer windows() {
        return ints(bWin[1], capacity * 36 * qh);
    }

    /**
     * Rechnet n Chunks (Blockminima in chunkXZ, Fenster vorher in {@link #windows()}); danach liefert
     * {@link #state} die Zustands-IDs (Bit 31: Post-Processing aus doFill, Bit 30: aus MaterialSystem).
     */
    public void run(int n, int[] chunkXZ) {
        if (n <= 0 || n > capacity) throw new IllegalArgumentException("Stapel " + n + " / " + capacity);
        ints(bChunk[1], n * 2).put(0, chunkXZ, 0, n * 2);
        ExactAquifer.SurfTable st = ExactAquifer.surfaceTable(chunkXZ, n);
        ints(bSurfIdx[1], n * 35).put(0, st.idx(), 0, n * 35);
        ints(bSurfPos[1], st.count() * 2).put(0, st.pos(), 0, st.count() * 2);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        int[] push = {0, n, plan.scratchFloats, plan.coordEntries, plan.coordSegs.size(), 0, 0, 0};
        if (plan.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords, push.clone(), (plan.coordEntries + 63) / 64, n));
        GpuCompute.addGroups(list, plan, groupPipes, taskPipe, taskPt, push, n);
        int[] a0 = push.clone();
        a0[0] = 0;
        a0[7] = st.count();
        list.add(new GpuCompute.Dispatch(aqPipe, a0, (st.count() * 16 + 63) / 64, 1));
        int[] a1 = push.clone();
        a1[0] = 1;
        list.add(new GpuCompute.Dispatch(aqPipe, a1, (cells + 64) / 64, n));
        int[] a2 = push.clone();
        a2[0] = 2;
        list.add(new GpuCompute.Dispatch(aqPipe, a2, (256 * height + 63) / 64, n));
        for (int pass : new int[]{2, 0, 1, 3}) {
            int[] p = push.clone();
            p[0] = pass;
            list.add(new GpuCompute.Dispatch(sfPipe, p, 4, n));
        }
        long bytes = (long) n * 256 * height * 4;
        lastList = list;
        lastMs = vk.run(list, List.<long[]>of(new long[]{bState[0], bReadback[0], bytes}));
    }

    private List<GpuCompute.Dispatch> lastList = List.of();

    /** Diagnose: letzten Stapel erneut mit Zeitstempeln je Dispatch (ms) und Namen. */
    public List<String> profileLast() {
        double[] t = vk.runTimed(lastList);
        List<String> out = new ArrayList<>();
        int groups = plan.groups.size(), gi = 0;
        for (int i = 0; i < t.length; i++) {
            GpuCompute.Dispatch d = lastList.get(i);
            String name;
            if (d.kernel() == coords) name = "coords";
            else if (d.kernel() == aqPipe) name = "aquifer" + d.push()[0];
            else if (d.kernel() == sfPipe) name = "surface" + d.push()[0];
            else {
                int[] g = plan.groups.get(Math.min(gi++, groups - 1));
                name = "grp#" + g[0] + "x" + g[1] + " n=" + plan.groupSize(g) + (groupPipes.get(gi - 1) != null ? " (codegen)" : "");
            }
            out.add(String.format("%8.3f ms  %s", t[i], name));
        }
        return out;
    }

    /** Ergebnis des letzten Stapels: Zustands-ID von Block (x, minY + yi, z) in Chunk c (Bits 30/31 = Post-Processing-Marken). */
    public int state(int c, int yi, int x, int z) {
        return mem(bReadback[1] + (((long) c * height + yi) * 256 + z + x * 16) * 4, 4).get(ValueLayout.JAVA_INT, 0);
    }

    /** Kopie des Ergebnisses von Chunk c (height * 256 Werte, Index yi * 256 + z + x * 16). */
    public int[] copyChunk(int c) {
        int[] out = new int[height * 256];
        ints(bReadback[1] + (long) c * height * 256 * 4, out.length).get(out);
        return out;
    }

    /** Schicht yi von Chunk c: 256 Werte, Index z + x * 16. */
    public IntBuffer layer(int c, int yi) {
        return ints(bReadback[1] + ((long) c * height + yi) * 256 * 4, 256);
    }

    // Host-Speicher der Puffer (gepinnt, Adresse aus GpuCompute) ueber FFM statt LWJGL-MemoryUtil:
    // auf dem dedizierten Server gibt es kein LWJGL.
    public static MemorySegment mem(long address, long bytes) {
        return MemorySegment.ofAddress(address).reinterpret(bytes);
    }

    public static IntBuffer ints(long address, int count) {
        return mem(address, count * 4L).asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static void putInt(long address, int value) {
        mem(address, 4).set(ValueLayout.JAVA_INT, 0, value);
    }

    @Override
    public void close() {
        for (GpuCompute.Kernel p : pipes) p.close();
        pipes.clear();
    }
}
