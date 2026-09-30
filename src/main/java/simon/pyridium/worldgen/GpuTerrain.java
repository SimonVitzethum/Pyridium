package simon.pyridium.worldgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.mixin.NoiseBasedChunkGeneratorAccess;
import simon.pyridium.worldgen.exact.ExactBeard;
import simon.pyridium.worldgen.exact.ExactBiomePipeline;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.GpuCompute;

/**
 * Beschleunigte Terrain-Generierung: NoiseBasedChunkGenerator.buildTerrain (doFill + buildSurface)
 * auf der GPU, bitgleich zu Vanilla; Carver danach wie gehabt auf der CPU. Laeuft auf dem
 * dedizierten wie dem integrierten Server, Clients brauchen die Mod nicht.
 *
 * <p>Ein GPU-Thread sammelt Anfragen zu Stapeln (je Generator), rechnet sie und gibt das Schreiben
 * in die Chunks an Vanillas Hintergrund-Executor. Struktur-Anpassung (Beardifier) rechnet die GPU
 * mit; Chunks mit Blending, Upgrades oder unbekannten Biomen gehen unveraendert an Vanilla, ebenso alles, wenn
 * kein CUDA (Treiber, Geraet ab Compute Capability 7.5) da ist oder der Selbsttest gegen Vanilla
 * beim Start fehlschlaegt. Rechen-Backend ist ausschliesslich CUDA (CudaCompute, Driver API per FFM).
 *
 * <p>GPU-Anteil: im Client ueber {@link #setClientShare}, auf dem dedizierten Server in
 * config/pyridium-server.properties (gpuShare). Schalter (JVM-Properties): pyridium.gpuWorldgen=false
 * (aus), pyridium.gpuWorldgenShare (GPU-Anteil 0..1, ueberschreibt die Datei), pyridium.gpuWorldgenBatch
 * (Chunks je Stapel, Standard 32), pyridium.gpuWorldgenVerify=true (jeden Chunk zusaetzlich mit
 * Vanilla vergleichen, langsam), pyridium.gpuWorldgenCodegen=false (nur Interpreter),
 * pyridium.gpuWorldgenWaitMs (Sammelfenster je Stapel, Standard 0: Vanilla hat nur wenige Chunks
 * gleichzeitig in Arbeit, Latenz zaehlt mehr als Stapelgroesse).
 */
