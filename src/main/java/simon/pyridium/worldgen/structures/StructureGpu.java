package simon.pyridium.worldgen.structures;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.GpuCompute;

/**
 * GPU-Besitzer der Strukturgenerierung: laedt die kompilierten Tabellen einmal hoch und fuehrt die
 * Kernel aus (alles in CUDA, keine CPU-Generierung):
 * <ul>
 * <li>{@code wg_struct_place}: Kandidaten (Set, Chunk) einer Region (random_spread / concentric_rings,
 * Frequenz, Exclusion Zones)</li>
 * <li>{@code wg_struct_gen}: je Kandidat Strukturauswahl, Biom-/Hoehenpruefung, Jigsaw-Loeser bzw. prozedurale
 * Teile; Ergebnis in die wachsende Arena (Starts + Teile)</li>
 * <li>{@code wg_struct_index}: neue Teile in den Raumindex (Hash-Zellen 64 x 64) eintragen</li>
 * </ul>
 * Die Arena waechst ueber mehrere {@link #generateAsync}-Aufrufe (disjunkte Regionen); {@link #reset} leert sie.
 * Abfragen laufen in beliebigen Kernen ueber {@link #viewBuffer()} und wg_struct_query.cuh.
 *
 * <p>Module: {@code wg_struct_tile} (Produktion: Umgebung aus Hoehen-/Biom-Kacheln) und
 * {@code wg_struct_synth} (Test: analytische Umgebung).
 */
public final class StructureGpu implements AutoCloseable {
    public static final int CAND_HDR = 4, OUT_HDR = 4, ENV_BASE = 4;
    /** wie STRUCT_SCRATCH_INTS in wg_struct_jigsaw.cuh */
    public static final int SCRATCH_INTS = 1024 * 24 + 512 * 4 + 512 * 8 + 1024 * 8 + 96 * 2 + 1536 * 2 + 256;
    public final StructureTables tables;
    private final CudaCompute gpu;
    private final long[] bView, bCands, bStarts, bPieces, bScratch, bStats, bKeys, bHeads, bNodes, bOut, bEnv, bEnvCur, bRingPlanes, bEnvHdr;
    private final long[] rbCands, rbStarts, rbPieces, rbStats;
    private final GpuCompute.Kernel place, gen, index, indexCommit, topview, slice, rings, ringsInit, ringsSearch;
    public final int envInts;
    private long[] bRingPos;
    public final int capCand, capStart, capPieces, slots, hashCap, capNodes;
    private final List<long[]> keep = new ArrayList<>();
    private final long[][] binds;
    public double lastPlaceMs, lastGenMs;
    private boolean uploadView = true;
    private long[] bAttr;

    /** Ergebnis einer Region */
    public static final class Result {
        public int candidates, starts, pieces, flags;
        public int[] candData = new int[0];
        public int[] startData = new int[0];
        public int[] pieceData = new int[0];

        public int[] start(int i) {
            return java.util.Arrays.copyOfRange(startData, i * StructureTables.START_W, (i + 1) * StructureTables.START_W);
        }

        public int[] piece(int i) {
            return java.util.Arrays.copyOfRange(pieceData, i * StructureTables.PIECE_W, (i + 1) * StructureTables.PIECE_W);
        }
    }

    /**
     * @param module PTX-Modul ({@code wg_struct_tile} Produktion, {@code wg_struct_synth} Test)
     * @param slots gleichzeitig arbeitende Jigsaw-Threads (je SCRATCH_INTS * 4 Byte Arbeitsspeicher)
     * @param hashCap Zellen des Raumindex (Zweierpotenz), Knoten = 2 x hashCap
     */
    /** Anbieter der Umgebungs-Kacheln (Hoehen, Biome): reiht GPU-Arbeit in den Stream ein (siehe docs/STRUCTURES.md) */
    public interface EnvProvider {
        /** Nach wg_struct_place: Kacheln aller Kandidaten befuellen (Format: wg_struct_env_tile.cuh) */
        void fillTiles(StructureGpu sg);

        /** Nach wg_struct_rings_init: Biom-Ebenen der Stronghold-Ringe befuellen */
        void fillRingPlanes(StructureGpu sg);
    }

