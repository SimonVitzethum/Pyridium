package simon.pyridium.client.world;

import com.mojang.logging.LogUtils;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import simon.pyridium.client.gpu.Native;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.ExactBiomes;
import simon.pyridium.worldgen.exact.ExactLodPipeline;
import simon.pyridium.worldgen.exact.ExactLodSurface;
import simon.pyridium.worldgen.exact.ExactOres;
import simon.pyridium.worldgen.exact.ExactPlan;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;
import simon.pyridium.worldgen.exact.ExactVegetation;

/**
 * Fernbereich wie Distant Horizons, aber vollstaendig auf der GPU: Pyrits Generator-Thread ruft
 * {@link #generate} fuer jeden Auftrag; je LOD-Stufe rechnet eine {@link ExactLodPipeline}
 * die exakte Vanilla-Dichte und die Biome und schreibt die Voxel direkt in Pyrits Puffer.
 * Chunks in geladenen Minecraft-Spalten ueberschreibt danach der Sektions-Kern mit den echten Bloecken.
 */
public final class LodGenerator {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile LodGenerator active;
	private static MemorySegment upcall;

	private final CudaCompute gpu;
	private final RandomState rs;
	private final NoiseGeneratorSettings settings;
	private final ExactBiomes biomes;
	private final int[] biomeTable;
	private final int[] lpBase;
	private final int minY, height;
	private final long seed;
	private final Map<Integer, ExactLodPipeline> pipes = new HashMap<>();
	private ExactLodPipeline.Shared shared;
	private final Map<Integer, String> failed = new HashMap<>();
	private long generated, calls;
	private final long[] levelCols = new long[MAX_LOD + 1], levelJobs = new long[MAX_LOD + 1];
	private final double[] levelMs = new double[MAX_LOD + 1];
	private double ms2d, msBand, ms3d, msTotal;
	private final long[] skipped = new long[MAX_LOD + 1];
	private final int sea;
	private int debugBand = 12;
	private double ms;

	private ExactLodSurface surface;
	private int[] surfaceTable;
	private int[] oreTable;
	private net.minecraft.world.level.levelgen.WorldGenerationContext genCtx;
	private boolean surfaceTried;
	private net.minecraft.server.MinecraftServer server;
	private LodStructures structures;
	private boolean structuresTried;

	private LodGenerator(CudaCompute gpu, RandomState rs, NoiseGeneratorSettings settings, ExactBiomes biomes, int minY, int height, int sea, long seed) {
		this.gpu = gpu;
		this.rs = rs;
		this.settings = settings;
		this.biomes = biomes;
		this.minY = minY;
		this.height = height;
		this.seed = seed;
		this.biomeTable = biomeTable(biomes);
		this.sea = sea;
		this.lpBase = new int[]{sea, 0, 0, 0, BlockPalette.attrOf(Blocks.WATER.defaultBlockState()), BlockPalette.attrOf(Blocks.STONE.defaultBlockState()),
			BlockPalette.attrOf(Blocks.DEEPSLATE.defaultBlockState()), BlockPalette.attrOf(Blocks.BEDROCK.defaultBlockState()), (int)seed, (int)(seed >>> 32)};
	}