public final class GpuTerrain {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/gpu-worldgen");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pyridium.gpuWorldgen", "true"));
    private static final boolean VERIFY = Boolean.getBoolean("pyridium.gpuWorldgenVerify");
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Wie lange ein Stapel auf weitere Anfragen wartet (Latenz gegen Stapelgroesse). */
    private static final long WAIT_NS = (long) (Double.parseDouble(System.getProperty("pyridium.gpuWorldgenWaitMs", "0")) * 1_000_000L);

    private static volatile @Nullable GpuTerrain instance;
    private static volatile boolean failed;

    /** GPU-Anteil aus den Client-Einstellungen (Einzelspieler/LAN), &lt;0 = nicht gesetzt. */
    private static volatile double clientShare = -1;

    private final MinecraftServer server;
    private final double defaultShare;
    private final int batch;
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;
    private @Nullable GpuCompute vk;
    /** Je RandomState (= Dimension) der Aufbauzustand. */
    private final Map<RandomState, Gen> gens = new IdentityHashMap<>();
    private final AtomicLong gpuChunks = new AtomicLong(), fallbackChunks = new AtomicLong(), verifyBad = new AtomicLong(),
            structureChunks = new AtomicLong(), biomeChunks = new AtomicLong(), writeNs = new AtomicLong(), carverNs = new AtomicLong();
    private double biomeMsTotal;
    private double gpuMsTotal;
    private long lastStats = System.nanoTime();

    /** Generator-Zustand: Pipeline oder Grund, warum Vanilla rechnet. */
    private static final class Gen {
        volatile @Nullable ExactTerrainPipeline pipe;
        volatile @Nullable String disabled;
        /** BIOMES-Stufe: eigene Pipeline oder Grund, warum Vanilla rechnet. */
        volatile @Nullable ExactBiomePipeline biomePipe;
        volatile @Nullable String biomeDisabled;
        int minY, height;
        @Nullable NoiseSettings noise;
    }

    /** Auftrag an den GPU-Thread (buildTerrain oder createBiomes). */
    private sealed interface Job permits Request, BiomeRequest {
        RandomState rs();
    }

    private record BiomeRequest(NoiseBasedChunkGenerator gen, ChunkAccess chunk, RandomState rs, Blender blender, StructureManager sm,
                                CompletableFuture<ChunkAccess> result) implements Job {
    }

    private record Request(NoiseBasedChunkGenerator gen, ChunkAccess chunk, Blender blender, RandomState rs, StructureManager sm,
                           BiomeManager bm, @Nullable WorldGenRegion carverRegion, Holder<Biome>[] window,
                           Beardifier beardifier, int @Nullable [] beard, CompletableFuture<ChunkAccess> result) implements Job {
    }

    private GpuTerrain(MinecraftServer server) {
        this.server = server;
        boolean dedicated = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
        double fileShare = dedicated ? ServerConfig.load().gpuShare : -1;
        String prop = System.getProperty("pyridium.gpuWorldgenShare");
        this.defaultShare = Math.clamp(prop != null ? Double.parseDouble(prop) : fileShare >= 0 ? fileShare : dedicated ? 1.0 : 0.5, 0.0, 1.0);
        this.batch = Math.clamp(Integer.getInteger("pyridium.gpuWorldgenBatch", 32), 1, 256);
        thread = new Thread(this::loop, "pyridium-gpu-worldgen");
        thread.setDaemon(true);
    }

    /**
     * GPU-Anteil fuer die Terrain-Generierung (0..1) aus den Client-Einstellungen; 0 = aus (Vanilla).
     * Wirkt sofort auf den laufenden integrierten Server.
     */
    public static void setClientShare(double share) {
        clientShare = Math.clamp(share, 0.0, 1.0);
    }

    private double share() {
        double c = clientShare;
        return c >= 0 && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT ? c : defaultShare;
    }

    // ---------------------------------------------------------------------------------------
    // Lebenszyklus (Pyridium.onInitialize registriert die Server-Ereignisse)
    // ---------------------------------------------------------------------------------------

    public static void serverStarting(MinecraftServer server) {
        ServerConfig cfg = ServerConfig.load();
        FeatureScheduler.configure(cfg.parallelFeatures);
        if (!ENABLED) {
            LOG.info("GPU-Terrain aus (pyridium.gpuWorldgen=false)");
            return;
        }
        failed = false;
        GpuTerrain t = new GpuTerrain(server);
        instance = t;
        t.thread.start();
    }

    public static void serverStopped(MinecraftServer server) {
        FeatureScheduler.logStats();
        GpuTerrain t = instance;
        instance = null;
        if (t != null) t.stop();
    }

    private void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Einstieg aus dem Mixin (Chunk-Task-Thread)
    // ---------------------------------------------------------------------------------------

    /** GPU-Pfad fuer buildTerrain oder null (Vanilla rechnet). */
    public static @Nullable CompletableFuture<ChunkAccess> tryBuildTerrain(NoiseBasedChunkGenerator gen, ChunkAccess chunk, Blender blender,
                                                                           RandomState rs, StructureManager sm, BiomeManager bm,
                                                                           @Nullable WorldGenRegion carverRegion) {
        GpuTerrain t = instance;
        if (t == null || failed || BYPASS.get() || !t.running || t.share() <= 0) return null;
        ChunkPos pos = chunk.getPos();
        if (SharedConstants.debugVoidTerrain(pos) || SharedConstants.DEBUG_DISABLE_SURFACE || chunk.isUpgrading()) return null;
        if (blender != Blender.empty()) return null;
        Gen g;
        synchronized (t.gens) {
            g = t.gens.computeIfAbsent(rs, k -> new Gen());
        }
        if (g.disabled != null) return null;
        LevelHeightAccessor heights = chunk.getHeightAccessorForGeneration();
        NoiseSettings ns = gen.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        if (ns.height() <= 0 || ns.minY() != heights.getMinY() || ns.height() != heights.getHeight()) return null;
        // Strukturen mit Terrain-Anpassung: Beardifier-Daten fuer die GPU (OP_BEARD)
        Beardifier beardifier = Beardifier.forStructuresInChunk(sm, pos);
        int[] beard = beardifier == Beardifier.EMPTY ? null : ExactBeard.encode(beardifier);
        // Biomfenster wie buildSurface es liest: Quarts x/z -1..4 um den Chunk, alle Hoehen
        int qh = ns.height() >> 2, qy0 = QuartPos.fromBlock(ns.minY());
        @SuppressWarnings("unchecked")
        Holder<Biome>[] window = new Holder[36 * qh];
        int qx0 = QuartPos.fromBlock(pos.getMinBlockX()) - 1, qz0 = QuartPos.fromBlock(pos.getMinBlockZ()) - 1;
        for (int iz = 0; iz < 6; iz++) for (int ix = 0; ix < 6; ix++) for (int y = 0; y < qh; y++)
            window[y + (ix + iz * 6) * qh] = bm.getNoiseBiomeAtQuart(qx0 + ix, qy0 + y, qz0 + iz);
        CompletableFuture<ChunkAccess> f = new CompletableFuture<>();
        t.queue.add(new Request(gen, chunk, blender, rs, sm, bm, carverRegion, window, beardifier, beard, f));
        return f;
    }

    /** GPU-Pfad fuer createBiomes (BIOMES-Stufe) oder null (Vanilla rechnet). */
    public static @Nullable CompletableFuture<ChunkAccess> tryCreateBiomes(NoiseBasedChunkGenerator gen, RandomState rs, Blender blender,
                                                                           StructureManager sm, ChunkAccess chunk) {
        GpuTerrain t = instance;
        if (t == null || failed || BYPASS.get() || !t.running || t.share() <= 0) return null;
        if (blender != Blender.empty() || chunk.isUpgrading()) return null;
        if (!(gen.getBiomeSource() instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource)) return null;
        Gen g;
        synchronized (t.gens) {
            g = t.gens.computeIfAbsent(rs, k -> new Gen());
        }
        if (g.biomeDisabled != null) return null;
        NoiseSettings ns = gen.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        if (ns.minY() != chunk.getMinY() || ns.height() != chunk.getHeight()) return null;
        CompletableFuture<ChunkAccess> f = new CompletableFuture<>();
        t.queue.add(new BiomeRequest(gen, chunk, rs, blender, sm, f));
        return f;
    }

    // ---------------------------------------------------------------------------------------
    // GPU-Thread
    // ---------------------------------------------------------------------------------------

    /** Biom-Stapel: erste Anfrage plus alles Wartende derselben Dimension. */
    private void biomeJobs(Gen g, BiomeRequest first, ArrayDeque<Job> later) throws InterruptedException {
        if (g.biomePipe == null && g.biomeDisabled == null) setupBiomes(g, first);
        ExactBiomePipeline pipe = g.biomePipe;
        if (pipe == null) {
            biomeToVanilla(first);
            return;
        }
        List<BiomeRequest> batch = new ArrayList<>();
        batch.add(first);
        for (var it = later.iterator(); it.hasNext() && batch.size() < pipe.capacity; ) {
            Job j = it.next();
            if (j instanceof BiomeRequest b && b.rs == first.rs) {
                batch.add(b);
                it.remove();
            }
        }
        long deadline = System.nanoTime() + WAIT_NS;
        while (batch.size() < pipe.capacity) {
            Job r = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (r == null) break;
            if (r instanceof BiomeRequest b && b.rs == first.rs) batch.add(b);
            else later.add(r);
        }
        int[] chunkXZ = new int[batch.size() * 2];
        for (int c = 0; c < batch.size(); c++) {
            chunkXZ[c * 2] = batch.get(c).chunk.getPos().getMinBlockX();
            chunkXZ[c * 2 + 1] = batch.get(c).chunk.getPos().getMinBlockZ();
        }
        pipe.run(batch.size(), chunkXZ);
        biomeChunks.addAndGet(batch.size());
        biomeMsTotal += pipe.lastMs;
        for (int c = 0; c < batch.size(); c++) {
            BiomeRequest r = batch.get(c);
            int[] idx = pipe.copyChunk(c);
            CompletableFuture.supplyAsync(() -> fillBiomes(pipe, r, idx), Util.backgroundExecutor().forName("createBiomes"))
                    .whenComplete((chunk, e) -> {
                        if (e != null) r.result.completeExceptionally(e);
                        else r.result.complete(chunk);
                    });
        }
        throttle(pipe.lastMs);
    }

    /** Biome in den Chunk wie fillBiomesFromNoise; Pruefmodus vergleicht mit Vanillas Resolver. */
    private ChunkAccess fillBiomes(ExactBiomePipeline pipe, BiomeRequest r, int[] idx) {
        ChunkAccess chunk = r.chunk;
        int qx0 = QuartPos.fromBlock(chunk.getPos().getMinBlockX()), qz0 = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int qy0 = QuartPos.fromBlock(pipe.minY), qh = pipe.qh;
        chunk.fillBiomesFromNoise((qx, qy, qz) -> pipe.biome(idx[(qy - qy0) + ((qx - qx0) + (qz - qz0) * 4) * qh]));
        if (VERIFY) {
            int[] ref = vanillaBiomes(r.gen, r.rs, chunk.getPos(), pipe);
            for (int i = 0; i < ref.length; i++)
                if (ref[i] != idx[i]) {
                    verifyBad.incrementAndGet();
                    LOG.error("GPU-Biome Pruefung: Abweichung in {} (Quart {})", chunk.getPos(), i);
                    break;
                }
        }
        return chunk;
    }

    /** Vanillas createResolverForChunk mit zurueckgesetztem R-Baum-Startkandidaten (Index je Quart). */
    private static int[] vanillaBiomes(NoiseBasedChunkGenerator gen, RandomState rs, ChunkPos pos, ExactBiomePipeline pipe) {
        var bs = gen.getBiomeSource();
        var sampler = rs.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build());
        int qx0 = QuartPos.fromBlock(pos.getMinBlockX()), qz0 = QuartPos.fromBlock(pos.getMinBlockZ()), qy0 = QuartPos.fromBlock(pipe.minY);
        var resolver = bs.createResolverForChunk(sampler, qx0, qy0, qz0, 4, pipe.qh, 4);
        resetRTreeCandidate(bs);
        int[] out = new int[pipe.quarts];
        for (int sct = 0; sct < pipe.qh / 4; sct++) for (int x = 0; x < 4; x++) for (int yy = 0; yy < 4; yy++) for (int z = 0; z < 4; z++) {
            int qy = sct * 4 + yy;
            out[qy + (x + z * 4) * pipe.qh] = pipe.biomes.indexOf(resolver.getNoiseBiome(qx0 + x, qy0 + qy, qz0 + z));
        }
        return out;
    }

    private static void resetRTreeCandidate(net.minecraft.world.level.biome.BiomeSource bs) {
        try {
            var m = net.minecraft.world.level.biome.MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
            m.setAccessible(true);
            Object tree = simon.pyridium.worldgen.exact.ExactPlan.fieldIn(m.invoke(bs), "index");
            ((ThreadLocal<?>) simon.pyridium.worldgen.exact.ExactPlan.fieldIn(tree, "lastResult")).remove();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setupBiomes(Gen g, BiomeRequest r) {
        long t0 = System.nanoTime();
        try {
            NoiseSettings ns = r.gen.generatorSettings().value().noiseSettings().clampToHeightAccessor(r.chunk.getHeightAccessorForGeneration());
            ExactBiomePipeline.Model m = ExactBiomePipeline.model(r.rs, r.gen.generatorSettings().value(), r.gen.getBiomeSource(), ns.minY(), ns.height());
            if (!m.unsupported().isEmpty()) {
                g.biomeDisabled = "nicht unterstuetzt: " + m.unsupported();
                LOG.info("GPU-Biome: Vanilla ({})", g.biomeDisabled);
                return;
            }
            boolean codegen = codegen();
            ExactBiomePipeline pipe = new ExactBiomePipeline(vk, m, ns.minY(), ns.height(), batch, ExactTerrainPipeline.JAR, codegen);
            // Selbsttest gegen Vanillas Resolver
            int n = Math.min(SELF_TEST.length, pipe.capacity);
            int[] chunkXZ = new int[n * 2];
            for (int c = 0; c < n; c++) {
                chunkXZ[c * 2] = SELF_TEST[c][0] * 16;
                chunkXZ[c * 2 + 1] = SELF_TEST[c][1] * 16;
            }
            pipe.run(n, chunkXZ);
            for (int c = 0; c < n; c++) {
                int[] ref = vanillaBiomes(r.gen, r.rs, new ChunkPos(SELF_TEST[c][0], SELF_TEST[c][1]), pipe);
                if (!java.util.Arrays.equals(ref, pipe.copyChunk(c))) {
                    pipe.close();
                    g.biomeDisabled = "Selbsttest";
                    LOG.error("GPU-Biome weichen im Selbsttest von Vanilla ab, nutze Vanilla");
                    return;
                }
            }
            g.biomePipe = pipe;
            LOG.info("GPU-Biome bereit in {} ms, Selbsttest bitgleich", (System.nanoTime() - t0) / 1_000_000);
        } catch (Throwable e) {
            g.biomeDisabled = e.toString();
            LOG.error("GPU-Biome nicht moeglich, nutze Vanilla", e);
        }
    }

    private void biomeToVanilla(BiomeRequest r) {
        CompletableFuture<ChunkAccess> f;
        BYPASS.set(true);
        try {
            f = r.gen.createBiomes(r.rs, r.blender, r.sm, r.chunk);
        } catch (Throwable e) {
            r.result.completeExceptionally(e);
            return;
        } finally {
            BYPASS.set(false);
        }
        f.whenComplete((c, e) -> {
            if (e != null) r.result.completeExceptionally(e);
            else r.result.complete(c);
        });
    }

    private void throttle(double ms) throws InterruptedException {
        double share = share();
        if (share > 0 && share < 1.0) {
            long sleep = (long) (ms * (1.0 / share - 1.0) * 1_000_000L);
            if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
        }
    }

    private void loop() {
        try {
            vk = openDevice();
            if (vk == null) {
                failed = true;
                drainToVanilla();
                return;
            }
            ArrayDeque<Job> later = new ArrayDeque<>();
            while (running) {
                Job job = later.isEmpty() ? queue.poll(200, TimeUnit.MILLISECONDS) : later.poll();
                if (job == null) {
                    logStats(false);
                    continue;
                }
                Gen g;
                synchronized (gens) {
                    g = gens.get(job.rs());
                }
                if (job instanceof BiomeRequest br) {
                    biomeJobs(g, br, later);
                    continue;
                }
                Request first = (Request) job;
                if (g.pipe == null && g.disabled == null) setup(g, first);
                ExactTerrainPipeline pipe = g.pipe;
                if (pipe == null) {
                    toVanilla(first);
                    continue;
                }
                List<Request> batchList = new ArrayList<>();
                batchList.add(first);
                long deadline = System.nanoTime() + WAIT_NS;
                while (batchList.size() < pipe.capacity) {
                    Job r = queue.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (r == null) break;
                    if (r instanceof Request tr && tr.rs == first.rs) batchList.add(tr);
                    else later.add(r);
                }
                runBatch(g, pipe, batchList);
                logStats(false);
            }
        } catch (InterruptedException e) {
            // Server stoppt
        } catch (Throwable e) {
            LOG.error("GPU-Terrain abgebrochen, ab jetzt Vanilla", e);
            failed = true;
        } finally {
            drainToVanilla();
            logStats(true);
            synchronized (gens) {
                for (Gen g : gens.values()) {
                    if (g.pipe != null) g.pipe.close();
                    if (g.biomePipe != null) g.biomePipe.close();
                }
                gens.clear();
            }
            if (vk != null) vk.close();
        }
    }

    private @Nullable GpuCompute openDevice() {
        try {
            CudaCompute c = CudaCompute.create();
            if (c == null) {
                LOG.warn("GPU-Terrain aus: CUDA nicht nutzbar ({}), Vanilla rechnet", CudaCompute.lastError);
                return null;
            }
            LOG.info("GPU-Terrain auf {} (CUDA, GPU-Anteil {}, Stapel {} Chunks{})", c, share() <= 0 ? "0 % = aus, Vanilla rechnet"
                    : Math.round(share() * 100) + " %", batch, VERIFY ? ", Pruefmodus" : "");
            return c;
        } catch (Throwable e) {
            LOG.warn("GPU-Terrain aus: GPU nicht nutzbar ({})", e.toString());
            return null;
        }
    }

    /** Laufzeit-Codegen per NVRTC (fehlt NVRTC, liefert groupKernel null und der Interpreter rechnet). */
    private static boolean codegen() {
        return Boolean.parseBoolean(System.getProperty("pyridium.gpuWorldgenCodegen", "true"));
    }

    private void drainToVanilla() {
        Job r;
        while ((r = queue.poll()) != null) {
            if (r instanceof Request tr) toVanilla(tr);
            else biomeToVanilla((BiomeRequest) r);
        }
    }

    /** Vanilla-buildTerrain fuer eine Anfrage (Mixin umgangen), Ergebnis in deren Future. */
    private void toVanilla(Request r) {
        fallbackChunks.incrementAndGet();
        CompletableFuture<ChunkAccess> f;
        BYPASS.set(true);
        try {
            f = r.gen.buildTerrain(r.chunk, r.blender, r.rs, r.sm, r.bm, r.carverRegion, possible(r.window));
        } catch (Throwable e) {
            r.result.completeExceptionally(e);
            return;
        } finally {
            BYPASS.set(false);
        }
        f.whenComplete((c, e) -> {
            if (e != null) r.result.completeExceptionally(e);
            else r.result.complete(c);
        });
    }

    private static Set<Holder<Biome>> possible(Holder<Biome>[] window) {
        return new HashSet<>(List.of(window));
    }

    /** Pipeline fuer den Generator bauen und gegen Vanilla pruefen; bei Fehler Vanilla fuer immer. */
    private void setup(Gen g, Request r) {
        LevelHeightAccessor heights = r.chunk.getHeightAccessorForGeneration();
        NoiseGeneratorSettings settings = r.gen.generatorSettings().value();
        NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(heights);
        g.minY = ns.minY();
        g.height = ns.height();
        g.noise = ns;
        long t0 = System.nanoTime();
        try {
            WorldGenerationContext ctx = new WorldGenerationContext(r.gen, heights);
            ExactTerrainPipeline.Model m = ExactTerrainPipeline.model(r.rs, settings, r.gen.getBiomeSource(), ctx, g.minY, g.height);
            if (!m.unsupported().isEmpty()) {
                g.disabled = "nicht unterstuetzt: " + m.unsupported();
                LOG.info("GPU-Terrain fuer {}: Vanilla ({})", settingsName(r), g.disabled);
                return;
            }
            boolean codegen = codegen();
            ExactTerrainPipeline pipe;
            try {
                pipe = new ExactTerrainPipeline(vk, m, g.minY, g.height, heights.getMaxY(), r.rs.seed(), batch, ExactTerrainPipeline.JAR, codegen);
            } catch (Throwable e) {
                if (!codegen) throw e;
                LOG.warn("Shader-Codegen nicht moeglich ({}), nutze Interpreter", e.toString());
                pipe = new ExactTerrainPipeline(vk, m, g.minY, g.height, heights.getMaxY(), r.rs.seed(), batch, ExactTerrainPipeline.JAR, false);
            }
            String bad = selfTest(pipe, r, heights);
            if (bad != null) {
                pipe.close();
                g.disabled = "Selbsttest: " + bad;
                LOG.error("GPU-Terrain fuer {} weicht von Vanilla ab ({}), nutze Vanilla", settingsName(r), bad);
                return;
            }
            g.pipe = pipe;
            LOG.info("GPU-Terrain fuer {} bereit in {} ms, Selbsttest bitgleich", settingsName(r), (System.nanoTime() - t0) / 1_000_000);
        } catch (Throwable e) {
            g.disabled = e.toString();
            LOG.error("GPU-Terrain fuer {} nicht moeglich, nutze Vanilla", settingsName(r), e);
        }
    }

    private static String settingsName(Request r) {
        return r.gen.generatorSettings().unwrapKey().map(k -> k.identifier().toString()).orElse("?");
    }

    // ---------------------------------------------------------------------------------------
    // Stapel
    // ---------------------------------------------------------------------------------------

    private void runBatch(Gen g, ExactTerrainPipeline pipe, List<Request> reqs) throws InterruptedException {
        int qh = pipe.qh;
        var win = pipe.windows();
        pipe.clearBeard();
        List<Request> ok = new ArrayList<>(reqs.size());
        int[] chunkXZ = new int[reqs.size() * 2];
        for (Request r : reqs) {
            int c = ok.size();
            boolean known = true;
            for (int iz = 0; iz < 6 && known; iz++) for (int ix = 0; ix < 6 && known; ix++) for (int y = 0; y < qh; y++) {
                int bi = pipe.biomeIndex(r.window[y + (ix + iz * 6) * qh]);
                if (bi < 0) {
                    known = false;
                    break;
                }
                win.put(pipe.windowIndex(c, ix, y, iz), bi);
            }
            if (!known || (r.beard != null && (pipe.pointsUseBeardifier || !pipe.setBeard(c, r.beard)))) {
                toVanilla(r);
                continue;
            }
            if (r.beard != null) structureChunks.incrementAndGet();
            chunkXZ[c * 2] = r.chunk.getPos().getMinBlockX();
            chunkXZ[c * 2 + 1] = r.chunk.getPos().getMinBlockZ();
            ok.add(r);
        }
        if (ok.isEmpty()) return;
        pipe.run(ok.size(), chunkXZ);
        gpuMsTotal += pipe.lastMs;
        gpuChunks.addAndGet(ok.size());
        for (int c = 0; c < ok.size(); c++) {
            Request r = ok.get(c);
            int[] states = pipe.copyChunk(c);
            CompletableFuture.supplyAsync(() -> finish(g, r, states), Util.backgroundExecutor().forName("buildTerrain"))
                    .whenComplete((chunk, e) -> {
                        if (e != null) r.result.completeExceptionally(e);
                        else r.result.complete(chunk);
                    });
        }
        // Drosselung: GPU-Anteil der Wandzeit (Einstellung im Client, sonst Property)
        throttle(pipe.lastMs);
    }

    /** Hintergrund-Thread: Bloecke schreiben, Heightmaps, Fluss-Markierungen, dann Carver. */
    private ChunkAccess finish(Gen g, Request r, int[] states) {
        ChunkAccess chunk = r.chunk;
        long w0 = System.nanoTime();
        write(chunk, states, g.minY, g.height);
        writeNs.addAndGet(System.nanoTime() - w0);
        if (VERIFY) verify(r, states, g);
        var acc = (NoiseBasedChunkGeneratorAccess) (Object) r.gen;
        var material = r.gen.generatorSettings().value().materialRule().value();
        long c0 = System.nanoTime();
        try (NoiseChunk nc = acc.pyridium$createNoiseChunk(chunk, r.sm, r.blender, r.rs, g.noise)) {
            if (!SharedConstants.DEBUG_DISABLE_CARVERS) acc.pyridium$generateCarvers(chunk, r.blender, nc, r.rs, r.bm, r.carverRegion, material);
        }
        carverNs.addAndGet(System.nanoTime() - c0);
        return chunk;
    }

    /**
     * Ergebnis in den Chunk wie doFill + buildSurface (gleicher Endzustand): je Sektion Palette
     * (Reihenfolge des ersten Vorkommens) und gepackte Werte ueber Vanillas Ladepfad
     * PalettedContainer.unpack statt 98.304 setBlockState; Heightmaps aus dem obersten passenden
     * Block je Spalte; Fluss-Markierungen (Bit 31 doFill, Bit 30 MaterialSystem) in Vanillas Reihenfolge.
     */
    public static void write(ChunkAccess chunk, int[] states, int minY, int height) {
        LevelChunkSection[] sections = chunk.getSections();
        WriteBuffers wb = WRITE_BUFFERS.get();
        int[] local = wb.local;           // Zustands-ID -> Palettenindex + 1 (nach jeder Sektion zurueckgesetzt)
        List<BlockState> palette = wb.palette;
        for (int si = 0; si < sections.length; si++) {
            int y0 = chunk.getSectionYFromSectionIndex(si) * 16;
            if (y0 + 15 < minY || y0 >= minY + height) continue;
            palette.clear();
            int[] values = wb.values;
            boolean onlyAir = true;
            for (int ly = 0; ly < 16; ly++) {
                int yi = y0 + ly - minY;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int id = yi < 0 || yi >= height ? AIR_ID : states[yi * 256 + z + x * 16] & 0x3fffffff;
                    int pi = id < local.length ? local[id] - 1 : -1;
                    if (pi < 0) {
                        BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
                        palette.add(st == null ? AIR : st);
                        pi = palette.size() - 1;
                        if (id < local.length) local[id] = pi + 1;
                    }
                    if (id != AIR_ID) onlyAir = false;
                    values[(ly << 8) | (z << 4) | x] = pi;
                }
            }
            for (BlockState st : palette) {
                int id = Block.BLOCK_STATE_REGISTRY.getId(st);
                if (id < local.length) local[id] = 0;
            }
            if (onlyAir) continue;   // leer bleibt leer (wie doFill: Luft wird nie gesetzt)
            LevelChunkSection old = sections[si];
            sections[si] = new LevelChunkSection(Sections.pack(old, palette, values), old.getBiomes());
        }
        // Heightmaps: oberster Block je Spalte, der das Praedikat erfuellt (= primeHeightmaps)
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            boolean doneS = false, doneO = false;
            for (int yi = height - 1; yi >= 0 && !(doneS && doneO); yi--) {
                int id = states[yi * 256 + z + x * 16] & 0x3fffffff;
                if (id == AIR_ID) continue;
                BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (!doneS) {
                    surface.update(x, minY + yi, z, st);
                    doneS = true;
                }
                if (!doneO && st.is(net.minecraft.tags.BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP)) {
                    ocean.update(x, minY + yi, z, st);
                    doneO = true;
                }
            }
        }
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // doFill (z, x, y absteigend), danach MaterialSystem (x, z, y absteigend) wie Vanilla
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int yi = height - 1; yi >= 0; yi--)
            if ((states[yi * 256 + z + x * 16] & 0x80000000) != 0) chunk.markPosForPostProcessing(p.set(pos.getMinBlockX() + x, minY + yi, pos.getMinBlockZ() + z));
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int yi = height - 1; yi >= 0; yi--)
            if ((states[yi * 256 + z + x * 16] & 0x40000000) != 0) chunk.markPosForPostProcessing(p.set(pos.getMinBlockX() + x, minY + yi, pos.getMinBlockZ() + z));
    }

    private static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());

    /** Je Thread wiederverwendete Puffer fuer write (keine 256-KB-Allokation je Chunk). */
    private static final class WriteBuffers {
        final int[] local = new int[1 << 16];
        final int[] values = new int[4096];
        final List<BlockState> palette = new ArrayList<>();
    }

    private static final ThreadLocal<WriteBuffers> WRITE_BUFFERS = ThreadLocal.withInitial(WriteBuffers::new);

    /** Sektionen ueber Vanillas PalettedContainer.unpack bauen (Strategie der vorhandenen Sektion). */
    private static final class Sections {
        private static final java.lang.reflect.Field STRATEGY;
        private static final java.lang.reflect.Method CONFIG;

        static {
            try {
                STRATEGY = net.minecraft.world.level.chunk.PalettedContainer.class.getDeclaredField("strategy");
                STRATEGY.setAccessible(true);
                CONFIG = net.minecraft.world.level.chunk.Strategy.class.getDeclaredMethod("getConfigurationForPaletteSize", int.class);
                CONFIG.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("unchecked")
        static net.minecraft.world.level.chunk.PalettedContainer<BlockState> pack(LevelChunkSection old, List<BlockState> palette, int[] values) {
            try {
                var strategy = (net.minecraft.world.level.chunk.Strategy<BlockState>) STRATEGY.get(old.getStates());
                var config = (net.minecraft.world.level.chunk.Configuration) CONFIG.invoke(strategy, palette.size());
                int bits = config.bitsInStorage();
                java.util.Optional<java.util.stream.LongStream> data = java.util.Optional.empty();
                if (bits > 0) {
                    var storage = new net.minecraft.util.SimpleBitStorage(bits, values.length);
                    for (int i = 0; i < values.length; i++) storage.set(i, values[i]);
                    data = java.util.Optional.of(java.util.Arrays.stream(storage.getRaw()));
                }
                return net.minecraft.world.level.chunk.PalettedContainer.unpack(strategy,
                        new net.minecraft.world.level.chunk.PalettedContainerRO.PackedData<>(List.copyOf(palette), data)).getOrThrow();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Vergleich mit Vanilla (Selbsttest beim Start, optional jeder Chunk)
    // ---------------------------------------------------------------------------------------

    /** Vanilla doFill + buildSurface auf einem frischen ProtoChunk (ohne Strukturen/Blending). */
    private ProtoChunk vanillaReference(Request proto, ChunkPos pos, BiomeResolver biomes, BiomeManager bm, Set<Holder<Biome>> possible,
                                        LevelHeightAccessor heights, NoiseSettings ns, Beardifier beardifier) {
        RegistryAccess reg = server.registryAccess();
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heights, PalettedContainerFactory.create(reg), null);
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        chunk.fillBiomesFromNoise(biomes);
        var acc = (NoiseBasedChunkGeneratorAccess) (Object) proto.gen;
        NoiseGeneratorSettings settings = proto.gen.generatorSettings().value();
        DensityVolume vol = new DensityVolume(16, ns.height(), 16, pos.getMinBlockX(), ns.minY(), pos.getMinBlockZ());
        try (NoiseChunk nc = new NoiseChunk(proto.rs, beardifier, settings, acc.pyridium$fluidPicker().get(), Blender.empty(), vol)) {
            acc.pyridium$doFill(nc, chunk);
            acc.pyridium$buildSurface(chunk, nc, proto.rs, bm, possible, settings.materialRule().value());
        }
        return chunk;
    }

    /** Unterschiede zwischen zwei Chunks (Bloecke, WG-Heightmaps, Post-Processing); null = gleich. */
    public static @Nullable String compare(ChunkAccess gpu, ChunkAccess ref, int minY, int height) {
        long bad = 0;
        String first = null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ChunkPos pos = ref.getPos();
        for (int yi = 0; yi < height; yi++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            p.set(pos.getMinBlockX() + x, minY + yi, pos.getMinBlockZ() + z);
            BlockState a = gpu.getBlockState(p), b = ref.getBlockState(p);
            if (a != b) {
                if (first == null) first = p.toShortString() + " gpu=" + a + " vanilla=" + b;
                bad++;
            }
        }
        for (Heightmap.Types t : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG))
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                if (gpu.getHeight(t, x, z) != ref.getHeight(t, x, z)) {
                    if (first == null) first = "Heightmap " + t + " " + x + "/" + z;
                    bad++;
                }
        var pa = gpu.getPostProcessing();
        var pb = ref.getPostProcessing();
        for (int i = 0; i < pa.length; i++) {
            if (!java.util.Objects.equals(pa[i] == null || pa[i].isEmpty() ? null : pa[i], pb[i] == null || pb[i].isEmpty() ? null : pb[i])) {
                if (first == null) {
                    var la = pa[i] == null ? it.unimi.dsi.fastutil.shorts.ShortList.of() : pa[i];
                    var lb = pb[i] == null ? it.unimi.dsi.fastutil.shorts.ShortList.of() : pb[i];
                    int k = 0;
                    while (k < Math.min(la.size(), lb.size()) && la.getShort(k) == lb.getShort(k)) k++;
                    first = "Post-Processing Sektion " + i + " (gpu " + la.size() + ", vanilla " + lb.size() + ", erste Differenz bei " + k
                            + (k < la.size() ? " gpu=" + ProtoChunk.unpackOffsetCoordinates(la.getShort(k), ref.getSectionYFromSectionIndex(i), ref.getPos()).toShortString() : "")
                            + (k < lb.size() ? " vanilla=" + ProtoChunk.unpackOffsetCoordinates(lb.getShort(k), ref.getSectionYFromSectionIndex(i), ref.getPos()).toShortString() : "") + ")";
                }
                bad++;
            }
        }
        return bad == 0 ? null : bad + " Abweichungen, erste " + first + " in " + pos;
    }

    private static final int[][] SELF_TEST = {{0, 0}, {37, -12}, {-250, 410}, {1234, -777}, {-4000, -9000}, {20000, 3000},
            {-61000, 52000}, {150000, -120000}};

    /** Acht feste Chunks auf GPU und mit Vanilla; Biome direkt aus der Biomquelle. */
    private @Nullable String selfTest(ExactTerrainPipeline pipe, Request proto, LevelHeightAccessor heights) {
        NoiseSettings ns = proto.gen.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        // einmal je Quart aufloesen: GPU-Fenster und Vanilla-Referenz sehen dieselben Biome
        BiomeResolver raw = proto.gen.getBiomeSource().createUncachedResolver(proto.rs);
        Map<Long, Holder<Biome>> memo = new java.util.HashMap<>();
        BiomeResolver biomes = (qx, qy, qz) -> memo.computeIfAbsent(BlockPos.asLong(qx, qy, qz), k -> raw.getNoiseBiome(qx, qy, qz));
        BiomeManager bm = new BiomeManager(biomes, BiomeManager.obfuscateSeed(proto.rs.seed()));
        int n = Math.min(SELF_TEST.length, pipe.capacity), qh = pipe.qh, qy0 = QuartPos.fromBlock(ns.minY());
        int[] chunkXZ = new int[n * 2];
        var win = pipe.windows();
        pipe.clearBeard();
        List<Set<Holder<Biome>>> possible = new ArrayList<>();
        List<Beardifier> beards = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            ChunkPos pos = new ChunkPos(SELF_TEST[c][0], SELF_TEST[c][1]);
            chunkXZ[c * 2] = pos.getMinBlockX();
            chunkXZ[c * 2 + 1] = pos.getMinBlockZ();
            // jeder zweite Chunk mit kuenstlicher Struktur (alle Anpassungsarten + Kreuzung)
            Beardifier bd = c % 2 == 1 && !pipe.pointsUseBeardifier ? syntheticBeardifier(pos) : Beardifier.EMPTY;
            beards.add(bd);
            if (bd != Beardifier.EMPTY && !pipe.setBeard(c, ExactBeard.encode(bd))) return "Beardifier-Puffer zu klein";
            Set<Holder<Biome>> set = new HashSet<>();
            int qx0 = QuartPos.fromBlock(pos.getMinBlockX()) - 1, qz0 = QuartPos.fromBlock(pos.getMinBlockZ()) - 1;
            for (int iz = 0; iz < 6; iz++) for (int ix = 0; ix < 6; ix++) for (int y = 0; y < qh; y++) {
                Holder<Biome> b = biomes.getNoiseBiome(qx0 + ix, qy0 + y, qz0 + iz);
                set.add(b);
                int bi = pipe.biomeIndex(b);
                if (bi < 0) return "Biom " + b.getRegisteredName() + " fehlt in der GPU-Tabelle";
                win.put(pipe.windowIndex(c, ix, y, iz), bi);
            }
            possible.add(set);
        }
        pipe.run(n, chunkXZ);
        for (int c = 0; c < n; c++) {
            ChunkPos pos = new ChunkPos(SELF_TEST[c][0], SELF_TEST[c][1]);
            ProtoChunk ref = vanillaReference(proto, pos, biomes, bm, possible.get(c), heights, ns, beards.get(c));
            ProtoChunk gpu = new ProtoChunk(pos, UpgradeData.EMPTY, heights, PalettedContainerFactory.create(server.registryAccess()), null);
            gpu.setPersistedStatus(ChunkStatus.BIOMES);
            gpu.fillBiomesFromNoise(biomes);
            write(gpu, pipe.copyChunk(c), ns.minY(), ns.height());
            String d = compare(gpu, ref, ns.minY(), ns.height());
            if (d != null) return d;
        }
        return null;
    }

    private static Beardifier syntheticBeardifier(ChunkPos pos) {
        int x = pos.getMinBlockX(), z = pos.getMinBlockZ();
        List<Beardifier.Rigid> rigids = new ArrayList<>();
        net.minecraft.world.level.levelgen.structure.BoundingBox any = null;
        var kinds = net.minecraft.world.level.levelgen.structure.TerrainAdjustment.values();
        for (int i = 0; i < kinds.length; i++) {
            var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(x - 6 + i * 5, 55 + i * 4, z - 4 + i * 4,
                    x + 2 + i * 5, 63 + i * 4, z + 5 + i * 4);
            rigids.add(new Beardifier.Rigid(box, kinds[i], i - 1));
            any = any == null ? box : net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulating(any, box);
        }
        var junction = new net.minecraft.world.level.levelgen.structure.pools.JigsawJunction(x + 9, 70, z + 11, 1,
                net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.Projection.RIGID);
        any = net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulating(any,
                new net.minecraft.world.level.levelgen.structure.BoundingBox(new BlockPos(x + 9, 70, z + 11)));
        return new Beardifier(List.copyOf(rigids), List.of(junction), any.inflatedBy(24));
    }

    /** Pruefmodus: Chunk-Ergebnis (vor Carvern) gegen Vanilla mit denselben Biomen. */
    private void verify(Request r, int[] states, Gen g) {
        LevelHeightAccessor heights = r.chunk.getHeightAccessorForGeneration();
        BiomeResolver biomes = r.bm::getNoiseBiomeAtQuart;
        ProtoChunk ref = vanillaReference(r, r.chunk.getPos(), biomes, r.bm, possible(r.window), heights, g.noise, r.beardifier);
        String d = compare(r.chunk, ref, g.minY, g.height);
        if (d != null) {
            verifyBad.incrementAndGet();
            LOG.error("GPU-Terrain Pruefung: {}", d);
        }
    }

    private void logStats(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastStats < 60_000_000_000L) return;
        lastStats = now;
        long n = gpuChunks.get();
        if (n == 0 && fallbackChunks.get() == 0) return;
        if (n > 0) LOG.info("GPU-Terrain CPU je Chunk: Schreiben {} ms, Carver {} ms", String.format("%.3f", writeNs.get() / 1e6 / n),
                String.format("%.3f", carverNs.get() / 1e6 / n));
        if (biomeChunks.get() > 0)
            LOG.info("GPU-Biome: {} Chunks ({} ms/Chunk GPU-Zeit)", biomeChunks.get(), String.format("%.3f", biomeMsTotal / biomeChunks.get()));
        LOG.info("GPU-Terrain: {} Chunks auf der GPU ({} mit Strukturen, {} ms/Chunk GPU-Zeit), {} ueber Vanilla{}", n,
                structureChunks.get(), n == 0 ? "-" : String.format("%.3f", gpuMsTotal / n), fallbackChunks.get(),
                VERIFY ? ", Pruefung: " + verifyBad.get() + " abweichend" : "");
    }
}