    public StructureGpu(CudaCompute gpu, StructureTables t, GpuCompute.BinarySource src, String module, int capCand, int capStart, int capPieces, int slots, int hashCap)
            throws Exception {
        this(gpu, t, src, module, capCand, capStart, capPieces, slots, hashCap, 0);
    }

    /**
     * @param envInts Kapazitaet des Kachel-Puffers in ints (0: Modul ohne Kacheln, z. B. wg_struct_synth)
     */
    public StructureGpu(CudaCompute gpu, StructureTables t, GpuCompute.BinarySource src, String module, int capCand, int capStart, int capPieces, int slots, int hashCap,
                        int envInts) throws Exception {
        this.envInts = envInts;
        this.gpu = gpu;
        this.tables = t;
        this.capCand = capCand;
        this.capStart = capStart;
        this.capPieces = capPieces;
        this.slots = slots;
        this.hashCap = Integer.highestOneBit(Math.max(16, hashCap - 1) * 2);
        this.capNodes = 2 * this.hashCap;
        long[] sets = up(t.sets.toIntArray()), entries = up(t.entries.toIntArray()), structs = up(t.structs.toIntArray());
        long[] masks = up(t.biomeMasks.toIntArray()), pools = up(t.pools.toIntArray()), poolElems = up(t.poolElems.toIntArray());
        long[] elems = up(t.elems.toIntArray()), subElems = up(t.subElems.toIntArray()), templates = up(t.templates.toIntArray());
        long[] jigsaws = up(t.jigsaws.toIntArray()), palette = up(t.palette.toIntArray()), ringPos = up(t.ringPos.toIntArray());
        bRingPos = ringPos;
        long[] alias = up(t.aliasData.toIntArray()), mips = up(t.mips.toIntArray()), procData = up(t.procData.toIntArray());
        long[] procCode = up(t.procCode.toIntArray()), procOff = up(t.procOff.toIntArray()), stateBlock = up(t.stateBlock), tagBits = up(t.tagBits.toIntArray());
        // Voxel: je zwei ushort in einem int
        int nv = t.voxels.size();
        int[] packed = new int[(nv + 1) / 2];
        for (int i = 0; i < nv; i++) packed[i >> 1] |= (t.voxels.getShort(i) & 0xffff) << ((i & 1) * 16);
        long[] voxels = up(packed);
        bCands = gpu.deviceBuffer(CAND_HDR * 4L + capCand * 16L);
        bStarts = gpu.deviceBuffer(OUT_HDR * 4L + (long) capStart * StructureTables.START_W * 4);
        bPieces = gpu.deviceBuffer((long) capPieces * StructureTables.PIECE_W * 4);
        bScratch = gpu.deviceBuffer((long) slots * SCRATCH_INTS * 4L);
        bStats = gpu.deviceBuffer(256);
        bKeys = gpu.deviceBuffer(this.hashCap * 8L);
        bHeads = gpu.deviceBuffer(this.hashCap * 4L);
        bNodes = gpu.deviceBuffer(capNodes * 8L + 16);
        bOut = gpu.deviceBuffer(8L * 1024 * 1024);
        bEnv = gpu.deviceBuffer((ENV_BASE + Math.max(16, envInts)) * 4L);
        bEnvCur = gpu.deviceBuffer(Math.max(1, slots) * 4L);
        int ringTotal = t.ringPos.size() / 2;
        bRingPlanes = gpu.deviceBuffer(Math.max(16L, ringTotal * 3249L));
        bEnvHdr = gpu.buffer(16);
        MemorySegment.ofAddress(bEnvHdr[1]).reinterpret(16).set(ValueLayout.JAVA_INT, 4, Math.max(16, envInts));
        bView = gpu.buffer(56 * 8L);
        MemorySegment v = MemorySegment.ofAddress(bView[1]).reinterpret(bView[2]);
        long[] f = {sets[0], t.numSets(), entries[0], t.entries.size() / StructureTables.ENTRY_W, structs[0], t.numStructs(), masks[0], t.biomeWords,
                pools[0], t.numPools(), poolElems[0], elems[0], subElems[0], templates[0], t.numTemplates(), jigsaws[0], voxels[0], palette[0],
                ringPos[0], t.ringPos.size() / 2, alias[0], mips[0], 0, 0, t.seed, t.minY, t.height, t.seaLevel, t.dimension,
                t.emptyPool, t.emptyString, t.biomeUniverse.size(), procData[0], 0, 0,
                bStarts[0], bPieces[0], bKeys[0], bHeads[0], bNodes[0], this.hashCap - 1, 0,
                procCode[0], procOff[0], stateBlock[0], tagBits[0], t.tagWords};
        for (int i = 0; i < f.length; i++) v.set(ValueLayout.JAVA_LONG, i * 8L, f[i]);
        rbCands = gpu.readbackBuffer(bCands[2]);
        rbStarts = gpu.readbackBuffer(bStarts[2]);
        rbPieces = gpu.readbackBuffer(bPieces[2]);
        rbStats = gpu.readbackBuffer(256);
        binds = new long[][]{bView, bCands, bStarts, bPieces, bScratch, bStats, bKeys, bHeads, bNodes, bOut, bEnv, bEnvCur, bRingPlanes};
        GpuCompute.BinarySource mapped = name -> src.load(module + ".ptx");
        place = gpu.kernel(mapped, "wg_struct_place", 13, 64);
        gen = gpu.kernel(mapped, "wg_struct_gen", 13, 32);
        index = gpu.kernel(mapped, "wg_struct_index", 13, 64);
        indexCommit = gpu.kernel(mapped, "wg_struct_index_commit", 13, 1);
        topview = gpu.kernel(mapped, "wg_struct_topview", 13, 64);
        slice = gpu.kernel(mapped, "wg_struct_slice", 13, 64);
        rings = gpu.kernel(mapped, "wg_struct_rings", 13, 128);
        ringsInit = gpu.kernel(mapped, "wg_struct_rings_init", 13, 128);
        ringsSearch = gpu.kernel(mapped, "wg_struct_rings_search", 13, 128);
        for (GpuCompute.Kernel k : new GpuCompute.Kernel[]{place, gen, index, indexCommit, topview, slice, rings, ringsInit, ringsSearch}) k.bind(binds);
    }

