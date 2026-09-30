package simon.pyridium.worldgen.exact;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;

/**
 * Pyridium: exakte Vanilla-Dichte und Biome auf LOD-Stufen, ganz auf der GPU. Vorgehen wie Distant
 * Horizons – nur rechnen, was sichtbar sein kann:
 *
 * <ol>
 * <li>Spalten (2D, 34 x 34 Stuetzpunkte, Schritt s = 2^lod): Vanillas vorlaeufige Oberflaechenhoehe
 * und die sechs Klimafunktionen, daraus das Biom (R-Baum wie Vanilla).</li>
 * <li>Nur Chunks, die das Band um die Oberflaeche (oder den Meeresspiegel) schneiden, bekommen die
 * exakte finalDensity – und zwar nur in ihrem eigenen y-Bereich plus Rand (relative y-Volumen).</li>
 * <li>wg_lod_voxels.cu macht daraus die sichtbare Haut und schreibt sie in Pyrits Puffer.</li>
 * </ol>
 *
 * <p>Dichte und Biom sind an den Stuetzpunkten bitgleich zu Vanilla; Oberflaechenmaterial, Wasser
 * und Baeume sind Naeherungen je Biom. Nicht threadsicher.
 */
public final class ExactLodPipeline implements AutoCloseable {
    public static final int SIDE = 34;
    public static final int QUARTS = SIDE * SIDE;
    public static final int PRE_SIDE = 9, PRE = PRE_SIDE * PRE_SIDE;
    /** Biom je 2 x 2 Stuetzspalten: 17 x 17 Suchen statt 34 x 34 */
    public static final int BIOME_SIDE = 17, BIOMES = BIOME_SIDE * BIOME_SIDE;
    /** Klima in dieser Hoehe (Block y) – Oberflaechenbiome der meisten Spalten. */
    public static final int CLIMATE_Y = 64;
    /** Band um die vorlaeufige Oberflaeche (Bloecke): darunter verdeckter Fels, darueber Luft. */
    public static final int BAND_BELOW = 24, BAND_ABOVE = 40;

    public final int lod, step, border, sy, minY, height, colCapacity, chunkCapacity;
    public final ExactPlan plan2d, plan3d;
    private final GpuCompute gpu;
    private final long[] bColumns, bSlots, bSlotY, bLp, bJobs, bOutVox, bOutCounts, bBiome, bTmp, bScratch2d, bScratch3d, bNode, bPre;
    private final GpuCompute.Kernel coords2d, task2d, taskPt2d, biomePipe, coords3d, task3d, voxPipe;
    private final List<GpuCompute.Kernel> groups2d = new ArrayList<>(), groups3d = new ArrayList<>();
    private final int[] lp = new int[20];
    private final int preOffset;
    public double lastMs;

    /** Arbeitspuffer, die sich alle Stufen teilen (sie laufen nacheinander). */
    public static final class Shared {
        public final long[] scratch, coord, biomeOut, zero;
        public final long scratchBytes, coordBytes;
        public final int maxColumns;

        public Shared(GpuCompute gpu, long scratchBytes, long coordBytes, int maxColumns) {
            this.scratchBytes = scratchBytes;
            this.coordBytes = coordBytes;
            this.maxColumns = maxColumns;
            scratch = gpu.deviceBuffer(scratchBytes);
            coord = gpu.deviceBuffer(coordBytes);
            biomeOut = gpu.deviceBuffer((long) maxColumns * BIOMES * 4L);
            zero = gpu.buffer(64 + 4096 * 16L);
        }
    }

    /** Rand in Stuetzpunkten: y-Ursprung muss Vielfaches der Zellhoehe (8) sein */
    public static int border(int lod) {
        int s = 1 << lod;
        return Math.max(8, s) / s;
    }

    public static ExactPlan plan2d(RandomState rs, NoiseGeneratorSettings settings, int lod) {
        int s = 1 << lod;
        var router = settings.noiseRouter();
        List<DensitySampler> roots = new ArrayList<>();
        List<ExactPlan.Vol> vols = new ArrayList<>();
        // vorlaeufige Oberflaeche (Punktsuche) nur grob: 9 x 9 Punkte im Abstand 4 s genuegen fuer das Band
        roots.add(rs.getSampler(rs.surfaceSystem().preliminarySurfaceFunction()));
        vols.add(new ExactPlan.Vol(PRE_SIDE, 1, PRE_SIDE, -s, 0, -s, 4 * s, 1, 4 * s, 0));
        for (var f : List.of(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(), router.ridges())) {
            roots.add(rs.getSampler(f));
            vols.add(new ExactPlan.Vol(BIOME_SIDE, 1, BIOME_SIDE, -s, CLIMATE_Y, -s, 2 * s, 1, 2 * s, 0));
        }
        return ExactPlan.forLod(roots, vols);
    }