	/** Upcall-Stub fuer libpyridium (einmalig) */
	public static synchronized MemorySegment upcallStub() {
		if (upcall == null) {
			try {
				var mh = MethodHandles.lookup().findStatic(LodGenerator.class, "generate",
					MethodType.methodType(int.class, long.class, int.class, long.class, long.class, int.class, long.class));
				upcall = Linker.nativeLinker().upcallStub(mh, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
					ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG), Arena.global());
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		return upcall;
	}

	/**
	 * Beim Start des integrierten Servers (vor "Loading terrain"): Palette, Plaene und Kerne aller
	 * Stufen im Hintergrund vorbereiten.
	 */
	public static void prepareAsync(net.minecraft.server.MinecraftServer server) {
		if (!(server instanceof IntegratedServer is)) {
			return;
		}
		Thread t = new Thread(() -> {
			BlockPalette.prepare();
			setup(is);
		}, "Pyridium Vorbereitung");
		t.setDaemon(true);
		t.start();
	}

	/** Exakter Generator des integrierten Servers */
	public static synchronized void setup(IntegratedServer server) {
		if (active != null) {
			return;
		}
		if (server == null) {
			LOGGER.info("Pyridium: kein integrierter Server - Fernbereich ohne Generator (Mehrspieler)");
			return;
		}
		try {
			ServerLevel level = server.overworld();
			ServerChunkCache cc = level.getChunkSource();
			if (!(cc.getGenerator() instanceof NoiseBasedChunkGenerator gen)) {
				LOGGER.info("Pyridium: Generator {} wird nicht unterstuetzt", cc.getGenerator().getClass().getSimpleName());
				return;
			}
			CudaCompute gpu = CudaCompute.create();
			if (gpu != null) {
				gpu.fastMath = !Boolean.getBoolean("pyridium.lodExactMath");
			}
			if (gpu == null) {
				LOGGER.warn("Pyridium: CUDA fuer den Generator nicht verfuegbar");
				return;
			}
			NoiseGeneratorSettings settings = gen.generatorSettings().value();
			ExactBiomes eb = new ExactBiomes(gen.getBiomeSource());
			if (!eb.unsupported.isEmpty()) {
				LOGGER.warn("Pyridium: Biome nicht exakt ({})", eb.unsupported);
				return;
			}
			LodGenerator g = new LodGenerator(gpu, cc.randomState(), settings, eb, level.getMinY(), level.getHeight(), settings.seaLevel(), level.getSeed());
			g.genCtx = new net.minecraft.world.level.levelgen.WorldGenerationContext(gen, level);
			g.server = server;
			active = g;
			// Plaene und Kerne aller Stufen im Hintergrund vorbereiten (grob zuerst: die sieht man
			// beim Beitritt als Erstes); Generator-Auftraege warten nur auf ihre eigene Stufe
			Thread warm = new Thread(() -> {
				// gebraucht werden die Stufen bis zum Ring um die Kamera, der die Sichtweite deckt (2 Chunks breit);
				// mit einer Stufe Reserve. Weitere entstehen bei Bedarf.
				long viewBlocks = Integer.getInteger("pyridium.viewChunks", 1024) * 16L;
				int top = 1;
				while (top < MAX_LOD && (64L << top) < viewBlocks) {
					top++;
				}
				for (int lod = Math.min(top + 1, MAX_LOD); lod >= 0; lod--) {
					synchronized (g) {
						if (active != g) {
							return;
						}
						g.pipeline(lod);
					}
				}
			}, "Pyridium LOD-Vorbereitung");
			warm.setDaemon(true);
			warm.start();
			long benchSeconds = Long.getLong("pyridium.lodBench", 0L);
			if (benchSeconds > 0) {
				// Terrain-Benchmark: nur der Generator, mit GPU-Anteil (duty), siehe tools/bench_terrain.sh
				double duty = Math.clamp(Double.parseDouble(System.getProperty("pyridium.lodBenchDuty", "1")), 0.05, 1.0);
				Thread bt = new Thread(() -> g.benchLoop(benchSeconds, duty), "Pyridium LOD-Bench");
				bt.setDaemon(true);
				bt.start();
			} else if (Boolean.getBoolean("pyridium.prefetch")) {
				Thread pf = new Thread(g::prefetchLoop, "Pyridium Vorausgenerator");
				pf.setDaemon(true);
				pf.setPriority(Thread.NORM_PRIORITY - 1);
				pf.start();
			}
			LOGGER.info("Pyridium: Fernbereich aus dem exakten GPU-Generator ({} Biome)", eb.biomes.size());
		} catch (Exception e) {
			LOGGER.error("Pyridium: Generator nicht einrichtbar", e);
		}
	}

	public static void shutdown() {
		LodGenerator g = active;
		active = null;
		if (g != null) {
			g.stop = true;
			synchronized (g) {
				// CudaCompute entlaedt alle Module selbst (die Pipelines nicht zusaetzlich schliessen)
				g.pipes.clear();
				if (g.structures != null) {
					g.structures.close();
				}
				g.structures = null;
				g.gpu.close();
			}
		}
	}

	/**
	 * Aus Pyrits Generator-Thread. keys: Host-Zeiger auf PyrChunkKey[count] (x, y, z, lod), known:
	 * Host-Zeiger auf uint[count] (1 = echte Daten, nichts zu tun). Rueckgabe 1 = erledigt.
	 */
	private static int generate(long keys, int count, long voxels, long counts, int capacity, long known) {
		LodGenerator g = active;
		if (g == null) {
			return 0;
		}
		try {
			g.callbackWaiting = true;
			synchronized (g) {
				g.callbackWaiting = false;
				g.notifyAll();
				long t0 = System.nanoTime();
				boolean ok = g.run(keys, count, voxels, counts, capacity, known);
				g.msTotal += (System.nanoTime() - t0) / 1e6;
				return ok ? 1 : 0;
			}
		} catch (Throwable t) {
			LOGGER.error("Pyridium: LOD-Generator", t);
			active = null;
			return 0;
		}
	}

	// ---------------------------------------------------------------------------------------
	// Vorausgenerator: eigener Thread, rechnet Pyrits LOD-Baum um die Kamera voraus (dieselbe
	// Verfeinerungsregel wie der Planer) in einen VRAM-Vorrat. Pyrits Auftraege kopieren dann nur
	// noch; Generator und Renderer laufen entkoppelt.
	// ---------------------------------------------------------------------------------------

	private static final int ST_DATA = 1, ST_EMPTY = 2, ST_NODATA = 3;

	/** Vorratseintrag: Voxel ab off (Bytes im Ring) oder nur das Wissen leer/nicht leer */
	private static final class Entry {
		int state;
		long off;
		int count;
		long used;
	}

	private static final int STAGE_N = Integer.getInteger("pyridium.prefetchBatch", 384);
	/** Obergrenze der Stufen (Pyrit: max_lod 20); die tatsaechlich noetigen folgen der Sichtweite */
	private static final int MAX_LOD = 24;
	private static final int STAGE_CAP = 16384;
	/**
	 * Hoehlen werden nur bis zu diesem Abstand erzeugt (Bloecke, Richtwert 64 Chunks); weiter weg genuegt das
	 * Hoehenfeld. Keine feste Stufe: ein Voxel der Stufe l liegt bei 1 Pixel Zielgroesse etwa in
	 * pixelsPerBlock * 2^l Bloecken Entfernung; die Entscheidung folgt Aufloesung und FOV zur Laufzeit.
	 */
	private static final double CAVE_BLOCKS = Double.parseDouble(System.getProperty("pyridium.caveBlocks", "1024"));
	/** vom Renderer je Frame gesetzt (Hoehe / (2 tan(fov/2))) */
	public static volatile double pixelsPerBlock = 700;

	/** Stufen, die je nach Kamera noch Hoehlen brauchen koennten (grobe Untergrenze 128 px/Block) */
	private static boolean mayNeedCaves(int lod) {
		return (1L << lod) * 128.0 <= CAVE_BLOCKS;
	}

	/** Hoehenfeld statt 3D-Dichte fuer diese Stufe, gemessen an der aktuellen Kamera */
	private static boolean heightfield(int lod) {
		return (1L << lod) * pixelsPerBlock > CAVE_BLOCKS;
	}
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Entry> cache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final java.util.TreeMap<Long, Long> ringLive = new java.util.TreeMap<>(); // off -> Schluessel
	private long[] stageVox, stageCounts, stageCountsHost, ring, hostCounts, hostOne;
	private long ringSize, ringHead;
	private volatile boolean callbackWaiting;
	private volatile boolean stop;
	private long prefetched, hits, misses;
	private int missLog;

	private static long key(int lod, int x, int y, int z) {
		return (long)lod << 58 | ((long)(y & 0xfff)) << 46 | ((long)(x & 0x7fffff)) << 23 | (z & 0x7fffff);
	}

	/**
	 * Der Generator ist statisch: Pyrits Auftraege werden direkt gerechnet, ohne Vorrat und Vorausplanung (bei >= 20k Chunks/s
	 * ist Bewegung kein Problem mehr). Nur kleine Zaehler-Puffer; Ring und Zwischenpuffer gibt es nur fuer -Dpyridium.prefetch=true
	 * und den Benchmark.
	 */
	private void allocBasic() {
		if (this.hostCounts != null) {
			return;
		}
		this.hostCounts = this.gpu.readbackBuffer(4096 * 4L);
		this.hostOne = this.gpu.readbackBuffer(4096 * 4L);
	}

	private void allocPrefetch() {
		this.allocBasic();
		if (this.stageVox != null) {
			return;
		}
		long[] mem = new long[2];
		// Vorrat nach freiem VRAM (andere Prozesse, z. B. ein Training, teilen sich die GPU): 6 %, 64..384 MiB
		long avail = this.gpu.freeMemory();
		long free = Long.getLong("pyridium.prefetchMiB", Math.clamp(avail / 16 >> 20, 64L, 384L)) << 20;
		this.ringSize = free;
		this.ring = this.gpu.deviceBuffer(this.ringSize);
		this.stageVox = this.gpu.deviceBuffer((long)STAGE_N * STAGE_CAP * 16L);
		this.stageCounts = this.gpu.deviceBuffer(STAGE_N * 4L);
		this.stageCountsHost = this.gpu.readbackBuffer(STAGE_N * 4L);
		LOGGER.info("Pyridium: Vorausgenerator mit {} MiB Vorrat", this.ringSize >> 20);
	}

	/** Platz im Ring; ueberschriebene Eintraege verlieren ihre Daten (bleiben als "nicht leer" bekannt) */
	private long ringAlloc(long bytes) {
		if (bytes > this.ringSize) {
			return -1;
		}
		if (this.ringHead + bytes > this.ringSize) {
			this.ringHead = 0;
		}
		long lo = this.ringHead, hi = lo + bytes;
		var floor = this.ringLive.floorEntry(lo);
		if (floor != null) {
			Entry e = this.cache.get((long)floor.getValue());
			if (e == null || e.state != ST_DATA || floor.getKey() + e.count * 16L <= lo) {
				floor = null;
			}
		}
		java.util.List<Long> dead = new java.util.ArrayList<>(this.ringLive.subMap(lo, true, hi, false).keySet());
		if (floor != null) {
			dead.add(floor.getKey());
		}
		for (long off : dead) {
			Long k = this.ringLive.remove(off);
			if (k != null) {
				Entry e = this.cache.get((long)k);
				if (e != null && e.state == ST_DATA && e.off == off) {
					e.state = ST_NODATA;
				}
			}
		}
		this.ringHead = hi;
		return lo;
	}

	private void dropData(long k, Entry e) {
		if (e.state == ST_DATA) {
			this.ringLive.remove(e.off);
			e.state = ST_NODATA;
		}
	}

	/** Vorausgenerator-Thread */
	/**
	 * Terrain-Benchmark (-Dpyridium.lodBench=Sekunden, -Dpyridium.lodBenchDuty=GPU-Anteil 0..1): erzeugt den LOD-Baum um eine
	 * synthetische Kamera wie der Vorausgenerator, ohne Renderer. Ist die Sicht voll, wandert die Kamera weiter (Dauerlast wie im
	 * Flug). Der GPU-Anteil wird durch Pausen nach jedem Stapel erreicht (Pause = Stapelzeit * (1/duty - 1)). Ausgabe: Chunks/s.
	 */
	private void benchLoop(long seconds, double duty) {
		try {
			long viewBlocks = Integer.getInteger("pyridium.viewChunks", 1024) * 16L;
			int top = 1;
			while (top < MAX_LOD && (64L << top) < viewBlocks) {
				top++;
			}
			// warten, bis die Stufen vorbereitet sind (Vorwaermthread)
			long wait0 = System.nanoTime();
			while (!this.stop && active == this) {
				boolean ready = true;
				synchronized (this) {
					for (int l = 0; l <= Math.min(top + 1, MAX_LOD); l++) {
						if (this.pipes.get(l) == null && !this.failed.containsKey(l)) {
							ready = false;
						}
					}
				}
				if (ready || System.nanoTime() - wait0 > 180_000_000_000L) {
					break;
				}
				Thread.sleep(200);
			}
			double refineK = pixelsPerBlock / Math.max(0.1, Double.parseDouble(System.getProperty("pyridium.voxelPx", "1.0")));
			double camX = 0, camZ = 0;
			long t0 = System.nanoTime(), lastLog = t0, deadline = t0 + seconds * 1_000_000_000L;
			long chunks0 = this.prefetched, lastChunks = chunks0, batches = 0, fills = 0;
			double busyMs = 0;
			LOGGER.info("Pyridium: LOD-Bench Start: {} s, GPU-Anteil {}, Sicht {} Bloecke, refine_k {}, oben L{}", seconds, duty, viewBlocks,
				String.format("%.0f", refineK), top);
			while (!this.stop && active == this && System.nanoTime() < deadline) {
				double[] c = {5, 0, 2, viewBlocks, refineK, this.minY, this.minY + this.height, top, camX, 120, camZ};
				java.util.List<int[]> todo;
				synchronized (this) {
					todo = this.plan(c);
				}
				if (todo.isEmpty()) {
					fills++;
					camX += viewBlocks / 2.0;
					camZ += viewBlocks / 3.0;
					continue;
				}
				long b0 = System.nanoTime();
				synchronized (this) {
					this.prefetchBatch(todo);
				}
				long b1 = System.nanoTime();
				busyMs += (b1 - b0) / 1e6;
				batches++;
				if (duty < 0.999) {
					Thread.sleep((long)((b1 - b0) / 1e6 * (1.0 / duty - 1.0)));
				}
				long now = System.nanoTime();
				if (now - lastLog > 5_000_000_000L) {
					LOGGER.info("Pyridium: LOD-Bench {} s: {} Chunks, {} Chunks/s (letzte 5 s {} Chunks/s), {} Stapel, Sicht {}x voll",
						(now - t0) / 1_000_000_000L, this.prefetched - chunks0,
						String.format("%.0f", (this.prefetched - chunks0) / ((now - t0) / 1e9)),
						String.format("%.0f", (this.prefetched - lastChunks) / ((now - lastLog) / 1e9)), batches, fills);
					lastLog = now;
					lastChunks = this.prefetched;
				}
			}
			double secs = (System.nanoTime() - t0) / 1e9;
			long total = this.prefetched - chunks0;
			LOGGER.info("Pyridium: LOD-Bench ERGEBNIS: {} Chunks in {} s = {} Chunks/s; Generator beschaeftigt {}% der Zeit (Stapel {}), GPU-Anteil {}",
				total, String.format("%.1f", secs), String.format("%.0f", total / secs), String.format("%.1f", 100.0 * busyMs / (secs * 1000.0)), batches, duty);
		} catch (InterruptedException e) {
			// beendet
		} catch (Throwable t) {
			LOGGER.error("Pyridium: LOD-Bench", t);
		}
	}

	private void prefetchLoop() {
		MemorySegment info = Arena.global().allocate(11 * 8L, 8);
		long lastLog = 0;
		while (!this.stop && active == this) {
			try {
				if (Native.planInfo(info.address()) != 0 || Double.isNaN(info.getAtIndex(ValueLayout.JAVA_DOUBLE, 8))) {
					Thread.sleep(50);
					continue;
				}
				double[] c = new double[11];
				for (int i = 0; i < 11; i++) {
					c[i] = info.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
				}
				java.util.List<int[]> todo;
				synchronized (this) {
					todo = this.plan(c);
				}
				if (System.nanoTime() - lastLog > 2_000_000_000L) {
					lastLog = System.nanoTime();
					LOGGER.info("Pyridium: Voraus: {} offen (erste {}), Vorrat {} Eintraege, {} gerechnet, Kamera {} {} {}, refine_k {}, Sicht {}, oben L{}",
						todo.size(), todo.isEmpty() ? "-" : java.util.Arrays.toString(todo.get(0)), this.cache.size(), this.prefetched,
						(int)c[8], (int)c[9], (int)c[10], String.format("%.1f", c[4]), (int)c[3], (int)c[7]);
				}
				if (todo.isEmpty()) {
					Thread.sleep(20);
					continue;
				}
				synchronized (this) {
					// Give the waiting render-world callback the monitor. Waiting
					// releases it and consumes no CPU while CUDA work is running.
					while (this.callbackWaiting && !this.stop && active == this) this.wait();
					if (active != this) {
						return;
					}
					this.prefetchBatch(todo);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				LOGGER.error("Pyridium: Vorausgenerator", t);
				return;
			}
		}
	}

	/**
	 * Pyrits Planer nachgebildet (world_plan.zig visit): von der groebsten Stufe abwaerts; ein
	 * Knoten wird verfeinert, wenn er nicht leer ist und naeher als refine_k * 2^lod liegt.
	 * Liefert fehlende Knoten, grob vor fein, nah vor fern.
	 */
	private java.util.List<int[]> plan(double[] c) {
		int log2 = (int)c[0], viewChunks = (int)c[2], top = (int)c[7];
		double viewDist = c[3], refineK = c[4], yMin = c[5], yMax = c[6];
		double[] cam = {c[8], c[9], c[10]};
		java.util.List<int[]> todo = new java.util.ArrayList<>();
		double s = Math.scalb(1.0, log2 + top);
		int cx = (int)Math.floor(cam[0] / s), cz = (int)Math.floor(cam[2] / s);
		int y0 = (int)Math.floor(yMin / s), y1 = (int)Math.floor((yMax - 1) / s);
		java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
		for (int z = cz - viewChunks; z <= cz + viewChunks; z++) {
			for (int x = cx - viewChunks; x <= cx + viewChunks; x++) {
				for (int y = y0; y <= y1; y++) {
					stack.push(new int[]{top, x, y, z});
				}
			}
		}
		long now = this.calls + this.prefetched;
		while (!stack.isEmpty()) {
			int[] k = stack.pop();
			int lod = k[0];
			double cs = Math.scalb(1.0, log2 + lod);
			double ylo = k[2] * cs;
			if (!(ylo + cs > yMin && ylo < yMax)) {
				continue;
			}
			double d2 = 0;
			for (int a = 0; a < 3; a++) {
				double lo = k[1 + a] * cs;
				double v = Math.max(Math.max(lo - cam[a], 0), cam[a] - (lo + cs));
				d2 += v * v;
			}
			double dist = Math.sqrt(d2);
			if (dist > viewDist) {
				continue;
			}
			Entry e = this.cache.get(key(lod, k[1], k[2], k[3]));
			if (e == null) {
				todo.add(new int[]{lod, k[1], k[2], k[3], 0, (int)Math.min(dist, Integer.MAX_VALUE)});
				continue;
			}
			e.used = now;
			if (e.state == ST_EMPTY) {
				continue;
			}
			if (lod > 0 && dist < refineK * (1L << lod)) {
				for (int i = 0; i < 8; i++) {
					stack.push(new int[]{lod - 1, k[1] * 2 + (i & 1), k[2] * 2 + ((i >> 1) & 1), k[3] * 2 + ((i >> 2) & 1)});
				}
			}
		}
		todo.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[5], b[5]));
		// weit entfernte Eintraege gelegentlich vergessen
		if (this.cache.size() > 200_000) {
			var it = this.cache.long2ObjectEntrySet().fastIterator();
			while (it.hasNext()) {
				var en = it.next();
				if (now - en.getValue().used > 2000) {
					this.dropData(en.getLongKey(), en.getValue());
					it.remove();
				}
			}
		}
		return todo.size() > STAGE_N ? todo.subList(0, STAGE_N) : todo;
	}