    private long[] up(int[] a) {
        long[] b = gpu.upload(a);
        keep.add(b);
        return b;
    }

    /** Geraeteadresse der StructTables-Sicht: in fremden Kernen als eine Bindung (vf_b[N]) einhaengen. */
    public long[] viewBuffer() {
        return bView;
    }

    /** Blockzustands-Id -&gt; Pyrit-Attribut (z. B. BlockPalette.ATTR); optional, ohne Tabelle liefert struct_attr die Zustands-Id. */
    public void setAttrTable(int[] attr) {
        bAttr = up(attr);
        MemorySegment v = MemorySegment.ofAddress(bView[1]).reinterpret(bView[2]);
        v.set(ValueLayout.JAVA_LONG, 22 * 8L, bAttr[0]);
        v.set(ValueLayout.JAVA_LONG, 23 * 8L, attr.length);
        uploadView = true;
    }

    /**
     * Berechnet die Ringpositionen aller concentric_rings-Sets (Strongholds) auf der GPU; einmal je Welt, vor der ersten
     * Platzierung. Alles bleibt auf der GPU.
     */
    public void solveRingsAsync() {
        solveRingsAsync(null);
    }

    /** wie {@link #solveRingsAsync()}; mit Anbieter in zwei Teilen (Ausgangspositionen, Biom-Ebenen vom Anbieter, Suche) */
    public void solveRingsAsync(EnvProvider env) {
        int[] push = {0, 0, 0, 0, 0, 0, 0, 0};
        if (env == null) {
            gpu.runAsync(List.of(new GpuCompute.Dispatch(rings, push, tables.numSets(), 1)), viewUpload());
            return;
        }
        gpu.runAsync(List.of(new GpuCompute.Dispatch(ringsInit, push, tables.numSets(), 1)), viewUpload());
        env.fillRingPlanes(this);
        gpu.runAsync(List.of(new GpuCompute.Dispatch(ringsSearch, push, tables.numSets(), 1)), List.of());
    }

    /** Test: Ringpositionen zum Host (nach solveRingsAsync + sync) */
    public int[] downloadRingPositions() {
        long bytes = bRingPos[2];
        long[] rb = gpu.readbackBuffer(bytes);
        gpu.downloadAsync(rb[1], bRingPos[0], bytes);
        gpu.sync();
        int[] out = new int[(int) (bytes / 4)];
        MemorySegment.copy(MemorySegment.ofAddress(rb[1]).reinterpret(bytes), ValueLayout.JAVA_INT, 0, out, 0, out.length);
        return out;
    }

