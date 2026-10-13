package simon.pyridium.worldgen.full;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.ExactBiomePipeline;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;
import simon.pyridium.worldgen.exact.GpuCompute;

/**
 * Device-resident full-chunk generator (batched, stateless per chunk): GPU biomes (own chunk + 8 neighbours
 * deduplicated over the batch) -> biome window gather -> exact terrain/aquifer/surface -> carver worms and
 * canyons -> ore blobs -> [hook: decoration/structure stages] -> heightmaps + sky light -> palette packing.
 * One stream submission and one download per batch. The result is Minecraft's own container format:
 * per 16^3 section a palette (state ids) plus SimpleBitStorage longs, per chunk four packed heightmaps and the
 * biome quarts. CPU only copies them into chunk containers ({@link FullChunkService}).
 *
 * <p>Further stages plug in through {@link #addStage}: any kernel that works on the shared state buffer
 * (binding 1 of the wg_full_* kernels) between the ore stage and the heightmaps, e.g. the structure agent's
 * {@code structureAt(x,y,z)} pass (see docs/FULL_CHUNK_GEN.md).
 */
public final class FullChunkPipeline implements AutoCloseable {
    public static final int SEC_STRIDE = 1288, HSTRIDE = 64;
    private static final int RING = 3;

    public final int minY, height, sections, capacity, bcap, qh, quarts, ys, qhb;
    private final CudaCompute vk;
    private final TerrainSource terrain;
    private final ExactBiomePipeline biome;
    private final GpuCompute.Kernel kWin, kCarve, kOre, kPack, kHeight, kZero, kDecor;
    private final long[] bFlags, bCarve, bOre, bCfg, bSlots, bMap, bPack, bHeight, bLight;
    private final long[][] rbPack = new long[RING][], rbHeight = new long[RING][], rbBio = new long[RING][], rbLight = new long[RING][];
    private final Semaphore[] free = new Semaphore[RING];
    private int ring;
    private final boolean light;
    private final List<GpuCompute.Dispatch> extraStages = new ArrayList<>();
    private final List<GpuCompute.Kernel> kernels = new ArrayList<>();
    /** Accumulated per-stage GPU ms (only with profile) and batches. */
    public final double[] stageMs = new double[8];
    public static final String[] STAGES = {"biomes", "window", "terrain", "carve", "ore", "stages", "height+light", "pack"};
    public boolean profile;
    /** bit 0 carvers, bit 1 ores, bit 2 extra stages, bit 3 decoration (-Dpyridium.fullStages, tests). */
    public int stageMask = Integer.getInteger("pyridium.fullStages", 255);
    private int profLogged;
    public double lastMs;
    public int lastUnique;

    /** One finished batch in a ring slot; call {@link #done()} once per chunk when its data was consumed. */
    public final class Out {
        public final int n, set;
        public final int[] chunkXZ;      // block minima
        public final int[] ownSlot;      // biome slot of each chunk
        public final long pack, heights, bio, lights;
        private final AtomicInteger users;

        Out(int n, int set, int[] chunkXZ, int[] ownSlot) {
            this.n = n;
            this.set = set;
            this.chunkXZ = chunkXZ;
            this.ownSlot = ownSlot;
            this.pack = rbPack[set][1];
            this.heights = rbHeight[set][1];
            this.bio = rbBio[set][1];
            this.lights = light ? rbLight[set][1] : 0;
            this.users = new AtomicInteger(n);
        }

        public void done() {
            if (users.decrementAndGet() == 0) free[set].release();
        }

        /** Section record (ints) of chunk c, section s. */
        public MemorySegment section(int c, int s) {
            return ExactTerrainPipeline.mem(pack + ((long) c * sections + s) * SEC_STRIDE * 4L, SEC_STRIDE * 4L);
        }

        /** Packed heightmap longs (as ints, 2 per long) of chunk c, map m (0 SURFACE, 1 OCEAN_FLOOR, 2 MOTION_BLOCKING, 3 NO_LEAVES). */
        public MemorySegment heightmap(int c, int m) {
            return ExactTerrainPipeline.mem(heights + ((long) c * 4 + m) * HSTRIDE * 8L, HSTRIDE * 8L);
        }