	/** Ein Stapel voraus: in den Zwischenpuffer rechnen, dann dicht in den Ring */
	private void prefetchBatch(java.util.List<int[]> todo) throws Exception {
		this.allocPrefetch();
		java.util.List<int[]> reqs = new java.util.ArrayList<>(todo.size());
		for (int i = 0; i < todo.size(); i++) {
			int[] t = todo.get(i);
			if (this.pipeline(t[0]) == null) {
				continue;
			}
			reqs.add(new int[]{t[0], t[1], t[2], t[3], reqs.size()});
		}
		LodStructures st = LodStructures.KERNEL_MODE ? this.structures() : null;
		if (st != null && !reqs.isEmpty()) {
			// Vorrat nur mit fertigen Strukturen fuellen (der Vorrat wird nicht neu gerechnet); der Rest folgt, sobald die Regionen da sind
			boolean[] ready = st.ensure(reqs);
			java.util.List<int[]> keep = new java.util.ArrayList<>(reqs.size());
			for (int i = 0; i < reqs.size(); i++) {
				if (ready[i]) {
					int[] r = reqs.get(i);
					keep.add(new int[]{r[0], r[1], r[2], r[3], keep.size()});
				}
			}
			reqs = keep;
		}
		if (reqs.isEmpty()) {
			if (st != null) {
				this.wait(10);   // auf Strukturregionen warten, ohne die Sperre zu halten
			}
			return;
		}
		this.gpu.zeroAsync(this.stageCounts[0], reqs.size() * 4L);
		this.genKeys(reqs, this.stageVox[0], this.stageCounts[0], STAGE_CAP);
		this.gpu.downloadAsync(this.stageCountsHost[1], this.stageCounts[0], reqs.size() * 4L);
		this.gpu.sync();
		MemorySegment hc = MemorySegment.ofAddress(this.stageCountsHost[1]).reinterpret(reqs.size() * 4L);
		for (int[] r : reqs) {
			int n = hc.get(ValueLayout.JAVA_INT, r[4] * 4L);
			long k = key(r[0], r[1], r[2], r[3]);
			Entry e = this.cache.get(k);
			if (e == null) {
				e = new Entry();
				this.cache.put(k, e);
			}
			e.used = this.calls + this.prefetched;
			if (n == 0) {
				e.state = ST_EMPTY;
			} else if (n > STAGE_CAP) {
				e.state = ST_NODATA; // zu gross fuer den Vorrat: Pyrits Auftrag rechnet selbst
			} else {
				long off = this.ringAlloc(n * 16L);
				if (off < 0) {
					e.state = ST_NODATA;
					continue;
				}
				this.gpu.copyAsync(this.ring[0] + off, this.stageVox[0] + (long)r[4] * STAGE_CAP * 16L, n * 16L);
				e.state = ST_DATA;
				e.off = off;
				e.count = n;
				this.ringLive.put(off, k);
			}
			this.prefetched++;
		}
		this.gpu.sync();
	}