    public void sync() {
        gpu.sync();
    }

    /** Basistemperatur je Biom (Index = Biom-Universum), fuer Strukturen mit Kaelte-Varianten (Ruined Portal) */
    public void setBiomeTemperatures(float[] temp) {
        int[] bits = new int[temp.length];
        for (int i = 0; i < temp.length; i++) bits[i] = Float.floatToRawIntBits(temp[i]);
        long[] b = up(bits);
        MemorySegment.ofAddress(bView[1]).reinterpret(bView[2]).set(ValueLayout.JAVA_LONG, 41 * 8L, b[0]);
        uploadView = true;
    }

    /** Arena (Starts, Teile, Raumindex) leeren */
    public void reset() {
        gpu.zeroAsync(bStarts[0], OUT_HDR * 4L);
        gpu.zeroAsync(bKeys[0], hashCap * 8L);
        gpu.zeroAsync(bHeads[0], hashCap * 4L);
        gpu.zeroAsync(bNodes[0], 16);
        gpu.sync();
    }

    private List<long[]> viewUpload() {
        if (!uploadView) return List.of();
        uploadView = false;
        return List.of(new long[]{bView[0], bView[1], bView[2]});
    }

    /**
     * Platziert und erzeugt alle Strukturstarts, deren Start-Chunk in [cx0, cx0+cw) x [cz0, cz0+cd) liegt, haengt sie an
     * die Arena an und indiziert die neuen Teile. Ohne Readback (alles bleibt auf der GPU); wartet nicht.
     */
    public void generateAsync(int cx0, int cz0, int cw, int cd) {
        generateAsync(cx0, cz0, cw, cd, null);
    }

    /**
     * wie {@link #generateAsync(int, int, int, int)}; mit Anbieter laufen place, Kachel-Fuellung des Anbieters, gen und Index
     * nacheinander im selben Stream (Kacheln stehen nur fuer diesen Aufruf bereit und werden danach verworfen).
     */
    public void generateAsync(int cx0, int cz0, int cw, int cd, EnvProvider env) {
        gpu.zeroAsync(bCands[0], CAND_HDR * 4L);
        int maxCells = 1;
        for (int s = 0; s < tables.numSets(); s++) {
            int b = s * StructureTables.SET_W;
            if (tables.sets.getInt(b + StructureTables.SET_TYPE) == 0) {
                int sp = tables.sets.getInt(b + StructureTables.SET_SPACING);
                int ncx = Math.floorDiv(cx0 + cw - 1, sp) - Math.floorDiv(cx0, sp) + 1, ncz = Math.floorDiv(cz0 + cd - 1, sp) - Math.floorDiv(cz0, sp) + 1;
                maxCells = Math.max(maxCells, ncx * ncz);
            } else {
                maxCells = Math.max(maxCells, tables.sets.getInt(b + StructureTables.SET_RING_COUNT));
            }
        }
        int[] push = {cx0, cz0, cw, cd, capCand, capStart, capPieces, slots};
        // Indexkernel: Thread i bearbeitet Teil (Cursor + i), Cursor und Obergrenze liest der Kernel aus dem Arena-Kopf
        int[] indexPush = {0, 0, 0, 0, capNodes, hashCap - 1, capPieces, 0};
        List<long[]> up = new ArrayList<>(viewUpload());
        up.add(new long[]{bEnv[0], bEnvHdr[1], 16});        // Kachel-Puffer leeren: belegt 0, Kapazitaet, Ueberlauf 0
        gpu.runAsync(List.of(new GpuCompute.Dispatch(place, push, (maxCells + 63) / 64, tables.numSets())), up);
        if (env != null) env.fillTiles(this);
        gpu.runAsync(List.of(
                new GpuCompute.Dispatch(gen, push, slots, 1),
                new GpuCompute.Dispatch(index, indexPush, (capPieces + 63) / 64, 1),
                new GpuCompute.Dispatch(indexCommit, indexPush, 1, 1)), List.of());
    }

    /** Wie {@link #generateAsync}, wartet und misst (Test). */
    public void generateSync(int cx0, int cz0, int cw, int cd) {
        generateSync(cx0, cz0, cw, cd, null);
    }