    public static ExactPlan plan3d(RandomState rs, NoiseGeneratorSettings settings, int lod) {
        int s = 1 << lod;
        int sy = 32 + 2 * border(lod);
        return ExactPlan.forLod(List.of(rs.getSampler(settings.noiseRouter().finalDensity())),
            List.of(new ExactPlan.Vol(SIDE, sy, SIDE, -s, 0, -s, s, s, s, ExactPlan.Vol.REL_Y)));
    }

    public ExactLodPipeline(GpuCompute gpu, ExactPlan plan2d, ExactPlan plan3d, ExactBiomes biomes, int[] biomeTable, int lod, int minY, int height,
                            Shared sh, int[] lpBase, GpuCompute.BinarySource src, boolean codegen) throws Exception {
        if (!plan2d.unsupported.isEmpty() || !plan3d.unsupported.isEmpty())
            throw new IllegalArgumentException("nicht unterstuetzt: " + plan2d.unsupported + plan3d.unsupported);
        this.gpu = gpu;
        this.plan2d = plan2d;
        this.plan3d = plan3d;
        this.lod = lod;
        this.step = 1 << lod;
        this.border = border(lod);
        this.sy = 32 + 2 * border;
        this.minY = minY;
        this.height = height;
        // Scratch und Koordinaten je zur Haelfte: Spalten (2D) und Chunks (3D)
        long half = sh.scratchBytes / 2, chalf = sh.coordBytes / 2;
        this.colCapacity = (int) Math.max(1, Math.min(sh.maxColumns, Math.min(half / Math.max(4L, plan2d.scratchFloats * 4L),
            chalf / Math.max(12L, plan2d.coordEntries * 12L))));
        this.chunkCapacity = (int) Math.max(1, Math.min(4096, Math.min(half / Math.max(4L, plan3d.scratchFloats * 4L),
            chalf / Math.max(12L, plan3d.coordEntries * 12L))));
        // --- 2D: Oberflaeche + Klima je Spalte
        int[] tasks2 = plan2d.encodeTasks();
        long[] t2 = gpu.upload(tasks2), p2 = gpu.upload(plan2d.params.toIntArray());
        long[] l2 = gpu.upload(plan2d.encodeLayers()), st2 = gpu.upload(plan2d.encodeStacks()), sg2 = gpu.upload(plan2d.encodeSegs());
        bColumns = gpu.buffer(4096 * 8L);
        long[] coordA = {sh.coord[0], 0, chalf};
        long[] coordB = {sh.coord[0] + chalf, 0, chalf};
        bScratch2d = new long[]{sh.scratch[0], 0, half};
        bScratch3d = new long[]{sh.scratch[0] + half, 0, half};
        long[] bZero = sh.zero;
        int[] bp = new int[9];
        bp[0] = biomes.nodeCount;
        bp[1] = BIOMES;
        bp[2] = 1;
        for (int i = 0; i < 6; i++) bp[3 + i] = plan2d.tasks.get(plan2d.roots.getInt(1 + i)).offset;
        long[] bBp = gpu.upload(bp);
        bNode = gpu.upload(biomes.nodes);
        // Biom je Stuetzspalte bleibt bis zum 3D-Schritt liegen: je Stufe eigen (alle Stufen laufen am Stueck)
        bTmp = gpu.deviceBuffer((long) sh.maxColumns * BIOMES * 17 * 4L);
        bBiome = gpu.upload(biomeTable);
        preOffset = plan2d.tasks.get(plan2d.roots.getInt(0)).offset;
        coords2d = gpu.kernel(src, "wg_exact_coords", 10, 64);
        task2d = gpu.kernel(src, "wg_exact_task", 11, 128);
        biomePipe = gpu.kernel(src, "wg_exact_biome", 12, 64);
        // Punktprogramm (FINDTOP der vorlaeufigen Oberflaeche): Bindungen 8/9 wie ExactTerrainPipeline
        long[] bCode = gpu.upload(plan2d.points.encodeCode()), bPParam = gpu.upload(plan2d.points.params.toIntArray());
        long[][] base2 = {t2, p2, l2, st2, sg2, bColumns, coordA, bScratch2d, bCode, bPParam, bZero};
        taskPt2d = gpu.kernel(src, "wg_exact_task_pt", 11, 128);
        coords2d.bind(java.util.Arrays.copyOf(base2, 10));
        task2d.bind(base2);
        taskPt2d.bind(base2);
        biomePipe.bind(t2, p2, l2, st2, sg2, bColumns, coordA, bScratch2d, bBp, bNode, bTmp, sh.biomeOut);
        groups2d.addAll(codegen ? gpu.groupKernels(plan2d, true, 11)
                : java.util.Collections.nCopies(plan2d.groups.size(), null));
        for (GpuCompute.Kernel p : groups2d) {
            if (p != null) p.bind(base2);
        }
        // vorlaeufige Oberflaeche je Spalte (34 x 34 floats) zum Host
        bPre = gpu.readbackBuffer(4096L * PRE * 4L);
        // --- 3D: finalDensity je Chunk im Band, relative y (gChunkY = Bindung 20)
        int[] tasks3 = plan3d.encodeTasks();
        long[] t3 = gpu.upload(tasks3), p3 = gpu.upload(plan3d.params.toIntArray());
        long[] l3 = gpu.upload(plan3d.encodeLayers()), st3 = gpu.upload(plan3d.encodeStacks()), sg3 = gpu.upload(plan3d.encodeSegs());
        bSlots = gpu.buffer(4096 * 8L);
        bSlotY = gpu.buffer(4096 * 4L);
        coords3d = gpu.kernel(src, "wg_exact_coords", 21, 64);
        task3d = gpu.kernel(src, "wg_exact_task", 21, 128);
        long[][] b3 = {t3, p3, l3, st3, sg3, bSlots, coordB, bScratch3d, bZero, bZero, bZero};
        long[][] base3 = new long[21][];
        for (int i = 0; i < 21; i++) base3[i] = i < b3.length ? b3[i] : (i == 20 ? bSlotY : bZero);
        coords3d.bind(base3);
        task3d.bind(base3);
        groups3d.addAll(codegen ? gpu.groupKernels(plan3d, true, 21)
                : java.util.Collections.nCopies(plan3d.groups.size(), null));
        for (GpuCompute.Kernel p : groups3d) {
            if (p != null) p.bind(base3);
        }
        // --- Voxel
        bLp = gpu.buffer(20 * 4L);
        bJobs = gpu.buffer(4096 * 16L);
        bOutVox = new long[]{0, 0, 0};
        bOutCounts = new long[]{0, 0, 0};
        voxPipe = gpu.kernel(src, "wg_lod_voxels", 12, 128);
        lp[0] = plan3d.scratchFloats;
        lp[1] = plan3d.tasks.get(plan3d.root).offset;
        lp[2] = sy;
        lp[3] = step;
        lp[4] = minY;
        lp[5] = height;
        System.arraycopy(lpBase, 0, lp, 6, Math.min(lpBase.length, 9));
        lp[8] = BIOMES;
        lp[9] = biomeTable.length / 8;
        lp[15] = plan2d.scratchFloats;
        lp[16] = preOffset;
        lp[17] = border;
    }