	/** Pyrits Auftrag: aus dem Vorrat kopieren, Fehlendes sofort rechnen */
	private boolean run(long keys, int count, long voxels, long counts, int capacity, long known) throws Exception {
		if (this.calls < 3) {
			LOGGER.info("Pyridium: LOD-Auftrag {} mit {} Chunks", this.calls, count);
		}
		MemorySegment k = MemorySegment.ofAddress(keys).reinterpret(count * 16L);
		MemorySegment kn = MemorySegment.ofAddress(known).reinterpret(count * 4L);
		java.util.List<int[]> miss = new java.util.ArrayList<>();
		java.util.List<int[]> all = new java.util.ArrayList<>();
		this.allocBasic();
		long hostOne = this.hostOne[1];
		int nHit = 0;
		for (int i = 0; i < count; i++) {
			if (kn.get(ValueLayout.JAVA_INT, i * 4L) != 0) {
				continue;
			}
			int x = k.get(ValueLayout.JAVA_INT, i * 16L), y = k.get(ValueLayout.JAVA_INT, i * 16L + 4), z = k.get(ValueLayout.JAVA_INT, i * 16L + 8);
			int lod = k.get(ValueLayout.JAVA_INT, i * 16L + 12);
			if (lod > MAX_LOD) {
				return false;
			}
			int[] r = {lod, x, y, z, i};
			all.add(r);
			long ck = key(lod, x, y, z);
			Entry e = this.cache.get(ck);
			if (e != null && e.state == ST_EMPTY) {
				this.hits++;
				continue;
			}
			if (e != null && e.state == ST_DATA && e.count <= capacity) {
				this.gpu.copyAsync(voxels + (long)i * capacity * 16L, this.ring[0] + e.off, e.count * 16L);
				MemorySegment.ofAddress(hostOne + nHit * 4L).reinterpret(4).set(ValueLayout.JAVA_INT, 0, e.count);
				this.gpu.uploadAsync(counts + i * 4L, hostOne + nHit * 4L, 4);
				nHit++;
				this.hits++;
				// Daten bleiben: Pyrit fordert Chunks nach Verdraengung oder Aenderung erneut an
				continue;
			}
			this.misses++;
			miss.add(r);
			if (this.missLog < 30 && this.prefetched > 0) {
				this.missLog++;
				Entry pe = lod < MAX_LOD ? this.cache.get(key(lod + 1, x >> 1, y >> 1, z >> 1)) : null;
				LOGGER.info("Pyridium: Fehlend L{} {} {} {}: eigener Zustand {}, Eltern {}", lod, x, y, z, e == null ? "-" : e.state,
					pe == null ? "-" : pe.state);
			}
		}
		for (int[] r : miss) {
			if (this.pipeline(r[0]) == null) {
				return false;
			}
		}
		LodStructures stc = LodStructures.KERNEL_MODE ? this.structures() : null;
		if (stc != null && !miss.isEmpty()) {
			stc.await(miss, 300);   // hoechstens kurz warten; fehlt eine Region danach, entsteht der Chunk ohne sie
		}
		long t0 = System.nanoTime();
		this.genKeys(miss, voxels, counts, capacity);
		this.gpu.sync();
		// leer / nicht leer merken (der Vorausgenerator steigt daran ab)
		if (!miss.isEmpty() && count <= 4096) {
			this.gpu.downloadAsync(this.hostCounts[1], counts, count * 4L);
			this.gpu.sync();
			MemorySegment hc = MemorySegment.ofAddress(this.hostCounts[1]).reinterpret(count * 4L);
			for (int[] r : miss) {
				int c = hc.get(ValueLayout.JAVA_INT, r[4] * 4L);
				Entry e = this.cache.computeIfAbsent(key(r[0], r[1], r[2], r[3]), q -> new Entry());
				e.state = c == 0 ? ST_EMPTY : ST_NODATA;
			}
		}
		this.calls++;
		this.ms += (System.nanoTime() - t0) / 1e6;
		if (this.calls % 50 == 0 || this.calls <= 3) {
			this.logStats();
		}
		return true;
	}

