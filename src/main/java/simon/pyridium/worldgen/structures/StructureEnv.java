package simon.pyridium.worldgen.structures;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.ExactBiomes;
import simon.pyridium.worldgen.exact.ExactPlan;
import simon.pyridium.worldgen.exact.GpuCompute;

/**
 * Produktions-Umgebungsanbieter der Strukturgenerierung ({@link StructureGpu.EnvProvider}): fuellt die Kacheln jedes platzierten
 * Kandidaten mit den Hoehen und Biomen der echten Gelaendefunktion der LOD-Pipeline (vorlaeufige Oberflaeche + Klima, wie
 * {@code ExactLodPipeline.plan2d}), ganz auf der GPU im selben Stream zwischen place und gen. Nur die Spaltenzahl kommt zum Host
 * (12 Bytes), damit die Plaene in Stapeln laufen koennen.
 *
 * <p>Zwei eigene 2D-Plaene: Hoehe (17 x 17 Punkte im Abstand 8, eine Spalte deckt 128 x 128 Bloecke) und Klima (12 x 12 Quarts je
 * Kandidat auf Hoehe 64, R-Baum-Suche wie die Biom-Pipeline). Kernel: wg_struct_envfill.cu.
 */
public final class StructureEnv implements StructureGpu.EnvProvider, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/structures");
    public static final int SUB = 128, PRE_SIDE = 17, QUARTS = 144, TMP_WORDS = 17, CLIMATE_Y = 64;

    /** Ein 2D-Plan mit eigenen Puffern; Spalten laufen in Stapeln der Kapazitaet {@link #cap}. */
    private final class Stage {
        final ExactPlan plan;
        final int cap;
        final long[] bColumns, bCoord, bScratch, bTmp, bBp, bBiomeOut;
        final GpuCompute.Kernel coords, task, taskPt, biome;
        final List<GpuCompute.Kernel> groups;
        final int bioQuarts;

        Stage(ExactPlan plan, long budget, boolean climate, ExactBiomes biomes) throws Exception {
            this(plan, budget, QUARTS, climate, biomes);
        }

        Stage(ExactPlan plan, long budget, int quarts, boolean climate, ExactBiomes biomes) throws Exception {
            if (!plan.unsupported.isEmpty()) throw new IllegalStateException("nicht unterstuetzt: " + plan.unsupported);
            this.plan = plan;
            this.bioQuarts = quarts;
            long per = Math.max(4L, plan.scratchFloats * 4L) + Math.max(12L, plan.coordEntries * 12L) + (climate ? (long) quarts * TMP_WORDS * 4 : 0) + 8;
            cap = (int) Math.max(1, Math.min(4096, budget / per));
            long[] t2 = gpu.upload(plan.encodeTasks()), p2 = gpu.upload(plan.params.toIntArray());
            long[] l2 = gpu.upload(plan.encodeLayers()), st2 = gpu.upload(plan.encodeStacks()), sg2 = gpu.upload(plan.encodeSegs());
            bColumns = gpu.deviceBuffer(cap * 8L);
            bCoord = gpu.deviceBuffer(Math.max(16L, (long) cap * plan.coordEntries * 12L));
            bScratch = gpu.deviceBuffer((long) cap * plan.scratchFloats * 4L);
            long[] bCode = gpu.upload(plan.points.encodeCode()), bPParam = gpu.upload(plan.points.params.toIntArray());
            long[][] base = {t2, p2, l2, st2, sg2, bColumns, bCoord, bScratch, bCode, bPParam, bZero};
            coords = gpu.kernel(src, "wg_exact_coords", 10, 64);
            task = gpu.kernel(src, "wg_exact_task", 11, 128);
            taskPt = gpu.kernel(src, "wg_exact_task_pt", 11, 128);
            coords.bind(Arrays.copyOf(base, 10));
            task.bind(base);
            taskPt.bind(base);
            // Interpreter statt NVRTC-Spezialisierung: die Umgebung ist klein, der Aufbau darf den LOD-Generator nicht Sekunden blockieren
            groups = Boolean.getBoolean("pyridium.structCodegen") ? new ArrayList<>(gpu.groupKernels(plan, true, 11)) : new ArrayList<>(java.util.Collections.nCopies(plan.groups.size(), (GpuCompute.Kernel) null));
            for (GpuCompute.Kernel k : groups) if (k != null) k.bind(base);
            if (climate) {
                int[] bp = new int[9];
                bp[0] = biomes.nodeCount;
                bp[1] = quarts;
                bp[2] = 1;
                for (int i = 0; i < 6; i++) bp[3 + i] = plan.tasks.get(plan.roots.getInt(i)).offset;
                bBp = gpu.upload(bp);
                bTmp = gpu.deviceBuffer((long) cap * quarts * TMP_WORDS * 4L);
                bBiomeOut = gpu.deviceBuffer(64);
                biome = gpu.kernel(src, "wg_exact_biome", 12, 64);
                biome.bind(t2, p2, l2, st2, sg2, bColumns, bCoord, bScratch, bBp, bNode, bTmp, bBiomeOut);
            } else {
                bBp = bTmp = bBiomeOut = null;
                biome = null;
            }
        }

        /** Spalten [0, n) aus bColumns berechnen (Scratch bleibt bis zum naechsten Lauf) */
        void run(int n) {
            List<GpuCompute.Dispatch> list = new ArrayList<>();
            int[] push = {0, n, plan.scratchFloats, plan.coordEntries, plan.coordSegs.size(), 0, 0, 0};
            if (plan.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords, push.clone(), (plan.coordEntries + 63) / 64, n));
            GpuCompute.addGroups(list, plan, groups, task, taskPt, push, n);
            if (biome != null) list.add(new GpuCompute.Dispatch(biome, push.clone(), (bioQuarts + 63) / 64, n));
            gpu.runAsync(list, List.of());
        }
    }

    private final CudaCompute gpu;
    private final RandomState rsRef;
    private final net.minecraft.world.level.levelgen.NoiseRouter routerFns;
    private final ExactBiomes biomesRef;
    private final GpuCompute.BinarySource src;
    private final long[] bZero, bNode;
    private final Stage heights, climate;
    private final GpuCompute.Kernel kCols, kFillH, kFillC;
    private final long[] bColH, bColC, bCnt, rbCnt, rbCands, rbEnv;
    private final int capColH, capColC;
    /** Kacheln/Kandidaten reichten nicht: der letzte Lauf wurde verworfen (Aufrufer teilt die Region) */
    public boolean overflow;
    public int lastEnvInts, lastCandidates, lastTiles, lastColumnsH;
    public double lastMs, lastPlaceMs, lastHMs, lastCMs;
    private static final boolean PROFILE = Boolean.getBoolean("pyridium.structProfile");

    /**
     * @param sg Strukturgenerator des Moduls wg_struct_envfill (Bindungen 0..12 werden geteilt)
     * @param budget Arbeitsspeicher je Plan in Bytes (Spalten-Stapel richten sich danach)
     */
    public StructureEnv(StructureGpu sg, GpuCompute.BinarySource src, RandomState rs, NoiseGeneratorSettings settings, ExactBiomes biomes, long budget) throws Exception {
        this.gpu = sg.gpu();
        this.src = src;
        this.rsRef = rs;
        this.routerFns = settings.noiseRouter();
        this.biomesRef = biomes;
        bZero = gpu.deviceBuffer(1 << 16);
        bNode = gpu.upload(biomes.nodes);
        List<DensitySampler> roots = new ArrayList<>();
        roots.add(rs.getSampler(rs.surfaceSystem().preliminarySurfaceFunction()));
        heights = new Stage(ExactPlan.forLod(roots, List.of(new ExactPlan.Vol(PRE_SIDE, 1, PRE_SIDE, 0, 0, 0, 8, 1, 8, 0))), budget, false, biomes);
        var router = settings.noiseRouter();
        List<DensitySampler> cl = new ArrayList<>();
        List<ExactPlan.Vol> vols = new ArrayList<>();
        for (var f : List.of(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges())) {
            cl.add(rs.getSampler(f));
            vols.add(new ExactPlan.Vol(12, 1, 12, 0, CLIMATE_Y, 0, 4, 1, 4, 0));
        }
        climate = new Stage(ExactPlan.forLod(cl, vols), budget, true, biomes);
        // Spaltenlisten: Kacheln je Kandidat, hoechstens (hw / SUB aufgerundet)^2 Hoehenspalten
        int maxNs = 1;
        for (int s = 0; s < sg.tables.numSets(); s++) {
            int b = s * StructureTables.SET_W;
            if (sg.tables.sets.getInt(b + StructureTables.SET_ENV_INTS) <= 0) continue;
            int hw = 2 * sg.tables.sets.getInt(b + StructureTables.SET_ENV_R) + 16;
            maxNs = Math.max(maxNs, (hw + SUB - 1) / SUB);
        }
        capColH = sg.capCand * maxNs * maxNs;
        capColC = sg.capCand;
        bColH = gpu.deviceBuffer(capColH * 8L);
        bColC = gpu.deviceBuffer(capColC * 8L);
        bCnt = gpu.deviceBuffer(64);
        rbCnt = gpu.readbackBuffer(64);
        rbCands = gpu.readbackBuffer(16);
        rbEnv = gpu.readbackBuffer(16);
        GpuCompute.BinarySource mapped = n -> src.load("wg_struct_envfill.ptx");
        long[][] b = Arrays.copyOf(sg.bindings(), 19);
        b[13] = bColH;
        b[14] = bColC;
        b[15] = bCnt;
        b[16] = heights.bScratch;
        b[17] = climate.bTmp;
        b[18] = bNode;
        kCols = gpu.kernel(mapped, "wg_struct_env_cols", 19, 64);
        kFillH = gpu.kernel(mapped, "wg_struct_env_fill_h", 19, 256);
        kFillC = gpu.kernel(mapped, "wg_struct_env_fill_c", 19, 256);
        for (GpuCompute.Kernel k : new GpuCompute.Kernel[]{kCols, kFillH, kFillC}) k.bind(b);
        gridBindings = b;
        LOG.info("Strukturumgebung: Hoehenplan {} Spalten/Stapel ({} floats), Klimaplan {} Spalten/Stapel ({} floats), max. {} Hoehenspalten",
                heights.cap, heights.plan.scratchFloats, climate.cap, climate.plan.scratchFloats, capColH);
    }

    @Override
    public void fillTiles(StructureGpu sg) {
        long t0 = System.nanoTime();
        overflow = false;
        gpu.zeroAsync(bCnt[0], 64);
        int[] push = {0, 0, 0, 0, sg.capCand, 0, 0, 0};
        int[] pc = {0, 0, 0, 0, capColH, capColC, sg.capCand, 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(kCols, pc, (sg.capCand + 63) / 64, 1)), List.of());
        // Kandidatenzahl und Kachel-Belegung: ein kurzes Warten (Spaltenzahlen bestimmen die Plan-Aufrufe)
        gpu.downloadAsync(rbCnt[1], bCnt[0], 16);
        gpu.downloadAsync(rbCands[1], sg.candidateBuffer()[0], 4);
        gpu.downloadAsync(rbEnv[1], sg.envBuffer()[0], 12);
        gpu.sync();
        lastPlaceMs = (System.nanoTime() - t0) / 1e6;
        MemorySegment cnt = MemorySegment.ofAddress(rbCnt[1]).reinterpret(16);
        int nH = cnt.get(ValueLayout.JAVA_INT, 0), nC = cnt.get(ValueLayout.JAVA_INT, 4), ov = cnt.get(ValueLayout.JAVA_INT, 8);
        int cands = MemorySegment.ofAddress(rbCands[1]).reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        int envOv = MemorySegment.ofAddress(rbEnv[1]).reinterpret(12).get(ValueLayout.JAVA_INT, 8);
        lastCandidates = cands;
        lastEnvInts = MemorySegment.ofAddress(rbEnv[1]).reinterpret(12).get(ValueLayout.JAVA_INT, 0);
        lastTiles = nC;
        lastColumnsH = nH;
        if (ov != 0 || envOv != 0 || cands > sg.capCand) {
            // Kandidaten/Kacheln verworfen: keine Starts erzeugen (Aufrufer teilt die Region)
            overflow = true;
            gpu.zeroAsync(sg.candidateBuffer()[0], 4);
            return;
        }
        for (int c0 = 0; c0 < nH; c0 += heights.cap) {
            int n = Math.min(heights.cap, nH - c0);
            gpu.copyAsync(heights.bColumns[0], bColH[0] + c0 * 8L, n * 8L);
            heights.run(n);
            int preOff = heights.plan.tasks.get(heights.plan.roots.getInt(0)).offset;
            int[] fp = {c0, n, heights.plan.scratchFloats, preOff, capColH, capColC, sg.capCand, 0};
            gpu.runAsync(List.of(new GpuCompute.Dispatch(kFillH, fp, Math.min(sg.capCand, 4096), 1)), List.of());
        }
        long tH = System.nanoTime();
        if (PROFILE) { gpu.sync(); tH = System.nanoTime(); lastHMs = (tH - t0) / 1e6 - lastPlaceMs; }
        for (int c0 = 0; c0 < nC; c0 += climate.cap) {
            int n = Math.min(climate.cap, nC - c0);
            gpu.copyAsync(climate.bColumns[0], bColC[0] + c0 * 8L, n * 8L);
            climate.run(n);
            int[] fp = {c0, n, climate.plan.scratchFloats, 0, capColH, capColC, sg.capCand, 0};
            gpu.runAsync(List.of(new GpuCompute.Dispatch(kFillC, fp, Math.min(sg.capCand, 4096), 1)), List.of());
        }
        if (PROFILE) { gpu.sync(); lastCMs = (System.nanoTime() - tH) / 1e6; }
        lastMs = (System.nanoTime() - t0) / 1e6;
    }

    private GpuCompute.Kernel kGrid;
    private long[] bGrid, hostCols, rbGrid;
    private int gridCols;

    /**
     * Hoehenraster der vorlaeufigen Oberflaeche (Abstand 8 Bloecke) ueber ein Quadrat ab (x0, z0) mit cols x cols Hoehenspalten (je 128 Bloecke):
     * int16[(cols * 16)^2], Zeile = z. Gleiche Funktion wie das LOD-Gelaende. Wartet (eigener Stream, nicht der LOD-Stream).
     */
    public short[] heightGrid(int x0, int z0, int cols) {
        int n = cols * cols;
        if (n > heights.cap) throw new IllegalArgumentException("zu viele Spalten " + n + " > " + heights.cap);
        if (kGrid == null || gridCols != cols) {
            try {
                if (kGrid == null) {
                    GpuCompute.BinarySource mapped = nm -> src.load("wg_struct_envfill.ptx");
                    long[][] b = Arrays.copyOf(gridBindings, 19);
                    kGrid = gpu.kernel(mapped, "wg_struct_env_grid", 19, 256);
                    gridBase = b;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            gridCols = cols;
            long bytes = (long) cols * 16 * cols * 16 * 2;
            bGrid = gpu.deviceBuffer(bytes);
            rbGrid = gpu.readbackBuffer(bytes);
            hostCols = gpu.buffer(heights.cap * 8L);
            long[][] b = gridBase.clone();
            b[13] = bGrid;
            kGrid.bind(b);
        }
        MemorySegment h = MemorySegment.ofAddress(hostCols[1]).reinterpret(n * 8L);
        for (int j = 0; j < cols; j++)
            for (int i = 0; i < cols; i++) {
                h.set(ValueLayout.JAVA_INT, ((long) j * cols + i) * 8, x0 + i * SUB);
                h.set(ValueLayout.JAVA_INT, ((long) j * cols + i) * 8 + 4, z0 + j * SUB);
            }
        gpu.uploadAsync(heights.bColumns[0], hostCols[1], n * 8L);
        heights.run(n);
        int preOff = heights.plan.tasks.get(heights.plan.roots.getInt(0)).offset;
        int[] fp = {0, n, heights.plan.scratchFloats, preOff, cols, 0, 0, 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(kGrid, fp, (n * 256 + 255) / 256, 1)), List.of());
        long bytes = (long) cols * 16 * cols * 16 * 2;
        gpu.downloadAsync(rbGrid[1], bGrid[0], bytes);
        gpu.sync();
        short[] out = new short[cols * 16 * cols * 16];
        MemorySegment.copy(MemorySegment.ofAddress(rbGrid[1]).reinterpret(bytes), ValueLayout.JAVA_SHORT, 0, out, 0, out.length);
        return out;
    }

    private long[][] gridBindings, gridBase;
    private GpuCompute.Kernel kBio;
    private long[] bBio, rbBio, hostColsC;
    private int bioN;

    /**
     * Biomindex je 48-Block-Zelle (Mitte) fuer ein Quadrat ab (x0, z0) mit cols x cols Zellen (Zeile = z), Index wie die GPU-Biomsuche
     * (255 = ungueltig). Fuer den Tint der Strukturschicht. Wartet (eigener Stream).
     */
    public byte[] biomeGrid(int x0, int z0, int cols) {
        int n = cols * cols;
        try {
            if (kBio == null) {
                GpuCompute.BinarySource mapped = nm -> src.load("wg_struct_envfill.ptx");
                kBio = gpu.kernel(mapped, "wg_struct_env_bgrid", 19, 256);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (bBio == null || bioN < n) {
            bioN = n;
            bBio = gpu.deviceBuffer(Math.max(16, n));
            rbBio = gpu.readbackBuffer(Math.max(16, n));
            hostColsC = gpu.buffer(climate.cap * 8L);
            long[][] b = Arrays.copyOf(gridBindings, 19);
            b[13] = bBio;
            kBio.bind(b);
        }
        MemorySegment h = MemorySegment.ofAddress(hostColsC[1]).reinterpret(climate.cap * 8L);
        for (int c0 = 0; c0 < n; c0 += climate.cap) {
            int m = Math.min(climate.cap, n - c0);
            for (int k = 0; k < m; k++) {
                int idx = c0 + k;
                h.set(ValueLayout.JAVA_INT, k * 8L, x0 + (idx % cols) * 48);
                h.set(ValueLayout.JAVA_INT, k * 8L + 4, z0 + (idx / cols) * 48);
            }
            gpu.uploadAsync(climate.bColumns[0], hostColsC[1], m * 8L);
            climate.run(m);
            int[] fp = {c0, m, 0, 0, 0, 0, 0, 0};
            gpu.runAsync(List.of(new GpuCompute.Dispatch(kBio, fp, (m + 255) / 256, 1)), List.of());
        }
        gpu.downloadAsync(rbBio[1], bBio[0], n);
        gpu.sync();
        byte[] out = new byte[n];
        MemorySegment.copy(MemorySegment.ofAddress(rbBio[1]).reinterpret(n), ValueLayout.JAVA_BYTE, 0, out, 0, n);
        return out;
    }

    private Stage ringStage;
    private GpuCompute.Kernel kRingCols, kRingFill;
    private long[] bRingCols;

    /** Ring-Ebenen (Strongholds): Biome bei y = 0 um jede Ausgangsposition, ganz auf der GPU (eigener Klimaplan 57 x 57 Quarts je Ring) */
    @Override
    public void fillRingPlanes(StructureGpu sg) {
        int rings = sg.tables.ringPos.size() / 2;
        if (rings <= 0) return;
        try {
            if (ringStage == null) {
                List<DensitySampler> cl = new ArrayList<>();
                List<ExactPlan.Vol> vols = new ArrayList<>();
                for (var f : List.of(routerFns.temperature(), routerFns.vegetation(), routerFns.continents(), routerFns.erosion(), routerFns.depth(), routerFns.ridges())) {
                    cl.add(rsRef.getSampler(f));
                    vols.add(new ExactPlan.Vol(57, 1, 57, 0, 0, 0, 4, 1, 4, 0));
                }
                ringStage = new Stage(ExactPlan.forLod(cl, vols), 40L << 20, 3249, true, biomesRef);
                GpuCompute.BinarySource mapped = nm -> src.load("wg_struct_envfill.ptx");
                kRingCols = gpu.kernel(mapped, "wg_struct_ring_cols", 19, 64);
                kRingFill = gpu.kernel(mapped, "wg_struct_ring_fill", 19, 256);
                bRingCols = gpu.deviceBuffer(rings * 8L);
                long[][] b = Arrays.copyOf(gridBindings, 19);
                b[13] = bRingCols;
                b[17] = ringStage.bTmp;
                kRingCols.bind(b);
                kRingFill.bind(b);
            }
            int[] cp = {0, rings, 0, 0, 0, 0, 0, 0};
            gpu.runAsync(List.of(new GpuCompute.Dispatch(kRingCols, cp, (rings + 63) / 64, 1)), List.of());
            for (int c0 = 0; c0 < rings; c0 += ringStage.cap) {
                int m = Math.min(ringStage.cap, rings - c0);
                gpu.copyAsync(ringStage.bColumns[0], bRingCols[0] + c0 * 8L, m * 8L);
                ringStage.run(m);
                int[] fp = {c0, m, 0, 0, 0, 0, 0, 0};
                gpu.runAsync(List.of(new GpuCompute.Dispatch(kRingFill, fp, (int) (((long) m * 3249 + 255) / 256), 1)), List.of());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        kCols.close();
        kFillH.close();
        kFillC.close();
    }
}
