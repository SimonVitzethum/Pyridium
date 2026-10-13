package simon.pyridium.worldgen.full;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.worldgen.GpuTerrain;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.ExactBiomePipeline;
import simon.pyridium.worldgen.exact.FastTerrainPipeline;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;

/**
 * Server integration of the GPU full-chunk generator (docs/FULL_CHUNK_GEN.md). Hooks (mixins):
 * ChunkGenerator.createBiomes (BIOMES status) runs the whole generation for a chunk on the GPU in batches and
 * fills blocks, biomes and heightmaps; buildTerrain / generateFeatures / structure steps are then no-ops for
 * that chunk ({@link #isGpuChunk}). Unsupported cases (other dimensions, blending, upgrading, datapacks the
 * exact compiler rejects, no CUDA) fall back to the previous path and are LOGGED once, never silent.
 * Switch: -Dpyridium.fullGpu=false. Other flags: pyridium.fullBatch (chunks per launch), pyridium.fullProfile,
 * pyridium.fullVerify (compare terrain with Vanilla at start, stages carve/ore off for that test).
 */
public final class FullChunkService {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/full-gpu");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pyridium.fullGpu", "true"));
    private static final boolean PROFILE = Boolean.getBoolean("pyridium.fullProfile");
    private static final boolean VERIFY = Boolean.getBoolean("pyridium.fullVerify");

    private static volatile @Nullable FullChunkService instance;
    private static final Map<ChunkAccess, Boolean> GPU_CHUNKS = Collections.synchronizedMap(new WeakHashMap<>());

    private final MinecraftServer server;
    private final int batch;
    private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final ExecutorService fill;
    private volatile boolean running = true;
    private @Nullable CudaCompute vk;
    private final Map<RandomState, Gen> gens = new IdentityHashMap<>();
    private final AtomicLong chunks = new AtomicLong(), fillNs = new AtomicLong(), fallback = new AtomicLong();
    private final AtomicLong tBio = new AtomicLong(), tBlk = new AtomicLong(), tSec = new AtomicLong(), tHm = new AtomicLong(), tLight = new AtomicLong();
    private double gpuMs;
    private long batches, lastStats = System.nanoTime();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static BlockState[] STATES;
    private static final MemorySegment UNIFORM_15 = java.lang.foreign.Arena.global().allocate(2048), UNIFORM_0 = java.lang.foreign.Arena.global().allocate(2048);

    static {
        UNIFORM_15.fill((byte) 0xFF);
    }
    /** Sky-light layers computed on the GPU, consumed by the INITIALIZE_LIGHT step (weak: chunk -> layers per section index). */
    private static final Map<ChunkAccess, net.minecraft.world.level.chunk.DataLayer[]> SKY = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ChunkAccess, Boolean> GPU_LIT = Collections.synchronizedMap(new WeakHashMap<>());

    /** GPU sky-light layers of a GPU chunk (null: none, Vanilla lights it). Consumed once. */
    public static net.minecraft.world.level.chunk.DataLayer @Nullable [] takeSkyLight(ChunkAccess chunk) {
        var l = SKY.remove(chunk);
        if (l != null) GPU_LIT.put(chunk, Boolean.TRUE);
        return l;
    }

    /** True for chunks whose light data came from the GPU: the LIGHT step then only marks them lit. */
    public static boolean isGpuLit(ChunkAccess chunk) {
        return GPU_LIT.containsKey(chunk);
    }

    private static final class Gen {
        @Nullable FullChunkPipeline pipe;
        @Nullable TerrainSource terrain;
        @Nullable ExactBiomePipeline biome;
        @Nullable CudaCompute gvk;
        @Nullable String disabled;
        int minY, height, cap, maxY, sea;
        boolean fast;
        long perChunk, retryAt, waitSince, lastGrowCheck = System.nanoTime();
        long lastWarn;
        // models (built once per generator)
        ExactTerrainPipeline.Model exactModel;
        FastTerrainPipeline.Model fastModel;
        ExactBiomePipeline.Model biomeModel;
        NoiseGeneratorSettings settings;
        WorldGenerationContext ctx;
        long seed;
        net.minecraft.world.level.levelgen.RandomState rs;
        @Nullable FullStructures structs;
        FullTables tables;
        Strategy<BlockState> blockStrategy;
        Strategy<Holder<Biome>> biomeStrategy;
        int[] bitsTable;

        void closePipes() {
            if (pipe != null) {
                try {
                    pipe.drain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                pipe.close();
            }
            if (terrain != null) terrain.close();
            if (biome != null) biome.close();
            if (gvk != null) gvk.close();
            structs = null;   // gehoert zu gvk (Modul-/Speicher-Besitz endet mit ihm)
            pipe = null;
            terrain = null;
            biome = null;
            gvk = null;
        }
    }

    private record Request(NoiseBasedChunkGenerator gen, ChunkAccess chunk, RandomState rs, StructureManager sm, CompletableFuture<ChunkAccess> result) {
    }

    private FullChunkService(MinecraftServer server) {
        this.server = server;
        this.batch = Math.clamp(Integer.getInteger("pyridium.fullBatch", 64), 1, 1024);
        this.thread = new Thread(this::loop, "pyridium-full-gpu");
        this.thread.setDaemon(true);
        Thread wd = new Thread(() -> {
            long lastWarn = 0;
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime();
                if (pending.get() > 0 && now - lastProgress > 30_000_000_000L && now - lastWarn > 30_000_000_000L) {
                    lastWarn = now;
                    LOG.error("GPU-Vollchunk Watchdog: {} Anfragen seit {} s ohne Fortschritt (Warteschlange {}, Fallback {}). Moegliche Ursache: Vanilla-Chunksystem "
                            + "('Requested chunk unavailable during world generation', siehe -Dpyridium.fullDebug) oder GPU/VRAM-Mangel", pending.get(),
                            (now - lastProgress) / 1_000_000_000L, queue.size(), failed);
                }
            }
        }, "pyridium-full-watchdog");
        wd.setDaemon(true);
        wd.start();
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        var tf = new java.util.concurrent.ThreadFactory() {
            int n;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pyridium-full-fill-" + n++);
                t.setDaemon(true);
                return t;
            }
        };
        this.fill = Executors.newFixedThreadPool(workers, tf);
    }

    public static void serverStarting(MinecraftServer server) {
        if (!ENABLED) {
            LOG.info("GPU-Vollchunk aus (pyridium.fullGpu=false): Vanilla-/Teil-GPU-Pfad");
            return;
        }
        FullChunkService s = new FullChunkService(server);
        instance = s;
        s.thread.start();
    }

    public static void serverStopped(MinecraftServer server) {
        FullChunkService s = instance;
        instance = null;
        if (s != null) s.stop();
    }

    private void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        fill.shutdown();
    }

    /** True if this ProtoChunk was fully generated on the GPU (terrain/features/structures steps become no-ops). */
    public static boolean isGpuChunk(ChunkAccess chunk) {
        return GPU_CHUNKS.containsKey(chunk);
    }

    /** True while the full-GPU path owns generation (structure starts etc. are skipped for it). */
    public static boolean active() {
        FullChunkService s = instance;
        return s != null && s.running && !s.failed;
    }

    private volatile boolean failed;
    /** Requests handed to the service and not yet completed (watchdog). */
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastProgress = System.nanoTime();
    private static volatile boolean structuresLogged;

    /**
     * Structure-starts hook: true when the GPU path owns this generator (overworld-like) - no CPU structure
     * generation then. The GPU structure stage plugs in via FullChunkPipeline.addStage (docs/FULL_CHUNK_GEN.md).
     */
    public static boolean skipStructures(net.minecraft.world.level.chunk.ChunkGenerator gen) {
        FullChunkService s = instance;
        if (s == null || s.failed || !s.running || !(gen instanceof NoiseBasedChunkGenerator ng)) return false;
        String key = ng.generatorSettings().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (key.equals("nether") || key.equals("end") || key.equals("caves") || key.equals("floating_islands")) return false;
        if (!structuresLogged) {
            structuresLogged = true;
            LOG.warn("GPU-Vollchunk: Strukturen werden im GPU-Pfad noch nicht erzeugt (kein CPU-Vanilla-Strukturstart); Hook: FullChunkPipeline.addStage");
        }
        return true;
    }

    /** BIOMES-step entry: returns the future of the fully generated chunk or null (previous path / Vanilla). */
    public static @Nullable CompletableFuture<ChunkAccess> tryGenerate(NoiseBasedChunkGenerator gen, RandomState rs, Blender blender,
                                                                       StructureManager sm, ChunkAccess chunk) {
        FullChunkService s = instance;
        if (s == null || s.failed || !s.running) return null;
        if (blender != Blender.empty() || chunk.isUpgrading()) return null;
        Gen g;
        synchronized (s.gens) {
            g = s.gens.computeIfAbsent(rs, k -> new Gen());
        }
        if (g.disabled != null) return null;
        String key = gen.generatorSettings().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        if (key.equals("nether") || key.equals("end") || key.equals("caves") || key.equals("floating_islands")) {
            if (g.disabled == null) {
                g.disabled = "Dimension " + key;
                LOG.info("GPU-Vollchunk fuer {} nicht vorgesehen: Fallback auf Teil-GPU/Vanilla", key);
            }
            return null;
        }
        CompletableFuture<ChunkAccess> f = new CompletableFuture<>();
        s.pending.incrementAndGet();
        f.whenComplete((c, e) -> {
            s.pending.decrementAndGet();
            s.lastProgress = System.nanoTime();
        });
        s.queue.add(new Request(gen, chunk, rs, sm, f));
        return f;
    }

    // ---------------------------------------------------------------------------------------------

    private void loop() {
        try {
            vk = CudaCompute.create();
            for (long t0 = System.nanoTime(); vk == null && CudaCompute.lastError.contains("OUT_OF_MEMORY") && running
                    && System.nanoTime() - t0 < 300_000_000_000L; ) {
                LOG.warn("GPU-Vollchunk wartet auf VRAM fuer den CUDA-Kontext ({}), neuer Versuch", CudaCompute.lastError);
                TimeUnit.SECONDS.sleep(3);
                vk = CudaCompute.create();
            }
            if (vk == null) {
                LOG.warn("GPU-Vollchunk aus: CUDA nicht nutzbar ({}) -> Fallback (sichtbar im Log)", CudaCompute.lastError);
                failed = true;
                drainFallback();
                return;
            }
            LOG.info("GPU-Vollchunk auf {} (Stapel {} Chunks)", vk, batch);
            ArrayDeque<Request> later = new ArrayDeque<>();
            while (running) {
                Request first = later.isEmpty() ? queue.poll(200, TimeUnit.MILLISECONDS) : later.poll();
                if (first == null) {
                    logStats(false);
                    maybeGrow();
                    continue;
                }
                Gen g;
                synchronized (gens) {
                    g = gens.get(first.rs);
                }
                if (g.pipe == null && g.disabled == null && System.nanoTime() >= g.retryAt) setup(g, first);
                if (g.pipe == null && g.disabled == null) {
                    // VRAM too tight right now: hold the request and retry (never a silent Vanilla fallback because of OOM)
                    later.add(first);
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }
                if (g.pipe == null) {
                    toFallback(first);
                    continue;
                }
                FullChunkPipeline pipe = g.pipe;
                List<Request> list = new ArrayList<>();
                list.add(first);
                Request r;
                while (list.size() < pipe.capacity && (r = later.isEmpty() ? queue.poll() : later.poll()) != null) {
                    if (r.rs == first.rs) list.add(r);
                    else later.add(r);
                }
                try {
                    run(g, pipe, list, later);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Throwable e) {
                    LOG.error("GPU-Vollchunk Stapel fehlgeschlagen -> Fallback fuer {} Chunks", list.size(), e);
                    failed = true;
                    for (Request q : list) if (!q.result.isDone()) toFallback(q);
                    throw e;
                }
                logStats(false);
            }
        } catch (InterruptedException e) {
            // stop
        } catch (Throwable e) {
            LOG.error("GPU-Vollchunk abgebrochen, ab jetzt Fallback", e);
            failed = true;
        } finally {
            failed = true;
            drainFallback();
            logStats(true);
            fill.shutdown();
            synchronized (gens) {
                for (Gen g : gens.values()) g.closePipes();
                gens.clear();
            }
            if (vk != null) vk.close();
        }
    }

    private void drainFallback() {
        Request r;
        while ((r = queue.poll()) != null) toFallback(r);
    }

    /** Fallback: run the previous path (partial GPU/Vanilla) for this chunk's BIOMES step. */
    private void toFallback(Request r) {
        fallback.incrementAndGet();
        FALLBACK.set(true);
        CompletableFuture<ChunkAccess> f;
        try {
            f = r.gen.createBiomes(r.rs, Blender.empty(), r.sm, r.chunk);
        } catch (Throwable e) {
            r.result.completeExceptionally(e);
            return;
        } finally {
            FALLBACK.set(false);
        }
        f.whenComplete((c, e) -> {
            if (e != null) r.result.completeExceptionally(e);
            else r.result.complete(c);
        });
    }

    private static final ThreadLocal<Boolean> FALLBACK = ThreadLocal.withInitial(() -> false);

    /** True while the fallback runs on this thread (the mixin must then not intercept again). */
    public static boolean inFallback() {
        return FALLBACK.get();
    }

    /** Vertical biome subsampling (quarts per sample): 4 = one biome per 16 blocks of height in the fast path. */
    private static final int BIOME_YS = !"exact".equals(System.getProperty("pyridium.fullMode", "fast")) && !VERIFY ? Math.max(1, Integer.getInteger("pyridium.fullBiomeYs", 8)) : 1;
    private static final double VRAM_SHARE = Double.parseDouble(System.getProperty("pyridium.fullVramShare", "0.11"));
    private static final boolean FAST = !"exact".equals(System.getProperty("pyridium.fullMode", "fast"));

    private void setup(Gen g, Request r) {
        long t0 = System.nanoTime();
        try {
            if (g.exactModel == null && g.fastModel == null && !buildModels(g, r)) return;
            if (g.pipe != null) return;
            long[] info = Vram.info();
            long free = info != null ? info[0] : Long.MAX_VALUE / 2, total = info != null ? info[1] : 8L << 30;
            long reserve = 0;
            // the renderer/LOD generator need the rest of the card: take at most ~11 % of the VRAM that is free right now
            long budget = (long) (free * VRAM_SHARE);
            int cap = (int) Math.min(batch, Math.max(0, budget / Math.max(1, g.perChunk)));
            cap &= ~7;
            for (; ; ) {
                if (cap < 8) {
                    long now = System.nanoTime();
                    if (g.waitSince == 0) g.waitSince = now;
                    if (now - g.lastWarn > 10_000_000_000L) {
                        g.lastWarn = now;
                        LOG.warn("GPU-Vollchunk wartet auf VRAM: frei {} MiB, Reserve {} MiB, Bedarf {} KiB je Chunk (Anfragen warten, kein Vanilla-Fallback)",
                                free >> 20, reserve >> 20, g.perChunk >> 10);
                    }
                    if (now - g.waitSince > 120_000_000_000L) {
                        g.disabled = "kein VRAM (120 s gewartet)";
                        LOG.error("GPU-Vollchunk: {} -> Fallback auf Teil-GPU/Vanilla (laut)", g.disabled);
                    }
                    g.retryAt = now + 1_000_000_000L;
                    return;
                }
                try {
                    build(g, cap);
                    break;
                } catch (Throwable e) {
                    g.closePipes();
                    if (!Vram.isOom(e)) throw e;
                    LOG.warn("GPU-Vollchunk: nicht genug VRAM fuer Stapel {} ({}), verkleinere", cap, e.getMessage());
                    cap = (cap / 2) & ~7;
                    free = Math.max(0, free / 2);
                }
            }
            g.waitSince = 0;
            if (Boolean.getBoolean("pyridium.fullSelfBench")) selfBench(g);
            if (VERIFY && !g.fast) verify(g, g.pipe, r, g.settings.noiseSettings().clampToHeightAccessor(r.chunk.getHeightAccessorForGeneration()), g.settings);
            LOG.info("GPU-Vollchunk bereit in {} ms ({}-Terrain): Hoehe {}, Stapel {} von max {} (VRAM frei {} MiB, {} KiB/Chunk), {} Erz-Features",
                    (System.nanoTime() - t0) / 1_000_000, g.fast ? "schnelles" : "exaktes", g.height, g.cap, batch, free >> 20, g.perChunk >> 10, g.tables.oreCount);
        } catch (Throwable e) {
            g.disabled = e.toString();
            LOG.error("GPU-Vollchunk Aufbau fehlgeschlagen -> Fallback", e);
        }
    }

    /** GPU-only throughput test (no Minecraft chunk system): -Dpyridium.fullSelfBench=true; batches over a dense chunk grid, device sync + download included. */
    private void selfBench(Gen g) throws InterruptedException {
        FullChunkPipeline pipe = g.pipe;
        int n = pipe.capacity, side = (int) Math.ceil(Math.sqrt(n * 24.0));
        long chunks = 0, profChunks = 0;
        double wall = 0;
        double[] stage = new double[pipe.stageMs.length];
        for (int iter = 0; iter < 26; iter++) {
            int[] xz = new int[n * 2];
            for (int i = 0; i < n; i++) {
                int gx = iter * 40 + 4000 + i % 12, gz = (i / 12) + iter * 11;
                xz[i * 2] = gx * 16;
                xz[i * 2 + 1] = gz * 16;
            }
            if (iter == 22) pipe.profile = true;
            long t0 = System.nanoTime();
            FullChunkPipeline.Out out = pipe.run(n, xz);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (iter >= 2 && iter < 22) {
                wall += ms;
                chunks += out.n;
            }
            if (iter >= 22) profChunks += out.n;
            for (int c = 0; c < out.n; c++) out.done();
        }
        for (int i = 0; i < stage.length; i++) stage[i] = pipe.stageMs[i];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FullChunkPipeline.STAGES.length; i++) sb.append(' ').append(FullChunkPipeline.STAGES[i]).append('=').append(String.format("%.4f", stage[i] / Math.max(1, profChunks)));
        LOG.info("SelfBench GPU-only: {} Chunks in {} ms = {} Chunks/s (Stapel {}, sync+Download inkl.); ms/Chunk je Stufe (separat gemessen):{}", chunks,
                String.format("%.1f", wall), String.format("%.0f", chunks / (wall / 1000)), n, sb);
        pipe.profile = PROFILE;
        java.util.Arrays.fill(pipe.stageMs, 0);
    }

    /** Models + tables once per generator; false if the generator is unsupported (g.disabled set, logged). */
    private boolean buildModels(Gen g, Request r) {
        NoiseGeneratorSettings settings = r.gen.generatorSettings().value();
        var heights = r.chunk.getHeightAccessorForGeneration();
        NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(heights);
        g.settings = settings;
        g.minY = ns.minY();
        g.height = ns.height();
        g.maxY = heights.getMaxY();
        g.sea = settings.seaLevel();
        g.seed = r.rs.seed();
        g.rs = r.rs;
        g.fast = FAST && !VERIFY;
        if ((g.height & 15) != 0 || g.height > 512) {
            g.disabled = "Hoehe " + g.height;
            LOG.warn("GPU-Vollchunk: {} -> Fallback", g.disabled);
            return false;
        }
        g.ctx = new WorldGenerationContext(r.gen, heights);
        List<String> unsup = new ArrayList<>();
        if (g.fast) {
            g.fastModel = FastTerrainPipeline.model(r.rs, settings, r.gen.getBiomeSource(), g.ctx, g.minY, g.height);
            unsup.addAll(g.fastModel.unsupported());
        } else {
            g.exactModel = ExactTerrainPipeline.model(r.rs, settings, r.gen.getBiomeSource(), g.ctx, g.minY, g.height);
            unsup.addAll(g.exactModel.unsupported());
        }
        g.biomeModel = ExactBiomePipeline.model(r.rs, settings, r.gen.getBiomeSource(), g.minY, g.height, BIOME_YS);
        unsup.addAll(g.biomeModel.unsupported());
        if (!unsup.isEmpty()) {
            g.disabled = "nicht unterstuetzt: " + unsup;
            LOG.warn("GPU-Vollchunk fuer {}: {} -> Fallback", r.gen.generatorSettings().unwrapKey().orElse(null), g.disabled);
            g.exactModel = null;
            g.fastModel = null;
            return false;
        }
        if (STATES == null) {
            BlockState[] st = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
            for (int i = 0; i < st.length; i++) st[i] = Block.BLOCK_STATE_REGISTRY.byId(i);
            STATES = st;
        }
        LevelChunkSection sample = r.chunk.getSection(0);
        g.blockStrategy = strategy(sample.getStates());
        g.biomeStrategy = strategy((PalettedContainer<?>) sample.getBiomes());
        g.bitsTable = FullTables.bitsTable(sample);
        g.tables = FullTables.build(settings, r.gen.getBiomeSource().possibleBiomes(), g.ctx, g.seed, g.minY, g.height);
        // device bytes per chunk (conservative): terrain + 3.5 x biome neighbourhood + packed output + light + ring buffers
        int qh = g.height >> 2, quarts = 16 * qh;
        var bp = g.biomeModel.plan();
        long terrainBytes = g.fast ? FastTerrainPipeline.bytesPerChunk(g.fastModel, g.height, qh)
                : 3L * 256 * g.height * 4 + (long) g.exactModel.plan().scratchFloats * 4 + (long) g.exactModel.plan().coordEntries * 12
                + 4L * simon.pyridium.worldgen.exact.ExactAquifer.stateWords(g.minY, g.height) + 36L * qh * 8 + 35 * 16 * 4 * 8;
        long biomeBytes = (long) (3.5 * ((long) bp.scratchFloats * 4 + (long) bp.coordEntries * 12 + (long) quarts * 18 * 4));
        long outBytes = (long) (g.height >> 4) * 1284 * 4 + 4 * 64 * 8 + (long) g.height * 128;
        g.perChunk = (long) ((terrainBytes + biomeBytes + outBytes) * 1.15);
        return true;
    }

    private void build(Gen g, int cap) throws Exception {
        CudaCompute gvk = CudaCompute.create();
        if (gvk == null) throw new IllegalStateException("CUDA: " + CudaCompute.lastError);
        g.gvk = gvk;
        TerrainSource terrain;
        if (g.fast) terrain = new FastTerrainPipeline(gvk, g.fastModel, g.minY, g.height, g.maxY, g.sea, g.seed, cap, ExactTerrainPipeline.JAR, true);
        else terrain = new ExactTerrainPipeline(gvk, g.exactModel, g.minY, g.height, g.maxY, g.seed, cap, ExactTerrainPipeline.JAR, true);
        g.terrain = terrain;
        ExactBiomePipeline biome = new ExactBiomePipeline(gvk, g.biomeModel, g.minY, g.height, Math.min(9 * cap, cap * 3 + 64), ExactTerrainPipeline.JAR, true, BIOME_YS);
        g.biome = biome;
        int[] map = new int[biome.biomes.biomes.size()];
        for (int i = 0; i < map.length; i++) {
            map[i] = terrain.biomeIndex(biome.biomes.biomes.get(i));
            if (map[i] < 0) throw new IllegalStateException("Biom " + biome.biomes.biomes.get(i).getRegisteredName() + " fehlt in der Terraintabelle");
        }
        FullChunkPipeline pipe = new FullChunkPipeline(gvk, terrain, biome, g.tables, g.bitsTable, map, FullDecor.build(biome.biomes.biomes), g.seed, cap,
                biome.capacity, g.sea, Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()), Block.BLOCK_STATE_REGISTRY.getId(Blocks.WATER.defaultBlockState()),
                Block.BLOCK_STATE_REGISTRY.getId(Blocks.LAVA.defaultBlockState()), Block.BLOCK_STATE_REGISTRY.getId(Blocks.BEDROCK.defaultBlockState()),
                ExactTerrainPipeline.JAR, !Boolean.getBoolean("pyridium.fullNoLight"));
        pipe.profile = PROFILE;
        // Strukturen (GPU, echte Bloecke): Arena + Stufe wg_full_struct; Fehler schalten sie nur ab
        if (g.structs == null && g.rs != null) {
            g.structs = FullStructures.create(gvk, server, g.rs, g.settings, biome.biomes, g.seed, g.minY, g.height, g.sea);
        }
        if (g.structs != null) {
            pipe.enableStructures(g.structs, Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIRT.defaultBlockState()), ExactTerrainPipeline.JAR);
        }
        g.cap = cap;
        g.pipe = pipe;
    }

    /** While idle: grow the batch capacity when VRAM became available (renderer/LOD released memory). */
    private void maybeGrow() throws InterruptedException {
        long now = System.nanoTime();
        synchronized (gens) {
            for (Gen g : gens.values()) {
                // idle for 2 minutes: give the VRAM back (rebuilt lazily with the next request)
                if (g.pipe != null && pending.get() == 0 && now - lastProgress > 120_000_000_000L) {
                    LOG.info("GPU-Vollchunk: 120 s ohne Anfragen, VRAM freigegeben (Stapel {})", g.cap);
                    g.closePipes();
                }
            }
        }
        synchronized (gens) {
            for (Gen g : gens.values()) {
                if (g.pipe == null || now - g.lastGrowCheck < 20_000_000_000L) continue;
                g.lastGrowCheck = now;
                long[] info = Vram.info();
                if (info == null) continue;
                long avail = (long) ((info[0] + g.perChunk * g.cap) * VRAM_SHARE);      // our own buffers are returned on rebuild
                int want = (int) Math.min(batch, avail / Math.max(1, g.perChunk)) & ~7;
                if (info[0] < (400L << 20) && g.cap > 16) want = (g.cap / 2) & ~7;          // other components are starving: shrink
                else if (want < g.cap * 3 / 2 + 8) continue;
                LOG.info("GPU-Vollchunk: VRAM-Anpassung, Stapel {} -> {}", g.cap, want);
                int old = g.cap;
                g.closePipes();
                try {
                    build(g, want);
                    g.cap = want;
                } catch (Throwable e) {
                    g.closePipes();
                    try {
                        build(g, old);
                    } catch (Throwable e2) {
                        LOG.error("GPU-Vollchunk Neuaufbau fehlgeschlagen, neuer Versuch spaeter", e2);
                        g.closePipes();
                        g.retryAt = now + 2_000_000_000L;
                    }
                }
            }
        }
    }

    private static final int[][] VERIFY_POS = {{0, 0}, {37, -12}, {-250, 410}, {1234, -777}, {-4000, -9000}, {20000, 3000}, {-61000, 52000}, {150000, -120000}};

    /** Terrain-only comparison with Vanilla (doFill + buildSurface with the same biomes) on 8 chunks; stages off for this test. */
    private void verify(Gen g, FullChunkPipeline pipe, Request r, NoiseSettings ns, NoiseGeneratorSettings settings) throws InterruptedException {
        int saved = pipe.stageMask;
        pipe.stageMask = 0;
        int n = VERIFY_POS.length;
        int[] xz = new int[n * 2];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = VERIFY_POS[i][0] * 16;
            xz[i * 2 + 1] = VERIFY_POS[i][1] * 16;
        }
        FullChunkPipeline.Out out = pipe.run(n, xz);
        var heights = r.chunk.getHeightAccessorForGeneration();
        var reg = server.registryAccess();
        var biomesRaw = r.gen.getBiomeSource().createUncachedResolver(r.rs);
        Map<Long, Holder<Biome>> memo = new java.util.HashMap<>();
        net.minecraft.world.level.biome.BiomeResolver biomes = (qx, qy, qz) -> memo.computeIfAbsent(net.minecraft.core.BlockPos.asLong(qx, qy, qz),
                k -> biomesRaw.getNoiseBiome(qx, qy, qz));
        var bm = new net.minecraft.world.level.biome.BiomeManager(biomes, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(r.rs.seed()));
        var acc = (simon.pyridium.mixin.NoiseBasedChunkGeneratorAccess) (Object) r.gen;
        long bad = 0, total = 0;
        for (int c = 0; c < out.n; c++) {
            var pos = new net.minecraft.world.level.ChunkPos(VERIFY_POS[c][0], VERIFY_POS[c][1]);
            var gpu = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY, heights,
                    net.minecraft.world.level.chunk.PalettedContainerFactory.create(reg), null);
            fillChunk(g, pipe, new Request(r.gen, gpu, r.rs, r.sm, new CompletableFuture<>()), out, c);
            var ref = new net.minecraft.world.level.chunk.ProtoChunk(pos, net.minecraft.world.level.chunk.UpgradeData.EMPTY, heights,
                    net.minecraft.world.level.chunk.PalettedContainerFactory.create(reg), null);
            ref.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
            ref.fillBiomesFromNoise(biomes);
            var vol = new net.minecraft.world.level.levelgen.densityfunction.DensityVolume(16, ns.height(), 16, pos.getMinBlockX(), ns.minY(), pos.getMinBlockZ());
            java.util.Set<Holder<Biome>> possible = new java.util.HashSet<>();
            int qx0 = net.minecraft.core.QuartPos.fromBlock(pos.getMinBlockX()) - 1, qz0 = net.minecraft.core.QuartPos.fromBlock(pos.getMinBlockZ()) - 1;
            for (int iz = 0; iz < 6; iz++) for (int ix = 0; ix < 6; ix++) for (int y = 0; y < ns.height() >> 2; y++)
                possible.add(biomes.getNoiseBiome(qx0 + ix, (ns.minY() >> 2) + y, qz0 + iz));
            try (var nc = new net.minecraft.world.level.levelgen.NoiseChunk(r.rs, net.minecraft.world.level.levelgen.Beardifier.EMPTY, settings,
                    acc.pyridium$fluidPicker().get(), Blender.empty(), vol)) {
                acc.pyridium$doFill(nc, ref);
                acc.pyridium$buildSurface(ref, nc, r.rs, bm, possible, settings.materialRule().value());
            }
            var p = new net.minecraft.core.BlockPos.MutableBlockPos();
            long chunkBad = 0;
            String first = null;
            for (int y = ns.minY(); y < ns.minY() + ns.height(); y++)
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    p.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    total++;
                    if (gpu.getBlockState(p) != ref.getBlockState(p)) {
                        if (first == null) first = p.toShortString() + " gpu=" + gpu.getBlockState(p) + " vanilla=" + ref.getBlockState(p);
                        chunkBad++;
                    }
                }
            for (var t : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG))
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    if (gpu.getHeight(t, x, z) != ref.getHeight(t, x, z)) {
                        if (first == null) first = "Heightmap " + t + " " + x + "/" + z + " gpu=" + gpu.getHeight(t, x, z) + " vanilla=" + ref.getHeight(t, x, z);
                        chunkBad++;
                    }
            if (chunkBad > 0) LOG.error("GPU-Vollchunk Pruefung Chunk {}: {} Abweichungen, erste {}", pos, chunkBad, first);
            bad += chunkBad;
            out.done();
        }
        LOG.info("GPU-Vollchunk Pruefung gegen Vanilla (nur Terrain): {} Abweichungen in {} Bloecken", bad, total);
        pipe.stageMask = saved;
    }

    @SuppressWarnings("unchecked")
    private static <T> Strategy<T> strategy(PalettedContainer<?> c) {
        try {
            var f = PalettedContainer.class.getDeclaredField("strategy");
            f.setAccessible(true);
            return (Strategy<T>) f.get(c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void run(Gen g, FullChunkPipeline pipe, List<Request> list, ArrayDeque<Request> later) throws InterruptedException {
        int n = list.size();
        int[] xz = new int[n * 2];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = list.get(i).chunk.getPos().getMinBlockX();
            xz[i * 2 + 1] = list.get(i).chunk.getPos().getMinBlockZ();
        }
        // chunk heights must match the compiled pipeline
        FullChunkPipeline.Out out = pipe.run(n, xz);
        if (out == null) {
            for (Request r : list) later.add(r);
            return;
        }
        batches++;
        gpuMs += pipe.lastMs;
        // chunks not processed (biome neighbourhood too big) go back to the queue
        for (int i = out.n; i < n; i++) later.addFirst(list.get(i));
        for (int c = 0; c < out.n; c++) {
            Request r = list.get(c);
            int ci = c;
            fill.execute(() -> {
                try {
                    long t0 = System.nanoTime();
                    ChunkAccess chunk = fillChunk(g, pipe, r, out, ci);
                    fillNs.addAndGet(System.nanoTime() - t0);
                    chunks.incrementAndGet();
                    r.result.complete(chunk);
                } catch (Throwable e) {
                    r.result.completeExceptionally(e);
                } finally {
                    out.done();
                }
            });
        }
        double share = GpuTerrain.currentShare();
        if (share > 0 && share < 1.0) {
            long sleep = (long) (pipe.lastMs * (1.0 / share - 1.0) * 1_000_000L);
            if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fill (CPU glue: copy packed GPU data into Minecraft's containers)
    // ---------------------------------------------------------------------------------------------

    private static final Heightmap.Types[] HM_ORDER = {Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES};

    private ChunkAccess fillChunk(Gen g, FullChunkPipeline pipe, Request r, FullChunkPipeline.Out out, int c) {
        ChunkAccess chunk = r.chunk;
        LevelChunkSection[] sections = chunk.getSections();
        int qh = pipe.qh;
        MemorySegment bio = out.biomes(c);
        int airId = Block.BLOCK_STATE_REGISTRY.getId(AIR);
        List<Holder<Biome>> bpal = new ArrayList<>(8);
        int[] bidx = new int[64];
        for (int s = 0; s < pipe.sections; s++) {
            // biomes of this section: container index (y << 4 | z << 2 | x), source qy + (qx + qz * 4) * qh
            bpal.clear();
            int[] seen = new int[8];
            long[] bl = null;
            int nb = 0;
            int[] ids = new int[8];
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    for (int x = 0; x < 4; x++) {
                        int b = bio.get(ValueLayout.JAVA_INT, 4L * ((s * 4 + y) / pipe.ys + (x + z * 4) * pipe.qhb));
                        int k = -1;
                        for (int i = 0; i < nb; i++) if (ids[i] == b) {
                            k = i;
                            break;
                        }
                        if (k < 0) {
                            if (nb == ids.length) ids = java.util.Arrays.copyOf(ids, nb * 2);
                            ids[nb] = b;
                            k = nb++;
                            bpal.add(g.biome.biome(b));
                        }
                        bidx[(y << 4) | (z << 2) | x] = k;
                    }
            long q0 = System.nanoTime();
            PalettedContainerRO<Holder<Biome>> biomes = packBiomes(g, bpal, bidx);
            tBio.addAndGet(System.nanoTime() - q0);
            q0 = System.nanoTime();
            // blocks
            MemorySegment sec = out.section(c, s);
            int count = sec.get(ValueLayout.JAVA_INT, 0);
            LevelChunkSection old = sections[s];
            if (count == 1 && (sec.get(ValueLayout.JAVA_INT, 32) == airId)) {
                sections[s] = new LevelChunkSection(old.getStates(), biomes);   // empty: counts are 0 anyway
                continue;
            }
            int bits = sec.get(ValueLayout.JAVA_INT, 4);
            List<BlockState> pal = new ArrayList<>(count);
            for (int i = 0; i < count; i++) pal.add(STATES[sec.get(ValueLayout.JAVA_INT, 32 + 4L * i)]);
            Optional<LongStream> data = Optional.empty();
            if (bits > 0) {
                int vpl = 64 / bits, nl = (4096 + vpl - 1) / vpl;
                long[] longs = new long[nl];
                MemorySegment.copy(sec, ValueLayout.JAVA_LONG_UNALIGNED, 1056, longs, 0, nl);
                data = Optional.of(LongStream.of(longs));
            }
            PalettedContainer<BlockState> states = PalettedContainer.unpack(g.blockStrategy, new PalettedContainerRO.PackedData<>(pal, data)).getOrThrow();
            long q1 = System.nanoTime();
            tBlk.addAndGet(q1 - q0);
            // construct without recalcBlockCounts (4096 palette lookups): counts come from the GPU
            LevelChunkSection ns = new LevelChunkSection(old.getStates(), biomes);
            SectionFields.set(ns, states, (short) sec.get(ValueLayout.JAVA_INT, 12), (short) sec.get(ValueLayout.JAVA_INT, 16),
                    (short) sec.get(ValueLayout.JAVA_INT, 20), (short) sec.get(ValueLayout.JAVA_INT, 24));
            sections[s] = ns;
            tSec.addAndGet(System.nanoTime() - q1);
        }
        long h0 = System.nanoTime();
        for (int m = 0; m < 4; m++) {
            MemorySegment h = out.heightmap(c, m);
            int bits = 32 - Integer.numberOfLeadingZeros(pipe.height);
            int nl = (256 + (64 / bits) - 1) / (64 / bits);
            long[] longs = new long[nl];
            MemorySegment.copy(h, ValueLayout.JAVA_LONG_UNALIGNED, 0, longs, 0, nl);
            chunk.setHeightmap(HM_ORDER[m], longs.clone());
            if (m == 0) chunk.setHeightmap(Heightmap.Types.WORLD_SURFACE_WG, longs.clone());
            if (m == 1) chunk.setHeightmap(Heightmap.Types.OCEAN_FLOOR_WG, longs.clone());
        }
        tHm.addAndGet(System.nanoTime() - h0);
        h0 = System.nanoTime();
        if (out.lights != 0) {
            MemorySegment sky = out.skyLight(c);
            var layers = new net.minecraft.world.level.chunk.DataLayer[pipe.sections];
            for (int s = 0; s < pipe.sections; s++) {
                // uniform layers (open sky = 15, solid rock = 0) need no 2 KB array: DataLayer(default) allocates lazily
                MemorySegment slice = sky.asSlice(2048L * s, 2048);
                if (slice.mismatch(UNIFORM_15) < 0) layers[s] = new net.minecraft.world.level.chunk.DataLayer(15);
                else if (slice.mismatch(UNIFORM_0) < 0) layers[s] = new net.minecraft.world.level.chunk.DataLayer(0);
                else {
                    byte[] d = new byte[2048];
                    MemorySegment.copy(sky, ValueLayout.JAVA_BYTE, 2048L * s, d, 0, 2048);
                    layers[s] = new net.minecraft.world.level.chunk.DataLayer(d);
                }
            }
            SKY.put(chunk, layers);
        }
        tLight.addAndGet(System.nanoTime() - h0);
        GPU_CHUNKS.put(chunk, Boolean.TRUE);
        return chunk;
    }

    /** Reflection access to LevelChunkSection's private counters (set once per section, no recount). */
    private static final class SectionFields {
        static final java.lang.reflect.Field STATES, NON_EMPTY, FLUID, TICK, FTICK;

        static {
            try {
                STATES = f("states");
                NON_EMPTY = f("nonEmptyBlockCount");
                FLUID = f("fluidCount");
                TICK = f("tickingBlockCount");
                FTICK = f("tickingFluidCount");
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static java.lang.reflect.Field f(String n) throws ReflectiveOperationException {
            var f = LevelChunkSection.class.getDeclaredField(n);
            f.setAccessible(true);
            return f;
        }

        static void set(LevelChunkSection s, PalettedContainer<BlockState> states, short nonEmpty, short fluid, short tick, short ftick) {
            try {
                STATES.set(s, states);
                NON_EMPTY.setShort(s, nonEmpty);
                FLUID.setShort(s, fluid);
                TICK.setShort(s, tick);
                FTICK.setShort(s, ftick);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static PalettedContainerRO<Holder<Biome>> packBiomes(Gen g, List<Holder<Biome>> pal, int[] idx) {
        int bits = g.biomeStrategy == null ? 0 : bitsFor(g.biomeStrategy, pal.size());
        Optional<LongStream> data = Optional.empty();
        if (bits > 0) {
            int vpl = 64 / bits, nl = (64 + vpl - 1) / vpl;
            long[] longs = new long[nl];
            for (int i = 0; i < 64; i++) longs[i / vpl] |= (long) idx[i] << ((i % vpl) * bits);
            data = Optional.of(LongStream.of(longs));
        }
        return PalettedContainer.unpack(g.biomeStrategy, new PalettedContainerRO.PackedData<>(List.copyOf(pal), data)).getOrThrow();
    }

    private static java.lang.reflect.Method CONFIG;

    private static int bitsFor(Strategy<?> st, int size) {
        try {
            if (CONFIG == null) {
                CONFIG = Strategy.class.getDeclaredMethod("getConfigurationForPaletteSize", int.class);
                CONFIG.setAccessible(true);
            }
            return ((net.minecraft.world.level.chunk.Configuration) CONFIG.invoke(st, size)).bitsInStorage();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void logStats(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastStats < 30_000_000_000L) return;
        lastStats = now;
        long n = chunks.get();
        if (n == 0) return;
        LOG.info("GPU-Vollchunk: {} Chunks, {} Stapel, GPU {} ms/Chunk (Wand je Stapel), CPU-Fuellen {} ms/Chunk, {} ueber Fallback", n, batches,
                String.format("%.3f", gpuMs / n), String.format("%.3f", fillNs.get() / 1e6 / n), fallback.get());
        if (PROFILE) {
            LOG.info("Fuellen ms/Chunk: Biome {} Bloecke {} Sektion {} Heightmaps {} Licht {}", String.format("%.3f", tBio.get() / 1e6 / n),
                    String.format("%.3f", tBlk.get() / 1e6 / n), String.format("%.3f", tSec.get() / 1e6 / n), String.format("%.3f", tHm.get() / 1e6 / n),
                    String.format("%.3f", tLight.get() / 1e6 / n));
            Gen g = gens.values().stream().filter(x -> x.pipe != null).findFirst().orElse(null);
            if (g != null) {
                StringBuilder sb = new StringBuilder("Stufen ms/Chunk (nur Profil-Laeufe):");
                for (int i = 0; i < FullChunkPipeline.STAGES.length; i++)
                    sb.append(' ').append(FullChunkPipeline.STAGES[i]).append('=').append(String.format("%.4f", g.pipe.stageMs[i] / Math.max(1, n)));
                LOG.info(sb.toString());
            }
        }
    }
}