	private void logStats() {
		StringBuilder sb = new StringBuilder();
		for (int l = 0; l <= MAX_LOD; l++) {
			if (this.levelCols[l] > 0) {
				sb.append(String.format(" L%d: %d Sp/%d Ch (%d leer)", l, this.levelCols[l], this.levelJobs[l], this.skipped[l]));
			}
		}
		if (this.structures != null) {
			LOGGER.info("Pyridium: LOD-Strukturen gesamt: {} ms, {} Regionen, {} Starts, {} Teile", (int)this.structures.msTotal, this.structures.regionsGenerated, this.structures.startsLast, this.structures.piecesLast);
		}
		LOGGER.info("Pyridium: LOD-Generator {} Auftraege, Vorrat {} Treffer/{} fehlend, {} voraus; {} Spalten (2D {} ms, Band {} ms, 3D {} ms, im Auftrag {} ms, Aufruf gesamt {} ms);{}",
			this.calls, this.hits, this.misses, this.prefetched, this.generated, String.format("%.0f", this.ms2d), String.format("%.0f", this.msBand),
			String.format("%.0f", this.ms3d), String.format("%.0f", this.ms), String.format("%.0f", this.msTotal), sb);
	}

	/** Chunks (je lod, x, y, z, Ausgabeindex) rechnen: Voxel nach voxels[out * capacity], Zahl nach counts[out]. Endet ohne Warten. */
	private void genKeys(java.util.List<int[]> reqs, long voxels, long counts, int capacity) throws Exception {
		if (reqs.isEmpty()) {
			return;
		}
		// Strukturen der beruehrten Regionen bereitstellen (GPU, siehe LodStructures)
		LodStructures st = this.structures();

		// nach Stufe und Spalte gruppieren
		Map<Integer, Map<Long, Integer>> cols = new HashMap<>();
		Map<Integer, java.util.List<int[]>> jobs = new HashMap<>();
		for (int[] r : reqs) {
			int lod = r[0], x = r[1], y = r[2], z = r[3];
			long ck = ((long)x << 32) ^ (z & 0xffffffffL);
			Map<Long, Integer> m = cols.computeIfAbsent(lod, l -> new HashMap<>());
			Integer ci = m.get(ck);
			if (ci == null) {
				ci = m.size();
				m.put(ck, ci);
			}
			jobs.computeIfAbsent(lod, l -> new java.util.ArrayList<>()).add(new int[]{ci, y, r[4], x, z});
		}
		// Stufen mit genug Platz laufen am Stueck: erst alle 2D-Schritte, ein Warten, dann alle
		// 3D-Schritte, ein Warten. Die GPU bekommt so lange Warteschlangen statt Hin und Her.
		record Batch(int lod, ExactLodPipeline p, int c0, int n, int[] xz, java.util.List<int[]> all) {
		}
		java.util.List<Batch> fast = new java.util.ArrayList<>(), slow = new java.util.ArrayList<>();
		for (var e : cols.entrySet()) {
			int lod = e.getKey();
			ExactLodPipeline p = this.pipeline(lod);
			if (p == null) {
				throw new IllegalStateException("LOD-Stufe " + lod + " nicht verfuegbar");
			}
			int s = 1 << lod;
			java.util.List<int[]> all = jobs.get(lod);
			int nCols = e.getValue().size();
			int[] colXZ = new int[nCols * 2];
			for (int[] j : all) {
				colXZ[j[0] * 2] = j[3] * 32 * s;
				colXZ[j[0] * 2 + 1] = j[4] * 32 * s;
			}
			for (int c0 = 0; c0 < nCols; c0 += p.colCapacity) {
				int n = Math.min(p.colCapacity, nCols - c0);
				Batch bt = new Batch(lod, p, c0, n, java.util.Arrays.copyOfRange(colXZ, c0 * 2, (c0 + n) * 2), all);
				(nCols <= p.colCapacity ? fast : slow).add(bt);
			}
		}
		long q0 = System.nanoTime();
		for (Batch bt : fast) {
			bt.p.columnsAsync(bt.n, bt.xz);
		}
		// kein Warten zwischen 2D und 3D: das Band liegt auf der GPU, alles laeuft im selben Stream
		long q1 = System.nanoTime();
		this.ms2d += (q1 - q0) / 1e6;
		for (Batch bt : fast) {
			this.chunksOf(bt.lod, bt.p, bt.c0, bt.n, bt.xz, bt.all, voxels, counts, capacity, true);
		}
		this.gpu.sync();
		this.ms3d += (System.nanoTime() - q1) / 1e6;
		// Rest (mehr Spalten als Platz): Schritt fuer Schritt
		for (Batch bt : slow) {
			long r0 = System.nanoTime();
			bt.p.columns(bt.n, bt.xz);
			long r1 = System.nanoTime();
			this.ms2d += (r1 - r0) / 1e6;
			this.chunksOf(bt.lod, bt.p, bt.c0, bt.n, bt.xz, bt.all, voxels, counts, capacity, false);
			this.ms3d += (System.nanoTime() - r1) / 1e6;
		}
		for (Batch bt : fast) {
			this.levelCols[bt.lod] += bt.n;
			this.generated += bt.n;
		}
		for (Batch bt : slow) {
			this.levelCols[bt.lod] += bt.n;
			this.generated += bt.n;
		}
	}