    public void generateSync(int cx0, int cz0, int cw, int cd, EnvProvider env) {
        long t0 = System.nanoTime();
        generateAsync(cx0, cz0, cw, cd, env);
        gpu.sync();
        lastGenMs = (System.nanoTime() - t0) / 1e6;
    }

    /**
     * Fuellstand der Arena (wartet): {Starts, Teile, Ueberlauf-Flag (Starts/Teile), Index-Knoten, Knoten-Ueberlauf}. Kleine Abfrage
     * (20 Bytes) fuer die Verwaltung im Betrieb (LodStructures), nicht der volle {@link #download()}.
     */
    public int[] arenaState() {
        if (rbState == null) rbState = gpu.readbackBuffer(64);
        gpu.downloadAsync(rbState[1], bStarts[0], 16);
        gpu.downloadAsync(rbState[1] + 16, bNodes[0], 8);
        gpu.sync();
        MemorySegment m = MemorySegment.ofAddress(rbState[1]).reinterpret(32);
        return new int[]{m.get(ValueLayout.JAVA_INT, 0), m.get(ValueLayout.JAVA_INT, 4), m.get(ValueLayout.JAVA_INT, 8), m.get(ValueLayout.JAVA_INT, 16),
                m.get(ValueLayout.JAVA_INT, 20)};
    }

    private long[] rbState;

    /** Teile [from, to) der Arena zum Host (PIECE_W ints je Teil); wartet. Fuer die Strukturschicht des LOD (Instanzen aus der kleinen Teileliste). */
    public int[] downloadPieces(int from, int to) {
        int n = Math.min(to, capPieces) - from;
        if (n <= 0) return new int[0];
        long bytes = (long) n * StructureTables.PIECE_W * 4;
        if (rbPiecesPart == null || rbPiecesPart[2] < bytes) rbPiecesPart = gpu.readbackBuffer(Math.max(bytes, 1 << 20));
        gpu.downloadAsync(rbPiecesPart[1], bPieces[0] + (long) from * StructureTables.PIECE_W * 4, bytes);
        gpu.sync();
        int[] out = new int[n * StructureTables.PIECE_W];
        MemorySegment.copy(MemorySegment.ofAddress(rbPiecesPart[1]).reinterpret(bytes), ValueLayout.JAVA_INT, 0, out, 0, out.length);
        return out;
    }

    private long[] rbPiecesPart;

    /** Alle Ergebnisse der Arena zum Host (Test). */
    public Result download() {
        gpu.downloadAsync(rbCands[1], bCands[0], bCands[2]);
        gpu.downloadAsync(rbStarts[1], bStarts[0], bStarts[2]);
        gpu.sync();
        Result r = new Result();
        MemorySegment c = MemorySegment.ofAddress(rbCands[1]).reinterpret(rbCands[2]);
        MemorySegment s = MemorySegment.ofAddress(rbStarts[1]).reinterpret(rbStarts[2]);
        r.candidates = c.get(ValueLayout.JAVA_INT, 0);
        int nc = Math.min(r.candidates, capCand);
        r.candData = new int[nc * 4];
        MemorySegment.copy(c, ValueLayout.JAVA_INT, CAND_HDR * 4L, r.candData, 0, nc * 4);
        r.starts = s.get(ValueLayout.JAVA_INT, 0);
        r.pieces = s.get(ValueLayout.JAVA_INT, 4);
        r.flags = s.get(ValueLayout.JAVA_INT, 8);
        int ns = Math.min(r.starts, capStart), np = Math.min(r.pieces, capPieces);
        r.startData = new int[ns * StructureTables.START_W];
        MemorySegment.copy(s, ValueLayout.JAVA_INT, OUT_HDR * 4L, r.startData, 0, r.startData.length);
        if (np > 0) {
            gpu.downloadAsync(rbPieces[1], bPieces[0], (long) np * StructureTables.PIECE_W * 4);
            gpu.sync();
            MemorySegment p = MemorySegment.ofAddress(rbPieces[1]).reinterpret(rbPieces[2]);
            r.pieceData = new int[np * StructureTables.PIECE_W];
            MemorySegment.copy(p, ValueLayout.JAVA_INT, 0, r.pieceData, 0, r.pieceData.length);
        }
        return r;
    }