        /** Biome quart indices (biome pipe order) of chunk c: qy / ys + (qx + qz * 4) * qhb. */
        public MemorySegment biomes(int c) {
            return ExactTerrainPipeline.mem(bio + (long) ownSlot[c] * quarts * 4L, (long) quarts * 4L);
        }

        /** Sky light of chunk c (height * 128 bytes, section-major nibbles). */
        public MemorySegment skyLight(int c) {
            return ExactTerrainPipeline.mem(lights + (long) c * height * 128L, (long) height * 128L);
        }
    }

    public FullChunkPipeline(CudaCompute vk, TerrainSource terrain, ExactBiomePipeline biome, FullTables tables, int[] bitsTable,
                             int[] bioMap, int[] decorTable, long seed, int capacity, int biomeCapacity, int seaLevel, int airId, int waterId, int lavaId, int bedrockId,
                             GpuCompute.BinarySource src, boolean light) throws Exception {
        this.vk = vk;
        this.terrain = terrain;
        this.biome = biome;
        this.minY = terrain.minY();
        this.height = terrain.height();
        this.sections = height >> 4;
        this.capacity = capacity;
        this.bcap = biomeCapacity;
        this.qh = terrain.qh();
        this.qhb = biome.qh;
        this.ys = biome.ys;
        this.quarts = 16 * qhb;
        this.light = light;
        int hbits = 32 - Integer.numberOfLeadingZeros(height);          // ceil(log2(height + 1))
        int[] cfg = new int[FullTables.CFG_DECOR + 64];
        cfg[0] = minY;
        cfg[1] = height;
        cfg[2] = seaLevel;
        cfg[3] = minY + 10;
        cfg[4] = airId;
        cfg[5] = waterId;
        cfg[6] = lavaId;
        cfg[7] = bedrockId;
        cfg[8] = hbits;
        cfg[9] = tables.flags.length;
        cfg[10] = (int) seed;
        cfg[11] = (int) (seed >>> 32);
        cfg[12] = qhb;
        cfg[14] = ys;
        cfg[13] = quarts;
        System.arraycopy(bitsTable, 0, cfg, FullTables.CFG_BITS_TABLE, 257);
        bCfg = vk.upload(cfg);
        bFlags = vk.upload(tables.flags);
        bCarve = vk.upload(toInts(tables.carve));
        bOre = vk.upload(toInts(tables.ore));
        bSlots = vk.buffer(capacity * 9L * 4);
        bMap = vk.upload(bioMap);
        bPack = vk.deviceBuffer((long) capacity * sections * SEC_STRIDE * 4);
        bHeight = vk.deviceBuffer((long) capacity * 4 * HSTRIDE * 8);
        bLight = light ? vk.deviceBuffer((long) capacity * height * 128) : vk.deviceBuffer(16);
        for (int i = 0; i < RING; i++) {
            rbPack[i] = vk.readbackBuffer((long) capacity * sections * SEC_STRIDE * 4);
            rbHeight[i] = vk.readbackBuffer((long) capacity * 4 * HSTRIDE * 8);
            rbBio[i] = vk.readbackBuffer((long) biomeCapacity * quarts * 4);
            rbLight[i] = vk.readbackBuffer(light ? (long) capacity * height * 128 : 16);
            free[i] = new Semaphore(1);
        }
        kWin = kernel(src, "wg_full_win", 4, 128);
        kCarve = kernel(src, "wg_full_carve", 6, 256);
        kOre = kernel(src, "wg_full_ore", 6, 256);
        kPack = kernel(src, "wg_full_pack", 6, 256);
        kHeight = kernel(src, "wg_full_height", 6, 256);
        kZero = kernel(src, "wg_full_zero", 1, 256);
        kDecor = kernel(src, "wg_full_decor", 7, 256);
        long[] bDecor = vk.upload(decorTable);
        long[] st = terrain.stateBuffer(), ch = terrain.chunkBuffer();
        kWin.bind(biome.outBuffer(), bSlots, bMap, terrain.windowBuffer());
        kCarve.bind(ch, st, bFlags, bCarve, bOre, bCfg);
        kOre.bind(ch, st, bFlags, bCarve, bOre, bCfg);
        kPack.bind(ch, st, bPack, bFlags, bOre, bCfg);
        kHeight.bind(ch, st, bFlags, bHeight, bLight, bCfg);
        kZero.bind(bLight);
        kDecor.bind(ch, st, bFlags, bDecor, biome.outBuffer(), bCfg, bSlots);
    }