	/** Chunks der Spalten [c0, c0+n) als Kandidaten; das Band waehlt die GPU (wg_lod_select), async: letzter Lauf ohne Warten */
	private void chunksOf(int lod, ExactLodPipeline p, int c0, int n, int[] xz, java.util.List<int[]> all, long voxels, long counts, int capacity,
		boolean async) {
		java.util.List<int[]> sel = new java.util.ArrayList<>();
		for (int[] j : all) {
			if (j[0] >= c0 && j[0] < c0 + n) {
				sel.add(j);
			}
		}
		boolean hf = heightfield(lod);
		int cap = p.capacityFor(hf);
		for (int j0 = 0; j0 < sel.size(); j0 += cap) {
			int nj = Math.min(cap, sel.size() - j0);
			int[] jd = new int[nj * 4];
			for (int q = 0; q < nj; q++) {
				int[] j = sel.get(j0 + q);
				jd[q * 4] = j[0] - c0;
				jd[q * 4 + 1] = j[1];
				jd[q * 4 + 2] = j[2];
			}
			// Host-Puffer der Stufe werden je Lauf neu beschrieben: nur der letzte darf offen bleiben
			if (async && j0 + nj >= sel.size()) {
				p.chunksAsync(nj, jd, voxels, counts, capacity, hf);
			} else {
				p.chunks(nj, jd, voxels, counts, capacity, hf);
			}
			this.levelJobs[lod] += nj;
		}
	}