    /** Test: leert die Arena, erzeugt die Region und liest alles zurueck (Zeit in lastGenMs). */
    public Result generate(int cx0, int cz0, int cw, int cd) {
        return generate(cx0, cz0, cw, cd, null);
    }

    public Result generate(int cx0, int cz0, int cw, int cd, EnvProvider env) {
        reset();
        generateSync(cx0, cz0, cw, cd, env);
        return download();
    }

    // --- Zugriff fuer Umgebungs-Anbieter ---
    public CudaCompute gpu() {
        return gpu;
    }

    /** Kandidaten: int[0] = Anzahl, ab CAND_HDR je 4 ints (Set, cx, cz, Kachel-Offset in ints; -1 = keine Kachel) */
    public long[] candidateBuffer() {
        return bCands;
    }

    /** Kachel-Puffer (Format wg_struct_env_tile.cuh): Kacheln ab ENV_BASE ints, Offsets der Kandidaten relativ dazu */
    public long[] envBuffer() {
        return bEnv;
    }

    /** Biom-Ebenen der Ringe: je Ring 57 x 57 Bytes (Quart-Ursprung ncx - 28, ncz - 28), 255 = ungueltig */
    public long[] ringPlaneBuffer() {
        return bRingPlanes;
    }

    /** Die 13 Bindungen der StructureGpu-Kernel (fuer Anbieter-Kernel im selben Modul) */
    public long[][] bindings() {
        return binds;
    }

    /** Ringpositionen (int2 je Ring), nach rings_init die Ausgangspositionen */
    public long[] ringPositionBuffer() {
        return bRingPos;
    }

    /**
     * Test: Draufsicht der Strukturbloecke. Pixel (px, pz) = Spalte (x0 + px * step, z0 + pz * step);
     * Ergebnis je Pixel zwei ints (Zustands-Id mit Bit 31 = Struktur, y des obersten Blocks) oder (0, 0).
     */
    public int[] topView(int x0, int z0, int w, int h, int step, int yMin, int yMax, boolean terrainHeights) {
        int[] push = {x0, z0, w, h, step, yMin, yMax, terrainHeights ? 1 : 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(topview, push, (w + 63) / 64, h)), viewUpload());
        long bytes = (long) w * h * 8;
        long[] rb = gpu.readbackBuffer(bytes);
        gpu.downloadAsync(rb[1], bOut[0], bytes);
        gpu.sync();
        int[] out = new int[w * h * 2];
        MemorySegment.copy(MemorySegment.ofAddress(rb[1]).reinterpret(bytes), ValueLayout.JAVA_INT, 0, out, 0, out.length);
        return out;
    }

    /**
     * Test: Seitenansicht bei konstantem z. Pixel (px, py): x = x0 + px * step, y = yTop - py * step; je Pixel (Ergebnis, Gelaendehoehe).
     */
    public int[] slice(int x0, int z, int w, int h, int step, int yTop, boolean terrainHeights) {
        int[] push = {x0, z, w, h, step, yTop, 0, terrainHeights ? 1 : 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(slice, push, (w + 63) / 64, h)), viewUpload());
        long bytes = (long) w * h * 8;
        long[] rb = gpu.readbackBuffer(bytes);
        gpu.downloadAsync(rb[1], bOut[0], bytes);
        gpu.sync();
        int[] out = new int[w * h * 2];
        MemorySegment.copy(MemorySegment.ofAddress(rb[1]).reinterpret(bytes), ValueLayout.JAVA_INT, 0, out, 0, out.length);
        return out;
    }

    /** Zaehler des Jigsaw-Loesers (nur Modul wg_struct_synth_stats) */
    public long[] readStats() {
        gpu.downloadAsync(rbStats[1], bStats[0], 256);
        gpu.sync();
        long[] out = new long[16];
        MemorySegment.copy(MemorySegment.ofAddress(rbStats[1]).reinterpret(256), ValueLayout.JAVA_LONG, 0, out, 0, 16);
        return out;
    }

    public void clearStats() {
        gpu.zeroAsync(bStats[0], 256);
    }

    @Override
    public void close() {
        place.close();
        gen.close();
        index.close();
        indexCommit.close();
        topview.close();
        slice.close();
        rings.close();
        ringsInit.close();
        ringsSearch.close();
    }
}
