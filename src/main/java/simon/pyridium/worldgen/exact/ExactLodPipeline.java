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
    /** Nur Hoehenfeld moeglich (plan3d == null); sonst waehlt der Aufrufer je Lauf, siehe wg_lod_height.cu */
    public final boolean far;
    private final GpuCompute gpu;
    private long[] bZero, bLut, bSurfTable, bOreTable, bStruct;
    private final ExactLodSurface surf;
    private final long[] bColumns, bBand, bPreCopy, bCand, bSlots, bSlotY, bLp, bJobs, bOutVox, bOutCounts, bBiome, bTmp, bScratch2d, bScratch3d, bNode;
    private final GpuCompute.Kernel coords2d, task2d, taskPt2d, biomePipe, coords3d, task3d, voxPipe, selPipe, bandPipe, heightPipe, surfPipe;
    private final List<GpuCompute.Kernel> groups2d = new ArrayList<>(), groups3d = new ArrayList<>();
    private final int[] lp = new int[20];
    private final int preOffset;
    public double lastMs;

    /** Arbeitspuffer, die sich alle Stufen teilen (sie laufen nacheinander). */
    public static final class Shared {
        public final long[] scratch, coord, biomeOut, zero, lut;
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
            // Materialtabelle (wg_lod_surface): 4096 Auftraege x 1024 Spalten x 4 Materialien; Stufen laufen nacheinander
            lut = gpu.deviceBuffer(4096L * 1024 * 4 * 4);
        }
    }

    /** Chunks je Lauf: das Hoehenfeld braucht keinen 3D-Scratch */
    public int capacityFor(boolean heightfield) {
        return heightfield ? 4096 : chunkCapacity;
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
                            Shared sh, int[] lpBase, GpuCompute.BinarySource src, boolean codegen,
                            ExactLodSurface surf, int[] surfTable, int[] oreTable, boolean carve, long[] structView, int structMaxStep) throws Exception {
        if (!plan2d.unsupported.isEmpty() || (plan3d != null && !plan3d.unsupported.isEmpty()))
            throw new IllegalArgumentException("nicht unterstuetzt: " + plan2d.unsupported + (plan3d == null ? "" : plan3d.unsupported));
        this.far = plan3d == null;
        this.surf = surf;
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
        this.chunkCapacity = far ? 4096 : (int) Math.max(1, Math.min(4096, Math.min(half / Math.max(4L, plan3d.scratchFloats * 4L),
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
        bZero = sh.zero;
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
        // --- 3D: finalDensity je Chunk im Band, relative y (gChunkY = Bindung 20)
        bSlots = gpu.deviceBuffer(4096 * 8L);
        bSlotY = gpu.deviceBuffer(4096 * 4L);
        bCand = gpu.buffer(4096 * 16L);
        if (far) {
            coords3d = null;
            task3d = null;
        } else {
            int[] tasks3 = plan3d.encodeTasks();
            long[] t3 = gpu.upload(tasks3), p3 = gpu.upload(plan3d.params.toIntArray());
            long[] l3 = gpu.upload(plan3d.encodeLayers()), st3 = gpu.upload(plan3d.encodeStacks()), sg3 = gpu.upload(plan3d.encodeSegs());
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
        }
        // --- Voxel
        bLp = gpu.buffer(20 * 4L);
        bJobs = gpu.deviceBuffer(4096 * 16L);
        bOutVox = new long[]{0, 0, 0};
        bOutCounts = new long[]{0, 0, 0};
        voxPipe = gpu.kernel(src, "wg_lod_voxels", 16, 128);
        selPipe = gpu.kernel(src, "wg_lod_select", 8, 1024);
        bandPipe = gpu.kernel(src, "wg_lod_band", 4, 64);
        bBand = gpu.deviceBuffer(4096 * 8L);
        bPreCopy = gpu.deviceBuffer((long) sh.maxColumns * PRE * 4L);
        heightPipe = gpu.kernel(src, "wg_lod_height", 12, 128);
        bLut = sh.lut;
        bOreTable = gpu.upload(oreTable == null ? new int[]{0, 1} : oreTable);
        // Schluchten/Hoehlen und Erze nur in der Naehe (Voxel bis 2 Bloecke): Hoehlen sind weiter weg nicht sichtbar
        int nearFlags = step <= 2 ? ((carve ? 2 : 0) | (oreTable != null && oreTable[0] > 0 ? 4 : 0)) : 0;
        lp[19] = lpBase.length > 9 ? lpBase[9] : 0;
        lp[18] = nearFlags;
        // Strukturen (wg_struct_adapt.cuh): Bit 3, nur bis zum Voxelschritt, bei dem sie noch sichtbar sind (LodStructures.maxStep)
        boolean structs = structView != null && step <= structMaxStep;
        bStruct = structs ? structView : bZero;
        if (structs) {
            lp[18] |= 8;
        }
        if (surf != null) {
            surfPipe = gpu.kernel(src, "wg_lod_surface", 24, 64);
            bSurfTable = gpu.upload(surfTable);
            lp[18] |= 1;
        } else {
            surfPipe = null;
        }
        lp[0] = far ? 0 : plan3d.scratchFloats;
        lp[1] = far ? 0 : plan3d.tasks.get(plan3d.root).offset;
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
        // Band je Spalte aus dem 2D-Scratch, bevor die naechste Stufe ihn ueberschreibt
        bandPipe.bind(bScratch2d, bLp, bBand, bPreCopy);
        list.add(new GpuCompute.Dispatch(bandPipe, new int[]{n, BAND_BELOW, BAND_ABOVE, 0, 0, 0, 0, 0}, (n + 63) / 64, 1));
        ExactTerrainPipeline.ints(bLp[1], 20).put(0, lp, 0, 20);
        cc.runAsync(list, List.of(new long[]{bColumns[0], h, n * 8L}, new long[]{bLp[0], bLp[1], 80L}));
        if (PROFILE && profiled2d++ < 2) {
            cc.sync();
            double[] t = cc.runTimed(list);
            org.slf4j.LoggerFactory.getLogger("Pyridium").info("LOD-Profil L{} 2D ({} Spalten): {}", lod, n, fmt(t, list, plan2d));
        }
    }

    /**
     * Schritt 2+3: Kandidaten (je 4 ints: Spalte, key.y, Pyrit-Index, 0). Die Bandauswahl laeuft auf der
     * GPU (wg_lod_select): abgewaehlte Chunks werden in allen Kernen uebersprungen, ihre Zahl bleibt, wie
     * Pyrit sie vorbelegt hat. Spaltendaten aus dem letzten {@link #columns}. Synchron.
     */
    public void chunks(int jobs, int[] jobData, long pyritVoxels, long pyritCounts, int pyritCapacity, boolean heightfield) {
        chunksAsync(jobs, jobData, pyritVoxels, pyritCounts, pyritCapacity, heightfield);
        ((CudaCompute) gpu).sync();
    }

    /** wie {@link #chunks}, ohne zu warten; die Host-Puffer dieser Stufe erst nach sync wieder benutzen */
    public void chunksAsync(int jobs, int[] jobData, long pyritVoxels, long pyritCounts, int pyritCapacity, boolean heightfield) {
        if (jobs <= 0) return;
        if (jobs > capacityFor(far || heightfield)) throw new IllegalArgumentException("Chunks " + jobs + " / " + chunkCapacity);
        CudaCompute cc = (CudaCompute) gpu;
        ExactTerrainPipeline.ints(bCand[1], jobs * 4).put(0, jobData, 0, jobs * 4);
        lp[7] = pyritCapacity;
        ExactTerrainPipeline.ints(bLp[1], 20).put(0, lp, 0, 20);
        bOutVox[0] = pyritVoxels;
        bOutCounts[0] = pyritCounts;
        if (far || heightfield) {
            // Hoehenfeld: kein 3D-Schritt, kein Band; der Kernel entscheidet je Spalte selbst
            heightPipe.bind(bZero, bLp, bCand, bOutVox, bOutCounts, bBiome, bTmp, bNode, bColumns, bPreCopy, bLut, bStruct);
            List<GpuCompute.Dispatch> hl = new ArrayList<>();
            if (surfPipe != null) {
                bindSurface(bCand, true);
                hl.add(new GpuCompute.Dispatch(surfPipe, new int[]{1, jobs, 0, 0, 0, 0, 0, 0}, 16, jobs));
            }
            hl.add(new GpuCompute.Dispatch(heightPipe, new int[]{jobs, 0, 0, 0, 0, 0, 0, 0}, 8, jobs));
            cc.runAsync(hl,
                List.of(new long[]{bCand[0], bCand[1], jobs * 16L}, new long[]{bLp[0], bLp[1], 80L}));
            lastMs = 0;
            return;
        }
        selPipe.bind(bCand, bLp, bJobs, bSlots, bSlotY, bColumns, bBand);
        voxPipe.bind(bScratch3d, bLp, bJobs, bOutVox, bOutCounts, bBiome, bTmp, bNode, bSlots, bSlotY, bColumns, bLut, bSurfTable == null ? bZero : bSurfTable, bOreTable, bStruct, bPreCopy);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        list.add(new GpuCompute.Dispatch(selPipe, new int[]{jobs, BAND_BELOW, BAND_ABOVE, 0, 0, 0, 0, 0}, 1, 1));
        // pad1 = 1: die Aufgaben-/Koordinatenkerne ueberspringen Chunks mit SlotY == INT_MIN
        int[] push = {0, jobs, plan3d.scratchFloats, plan3d.coordEntries, plan3d.coordSegs.size(), 0, 0, 1};
        if (plan3d.coordEntries > 0) list.add(new GpuCompute.Dispatch(coords3d, push.clone(), (plan3d.coordEntries + 63) / 64, jobs));
        GpuCompute.addGroups(list, plan3d, groups3d, task3d, null, push, jobs);
        if (surfPipe != null) {
            bindSurface(bJobs, false);
            list.add(new GpuCompute.Dispatch(surfPipe, new int[]{0, jobs, 0, 0, 0, 0, 0, 0}, 16, jobs));
        }
        int[] vp = new int[8];
        vp[0] = jobs;
        list.add(new GpuCompute.Dispatch(voxPipe, vp, (32 * 32) / 128, jobs));
        long t0 = System.nanoTime();
        List<long[]> up = List.of(new long[]{bCand[0], bCand[1], jobs * 16L}, new long[]{bLp[0], bLp[1], 80L});
        if (PROFILE && profiled3d++ < 2) {
            cc.runAsync(List.of(), up);
            cc.sync();
            List<GpuCompute.Dispatch> dry = list.subList(0, list.size() - 1);
            double[] t = cc.runTimed(dry);
            org.slf4j.LoggerFactory.getLogger("Pyridium").info("LOD-Profil L{} 3D ({} Chunks): {}", lod, jobs, fmt(t, dry, plan3d));
        }
        cc.runAsync(list, up);
        lastMs = (System.nanoTime() - t0) / 1e6;
    }

    /** Bindungen von wg_lod_surface.cu: Plan 0..4, Auftraege 5, Oberflaeche 6, Dichte 7, Punkt 8/9, Tabelle 10, Kopf 11 ... */
    private void bindSurface(long[] jobs, boolean heightfield) {
        surfPipe.bind(surf.bTask, surf.bParam, surf.bLayer, surf.bStack, surf.bSeg, jobs, bPreCopy, bScratch3d, surf.bCode, surf.bPParam, surf.bSf,
            bLp, heightfield ? bColumns : bSlots, bLut, bTmp, bZero, bNode, bSurfTable, bZero, bZero, bSlotY, bZero, bZero, bZero);
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