	private ExactLodPipeline pipeline(int lod) {
		ExactLodPipeline p = this.pipes.get(lod);
		if (p != null || this.failed.containsKey(lod) || System.nanoTime() < this.oomCooldown) {
			return p;
		}
		try {
			long t0 = System.nanoTime();
			ExactPlan plan2 = ExactLodPipeline.plan2d(this.rs, this.settings, lod);
			// ferne Stufen: Hoehenfeld ohne 3D-Dichte (Hoehlen sind nur in der Naehe sichtbar)
			ExactPlan plan3 = !mayNeedCaves(lod) ? null : ExactLodPipeline.plan3d(this.rs, this.settings, lod);
			if (!plan2.unsupported.isEmpty() || (plan3 != null && !plan3.unsupported.isEmpty())) {
				throw new IllegalStateException("nicht unterstuetzt: " + plan2.unsupported + (plan3 == null ? "" : plan3.unsupported));
			}
			if (this.shared == null) {
				// gemeinsame Arbeitspuffer aller Stufen
				// Arbeitsspeicher nach freiem VRAM: mehr Chunks je Lauf = bessere Auslastung
				this.shared = new ExactLodPipeline.Shared(this.gpu, Long.getLong("pyridium.lodScratchMiB", Math.clamp(this.gpu.freeMemory() / 8 >> 20, 32L, 768L)) << 20, 48L << 20, 512);
			}
			boolean codegen = !Boolean.getBoolean("pyridium.lodInterpreter");
			this.ensureSurface();
			LodStructures st = this.structures();
			p = new ExactLodPipeline(this.gpu, plan2, plan3, this.biomes, this.biomeTable, lod, this.minY, this.height, this.shared, this.lpBase,
				ExactTerrainPipeline.JAR, codegen, this.surface, this.surfaceTable, this.oreTable, !Boolean.getBoolean("pyridium.lodNoCarve"),
					st == null || !LodStructures.KERNEL_MODE ? null : st.view(), st == null ? 0 : st.maxStep);
			this.pipes.put(lod, p);
			LOGGER.info("Pyridium: LOD-Stufe {} (Schritt {}{}): 2D {} Aufgaben, 3D {} Aufgaben/{} floats, {} Spalten, {} Chunks je Lauf, {} ms", lod,
				1 << lod, plan3 == null ? ", Hoehenfeld" : "", plan2.tasks.size(), plan3 == null ? 0 : plan3.tasks.size(), plan3 == null ? 0 : plan3.scratchFloats,
				p.colCapacity, p.chunkCapacity,
				(System.nanoTime() - t0) / 1000000);
			return p;
		} catch (Exception e) {
			if (String.valueOf(e).contains("OUT_OF_MEMORY")) {
				// Grafikspeicher ist knapp (Renderer, Server-Generator, andere Programme): spaeter erneut versuchen
				// statt die Stufe dauerhaft zu verlieren; kleinere Arbeitspuffer fuer den Neuversuch
				LOGGER.warn("Pyridium: LOD-Stufe {}: kein Grafikspeicher, neuer Versuch spaeter ({})", lod, e.getMessage());
				this.shared = null;
				this.oomCooldown = System.nanoTime() + 5_000_000_000L;
				return null;
			}
			LOGGER.error("Pyridium: LOD-Stufe {} nicht verfuegbar", lod, e);
			this.failed.put(lod, String.valueOf(e));
			return null;
		}
	}