    private GpuCompute.Kernel kernel(GpuCompute.BinarySource src, String name, int bindings, int block) throws Exception {
        GpuCompute.Kernel k = vk.kernel(src, name, bindings, block);
        kernels.add(k);
        return k;
    }

    private static int[] toInts(byte[] b) {
        int[] r = new int[(b.length + 3) / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(r, 0, b.length / 4);
        return r;
    }

    /** Extra stage between ores and heightmaps: a dispatch built by the caller against {@link #stateBuffer()}. */
    public void addStage(GpuCompute.Dispatch d) {
        extraStages.add(d);
    }

    /** Hook vor jedem Stapel (Strukturregionen bereitstellen), siehe FullStructures */
    public java.util.function.BiConsumer<Integer, int[]> beforeRun;

    /**
     * Strukturen als Stufe (wg_full_struct.cu): Bloecke aus der Arena von FullStructures.
     * @param dirtId Zustands-Id von Erde (Fundament unter Gras/Schnee)
     */
    public void enableStructures(FullStructures fs, int dirtId, GpuCompute.BinarySource src) throws Exception {
        GpuCompute.Kernel k = kernel(src, "wg_full_struct", 7, 256);
        k.bind(terrain.chunkBuffer(), terrain.stateBuffer(), bFlags, bCarve, bOre, bCfg, fs.sg.viewBuffer());
        addStage(new GpuCompute.Dispatch(k, new int[]{0, dirtId, 0, 0, 0, 0, 0, 0}, 1, 0));
        beforeRun = (n, xz) -> fs.prepare(n, xz);
    }

    public long[] stateBuffer() {
        return terrain.stateBuffer();
    }

    public long[] chunkBuffer() {
        return terrain.chunkBuffer();
    }

    /**
     * Generates up to n chunks (block minima in chunkXZ); returns the batch (possibly fewer chunks than asked, when the
     * biome neighbourhood exceeds the biome capacity: the caller re-queues the rest) or null for n == 0.
     */
    public Out run(int n, int[] chunkXZ) throws InterruptedException {
        n = Math.min(n, capacity);
        if (beforeRun != null && (stageMask & 4) != 0) beforeRun.accept(n, chunkXZ);
        // unique biome chunks: own + 8 neighbours, dedup over the batch
        Long2IntOpenHashMap slot = new Long2IntOpenHashMap();
        slot.defaultReturnValue(-1);
        int[] slotXZ = new int[bcap * 2];
        int[] winSlots = new int[n * 9];
        int[] own = new int[n];
        int u = 0, done = 0;
        for (int c = 0; c < n; c++) {
            int cx = chunkXZ[c * 2] >> 4, cz = chunkXZ[c * 2 + 1] >> 4;
            int before = u;
            boolean ok = true;
            for (int dz = -1; dz <= 1 && ok; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long key = ((long) (cx + dx) << 32) | ((cz + dz) & 0xffffffffL);
                    int s = slot.get(key);
                    if (s < 0) {
                        if (u >= bcap) {
                            ok = false;
                            break;
                        }
                        s = u++;
                        slot.put(key, s);
                        slotXZ[s * 2] = (cx + dx) * 16;
                        slotXZ[s * 2 + 1] = (cz + dz) * 16;
                    }
                    winSlots[c * 9 + (dz + 1) * 3 + dx + 1] = s;
                }
            if (!ok) {
                // roll back this chunk's new slots (they stay in the map but unused; harmless) and stop
                u = Math.max(u, before);
                break;
            }
            own[c] = winSlots[c * 9 + 4];
            done = c + 1;
        }
        n = done;
        if (n == 0) return null;
        lastUnique = u;
        int set = ring;
        ring = (ring + 1) % RING;
        free[set].acquire();
        // host inputs
        ExactTerrainPipeline.ints(bSlots[1], n * 9).put(0, winSlots, 0, n * 9);
        List<GpuCompute.Dispatch> list = new ArrayList<>();
        List<Integer> stage = new ArrayList<>();
        var bl = biome.prepare(u, slotXZ);
        list.addAll(bl);
        stage.addAll(java.util.Collections.nCopies(bl.size(), 0));
        list.add(new GpuCompute.Dispatch(kWin, new int[]{n, qh, quarts, ys, qhb, 0, 0, 0}, (36 * qh + 127) / 128, n));
        stage.add(1);
        int[] cxz = java.util.Arrays.copyOf(chunkXZ, n * 2);
        var tl = terrain.prepare(n, cxz);
        list.addAll(tl);
        stage.addAll(java.util.Collections.nCopies(tl.size(), 2));
        if ((stageMask & 1) != 0) {
            list.add(new GpuCompute.Dispatch(kCarve, new int[]{n, 0, 0, 0, 0, 0, 0, 0}, n, 1));
            stage.add(3);
        }
        if ((stageMask & 2) != 0) {
            list.add(new GpuCompute.Dispatch(kOre, new int[]{n, 0, 0, 0, 0, 0, 0, 0}, n, 1));
            stage.add(4);
        }
        if ((stageMask & 8) != 0) {
            list.add(new GpuCompute.Dispatch(kDecor, new int[]{n, 0, 0, 0, 0, 0, 0, 0}, n, 1));
            stage.add(5);
        }
        if ((stageMask & 4) != 0) for (GpuCompute.Dispatch d : extraStages) {
            int[] p = d.push().clone();
            p[0] = n;
            list.add(new GpuCompute.Dispatch(d.kernel(), p, d.gx(), n));
            stage.add(5);
        }
        if (light) {
            list.add(new GpuCompute.Dispatch(kZero, new int[]{n * height * 32, 0, 0, 0, 0, 0, 0, 0}, (n * height * 32 + 255) / 256 > 4096 ? 4096 : (n * height * 32 + 255) / 256, 1));
            stage.add(6);
        }
        list.add(new GpuCompute.Dispatch(kHeight, new int[]{n, 0, light ? 1 : 0, 0, 0, 0, 0, 0}, n, 1));
        stage.add(6);
        list.add(new GpuCompute.Dispatch(kPack, new int[]{n, sections, 0, 0, 0, 0, 0, 0}, sections, n));
        stage.add(7);
        List<long[]> copies = new ArrayList<>();
        copies.add(new long[]{bPack[0], rbPack[set][0], (long) n * sections * SEC_STRIDE * 4});
        copies.add(new long[]{bHeight[0], rbHeight[set][0], (long) n * 4 * HSTRIDE * 8});
        copies.add(new long[]{biome.outBuffer()[0], rbBio[set][0], (long) u * quarts * 4});
        if (light) copies.add(new long[]{bLight[0], rbLight[set][0], (long) n * height * 128});
        lastMs = vk.run(list, copies);
        if (profile && n >= 64 && profLogged++ < 2) {
            org.slf4j.LoggerFactory.getLogger("pyridium/full-gpu").info("Terrain-Profil Stapel {} Chunks (ms je Stapel):", n);
            for (String l : terrain.profileLast()) org.slf4j.LoggerFactory.getLogger("pyridium/full-gpu").info("  {}", l);
        }
        if (profile) {
            double[] t = vk.runTimed(list);
            for (int i = 0; i < t.length; i++) stageMs[stage.get(i)] += t[i];
        }
        return new Out(n, set, cxz, own);
    }

    /** Waits until all ring slots were consumed (before closing/rebuilding). */
    public void drain() throws InterruptedException {
        for (Semaphore s : free) s.acquire();
    }

    @Override
    public void close() {
        for (GpuCompute.Kernel k : kernels) k.close();
        kernels.clear();
    }
}