    /**
     * Schritt 1 fuer n Spalten (Blockminimum x/z): Oberflaeche und Biom. Synchron; danach liefert
     * {@link #surfaceRange} Minimum/Maximum der vorlaeufigen Oberflaeche je Spalte.
     */
    public void columns(int n, int[] columnXZ) {
        columnsAsync(n, columnXZ);
        ((CudaCompute) gpu).sync();
    }

    /** wie {@link #columns}, ohne zu warten: {@link #surfaceRange} erst nach dem naechsten sync */
    public void columnsAsync(int n, int[] columnXZ) {
        if (n <= 0 || n > colCapacity) throw new IllegalArgumentException("Spalten " + n + " / " + colCapacity);
        CudaCompute cc = (CudaCompute) gpu;
        long h = bColumns[1];
        ExactTerrainPipeline.ints(h, n * 2).put(0, columnXZ, 0, n * 2);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        int[] push = {0, n, plan2d.scratchFloats, plan2d.coordEntries, plan2d.coordSegs.size(), 0, 0, 0};
        if (plan2d.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords2d, push.clone(), (plan2d.coordEntries + 63) / 64, n));
        GpuCompute.addGroups(list, plan2d, groups2d, task2d, taskPt2d, push, n);
        list.add(new GpuCompute.Dispatch(biomePipe, push.clone(), (BIOMES + 63) / 64, n));
        cc.runAsync(list, List.of(new long[]{bColumns[0], h, n * 8L}));
        if (PROFILE && profiled2d++ < 2) {
            cc.sync();
            double[] t = cc.runTimed(list);
            org.slf4j.LoggerFactory.getLogger("Pyridium").info("LOD-Profil L{} 2D ({} Spalten): {}", lod, n, fmt(t, list, plan2d));
        }
        cc.readStridedAsync(bPre[1], bScratch2d[0], plan2d.scratchFloats * 4L, preOffset * 4L, PRE * 4L, n);
    }

    /** vorlaeufige Oberflaeche (Minimum, Maximum) der Spalte c nach {@link #columns} */
    public int[] surfaceRange(int c) {
        java.nio.FloatBuffer f = ExactTerrainPipeline.mem(bPre[1] + (long) c * PRE * 4, PRE * 4L).asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < PRE; i++) {
            float v = f.get(i);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new int[]{(int) Math.floor(lo), (int) Math.ceil(hi)};
    }

    /**
     * Schritt 2+3: exakte Dichte fuer die Chunks (je 4 ints: Spalte, key.y, Pyrit-Index, 0) und die
     * Voxel direkt in Pyrits Puffer. Spaltendaten aus dem letzten {@link #columns}. Synchron.
     */
    public void chunks(int[] columnXZ, int jobs, int[] jobData, long pyritVoxels, long pyritCounts, int pyritCapacity) {
        chunksAsync(columnXZ, jobs, jobData, pyritVoxels, pyritCounts, pyritCapacity);
        ((CudaCompute) gpu).sync();
    }

    /** wie {@link #chunks}, ohne zu warten; die Host-Puffer dieser Stufe erst nach sync wieder benutzen */
    public void chunksAsync(int[] columnXZ, int jobs, int[] jobData, long pyritVoxels, long pyritCounts, int pyritCapacity) {
        if (jobs <= 0) return;
        if (jobs > chunkCapacity) throw new IllegalArgumentException("Chunks " + jobs + " / " + chunkCapacity);
        CudaCompute cc = (CudaCompute) gpu;
        int[] slots = new int[jobs * 2];
        int[] slotY = new int[jobs];
        for (int j = 0; j < jobs; j++) {
            int col = jobData[j * 4];
            slots[j * 2] = columnXZ[col * 2];
            slots[j * 2 + 1] = columnXZ[col * 2 + 1];
            slotY[j] = jobData[j * 4 + 1] * 32 * step - border * step;
        }
        ExactTerrainPipeline.ints(bSlots[1], jobs * 2).put(0, slots, 0, jobs * 2);
        ExactTerrainPipeline.ints(bSlotY[1], jobs).put(0, slotY, 0, jobs);
        ExactTerrainPipeline.ints(bJobs[1], jobs * 4).put(0, jobData, 0, jobs * 4);
        lp[7] = pyritCapacity;
        ExactTerrainPipeline.ints(bLp[1], 20).put(0, lp, 0, 20);
        bOutVox[0] = pyritVoxels;
        bOutCounts[0] = pyritCounts;
        voxPipe.bind(bScratch3d, bLp, bJobs, bOutVox, bOutCounts, bBiome, bTmp, bNode, bSlots, bSlotY, bColumns);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        int[] push = {0, jobs, plan3d.scratchFloats, plan3d.coordEntries, plan3d.coordSegs.size(), 0, 0, 0};
        if (plan3d.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords3d, push.clone(), (plan3d.coordEntries + 63) / 64, jobs));
        GpuCompute.addGroups(list, plan3d, groups3d, task3d, null, push, jobs);
        int[] vp = new int[8];
        vp[0] = jobs;
        list.add(new GpuCompute.Dispatch(voxPipe, vp, (32 * 32) / 128, jobs));
        long t0 = System.nanoTime();
        if (PROFILE && profiled3d++ < 2) {
            cc.runAsync(List.of(), List.of(new long[]{bSlots[0], bSlots[1], jobs * 8L}, new long[]{bSlotY[0], bSlotY[1], jobs * 4L},
                new long[]{bJobs[0], bJobs[1], jobs * 16L}, new long[]{bLp[0], bLp[1], 80L}));
            cc.sync();
            List<GpuCompute.Dispatch> dry = list.subList(0, list.size() - 1);
            double[] t = cc.runTimed(dry);
            org.slf4j.LoggerFactory.getLogger("Pyridium").info("LOD-Profil L{} 3D ({} Chunks): {}", lod, jobs, fmt(t, dry, plan3d));
        }
        cc.runAsync(list, List.of(new long[]{bSlots[0], bSlots[1], jobs * 8L}, new long[]{bSlotY[0], bSlotY[1], jobs * 4L},
            new long[]{bJobs[0], bJobs[1], jobs * 16L}, new long[]{bLp[0], bLp[1], 80L}));
        lastMs = (System.nanoTime() - t0) / 1e6;
    }

    private static final boolean PROFILE = Boolean.getBoolean("pyridium.lodProfile");
    private int profiled2d, profiled3d;

    private static String fmt(double[] t, List<GpuCompute.Dispatch> list, ExactPlan plan) {
        StringBuilder sb = new StringBuilder();
        double sum = 0;
        for (int i = 0; i < t.length; i++) {
            sum += t[i];
            sb.append(String.format(" %.2f", t[i]));
        }
        return String.format("%.2f ms =", sum) + sb;
    }

    @Override
    public void close() {
    }
}