	/** frueheste Zeit (nanoTime) fuer den naechsten Versuch nach Speichermangel */
	private long oomCooldown;

	/** Strukturen im LOD (einmal je Welt aufgebaut; null, wenn nicht verfuegbar) */
	private LodStructures structures() {
		if (!this.structuresTried) {
			this.structuresTried = true;
			if (this.server != null) {
				this.structures = LodStructures.create(this.gpu, this.server, this.rs, this.settings, this.biomes, this.seed, this.minY, this.height, this.sea);
			}
		}
		return this.structures;
	}

	/** Echte Oberflaechenregeln (Datapack) fuer alle Stufen: einmal aufbauen; bei Fehlern bleibt die Biomtabelle */
	private void ensureSurface() {
		if (this.surfaceTried) {
			return;
		}
		this.surfaceTried = true;
		if (Boolean.getBoolean("pyridium.lodNoSurfaceRules")) {
			return;
		}
		try {
			ExactLodSurface sf = new ExactLodSurface(this.gpu, this.rs, this.settings, this.genCtx, this.biomes, this.minY, this.height, this.seed);
			if (!sf.unsupported.isEmpty()) {
				LOGGER.warn("Pyridium: Oberflaechenregeln im LOD nicht exakt ({}): Biomtabelle", sf.unsupported);
				return;
			}
			ExactOres ores = new ExactOres(this.biomes, this.genCtx);
			this.oreTable = ores.count > 0 ? ores.table : null;
			it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet all = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet(sf.stateIds);
			for (int id : ores.stateIds) {
				all.add(id);
			}
			int[] stateIds = all.toIntArray();
			LOGGER.info("Pyridium: LOD mit {} Erz-Features aus den Biomen", ores.count);
			int n = stateIds.length, nb = Math.max(1, this.biomes.biomes.size());
			int[] t = new int[2 + 3 * n + nb];
			t[0] = n;
			t[1] = nb;
			for (int i = 0; i < n; i++) {
				BlockState st = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(stateIds[i]);
				t[2 + i * 3] = stateIds[i];
				t[3 + i * 3] = st == null ? 0 : BlockPalette.attrOf(st);
				// nur Gras faerbt sich nach dem Biom (verschneites Gras bleibt unveraendert)
				t[4 + i * 3] = st != null && st.is(Blocks.GRASS_BLOCK) && !st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY) ? 1 : 0;
			}
			for (int b = 0; b < this.biomes.biomes.size(); b++) {
				t[2 + 3 * n + b] = BlockPalette.attr(0, this.biomes.biomes.get(b).value().getGrassColor(0, 0) & 0xffffff) & 0xffff0000;
			}
			this.surface = sf;
			this.surfaceTable = t;
			LOGGER.info("Pyridium: LOD mit echten Oberflaechenregeln ({} Block-Zustaende)", n);
		} catch (Exception e) {
			LOGGER.warn("Pyridium: Oberflaechenregeln im LOD nicht verfuegbar, Biomtabelle", e);
		}
	}

	// --- Biome -> Oberflaeche (Naeherung der Oberflaechenregeln) ---------------

	private static int[] biomeTable(ExactBiomes eb) {
		int n = Math.max(1, eb.biomes.size());
		int[] t = new int[n * 8];
		for (int i = 0; i < eb.biomes.size(); i++) {
			Holder<Biome> h = eb.biomes.get(i);
			String id = h.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
			Biome b = h.value();
			int grass = b.getGrassColor(0, 0) & 0xffffff, foliage = b.getFoliageColor() & 0xffffff;
			// Keine Biomnamen-Heuristik: Oberflaechenmaterial kommt aus den SurfaceRules des Datapacks (wg_lod_surface.cu). Diese Tabelle ist
			// nur der neutrale Rueckfall (Standardgestein), falls die Regeln nicht kompilierbar waeren.
			BlockState top = Blocks.STONE.defaultBlockState(), filler = top, under = top;
			BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState();
			float trees = 0f;
			boolean tintTop = false, tintLeaves = true;
			// Baeume aus den Features des Datapacks (VEGETAL_DECORATION) statt aus dem Namen; ueberschreibt die Namensschaetzung
			ExactVegetation veg = ExactVegetation.of(b);
			trees = veg.treesPerBlock();
			if (veg.leaves() != null) {
				leaves = veg.leaves();
				if (veg.log() != null) {
					log = veg.log();
				}
				net.minecraft.world.level.block.Block lb = leaves.getBlock();
				tintLeaves = true;
				foliage = b.getFoliageColor() & 0xffffff;
				if (lb == Blocks.BIRCH_LEAVES) {
					foliage = 0x80A755; // Vanilla: feste Birkenfarbe
				} else if (lb == Blocks.SPRUCE_LEAVES) {
					foliage = 0x619961; // Vanilla: feste Fichtenfarbe
				} else if (lb == Blocks.MANGROVE_LEAVES) {
					foliage = 0x8DB127;
				} else if (lb == Blocks.CHERRY_LEAVES || lb == Blocks.PALE_OAK_LEAVES || lb == Blocks.AZALEA_LEAVES || lb == Blocks.FLOWERING_AZALEA_LEAVES) {
					tintLeaves = false;
				}
			}
			int o = i * 8;
			t[o] = tint(BlockPalette.attrOf(top), tintTop ? grass : -1);
			t[o + 1] = BlockPalette.attrOf(filler);
			t[o + 2] = BlockPalette.attrOf(under);
			t[o + 3] = tint(BlockPalette.attrOf(leaves), tintLeaves ? foliage : -1);
			t[o + 4] = Float.floatToRawIntBits(trees);
			t[o + 5] = BlockPalette.attrOf(log);
			t[o + 6] = 0;
		}
		return t;
	}

	private static int tint(int attr, int rgb) {
		if (rgb < 0 || attr == 0) {
			return attr;
		}
        return BlockPalette.attr(attr & 0xffff, rgb);
	}
}
