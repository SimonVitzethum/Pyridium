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
import simon.pyridium.worldgen.exact.ExactPlan;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;

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
	private final long[] levelCols = new long[16], levelJobs = new long[16];
	private final double[] levelMs = new double[16];
	private double ms2d, msBand, ms3d, msTotal;
	private final long[] skipped = new long[16];
	private final int sea;
	private int debugBand = 12;
	private double ms;

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
			BlockPalette.attrOf(Blocks.DEEPSLATE.defaultBlockState()), BlockPalette.attrOf(Blocks.BEDROCK.defaultBlockState()), (int)seed};
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
			active = g;
			// Plaene und Kerne aller Stufen im Hintergrund vorbereiten (grob zuerst: die sieht man
			// beim Beitritt als Erstes); Generator-Auftraege warten nur auf ihre eigene Stufe
			Thread warm = new Thread(() -> {
				for (int lod = 12; lod >= 0; lod--) {
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
			if (!Boolean.getBoolean("pyridium.noPrefetch")) {
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
	private static final int STAGE_CAP = 16384;
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

	private void allocPrefetch() {
		if (this.stageVox != null) {
			return;
		}
		long[] mem = new long[2];
		long free = Long.getLong("pyridium.prefetchMiB", 384L) << 20;
		this.ringSize = free;
		this.ring = this.gpu.deviceBuffer(this.ringSize);
		this.stageVox = this.gpu.deviceBuffer((long)STAGE_N * STAGE_CAP * 16L);
		this.stageCounts = this.gpu.deviceBuffer(STAGE_N * 4L);
		this.stageCountsHost = this.gpu.readbackBuffer(STAGE_N * 4L);
		this.hostCounts = this.gpu.readbackBuffer(4096 * 4L);
		this.hostOne = this.gpu.readbackBuffer(4096 * 4L);
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
				// Pyrits Auftraege haben Vorrang: kurz Platz machen
				while (this.callbackWaiting) {
					Thread.onSpinWait();
				}
				synchronized (this) {
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
		if (reqs.isEmpty()) {
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
		MemorySegment k = MemorySegment.ofAddress(keys).reinterpret(count * 16L);
		MemorySegment kn = MemorySegment.ofAddress(known).reinterpret(count * 4L);
		java.util.List<int[]> miss = new java.util.ArrayList<>();
		java.util.List<int[]> all = new java.util.ArrayList<>();
		this.allocPrefetch();
		long hostOne = this.hostOne[1];
		int nHit = 0;
		for (int i = 0; i < count; i++) {
			if (kn.get(ValueLayout.JAVA_INT, i * 4L) != 0) {
				continue;
			}
			int x = k.get(ValueLayout.JAVA_INT, i * 16L), y = k.get(ValueLayout.JAVA_INT, i * 16L + 4), z = k.get(ValueLayout.JAVA_INT, i * 16L + 8);
			int lod = k.get(ValueLayout.JAVA_INT, i * 16L + 12);
			if (lod > 14) {
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
				Entry pe = lod < 14 ? this.cache.get(key(lod + 1, x >> 1, y >> 1, z >> 1)) : null;
				LOGGER.info("Pyridium: Fehlend L{} {} {} {}: eigener Zustand {}, Eltern {}", lod, x, y, z, e == null ? "-" : e.state,
					pe == null ? "-" : pe.state);
			}
		}
		for (int[] r : miss) {
			if (this.pipeline(r[0]) == null) {
				return false;
			}
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
		if (this.calls % 50 == 0) {
			this.logStats();
		}
		return true;
	}

	private void logStats() {
		StringBuilder sb = new StringBuilder();
		for (int l = 0; l < 15; l++) {
			if (this.levelCols[l] > 0) {
				sb.append(String.format(" L%d: %d Sp/%d Ch (%d leer)", l, this.levelCols[l], this.levelJobs[l], this.skipped[l]));
			}
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
		this.gpu.sync();
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

	/** Band je Spalte (nach columns) und die Chunks darin; async: letzter Lauf ohne Warten */
	private void chunksOf(int lod, ExactLodPipeline p, int c0, int n, int[] xz, java.util.List<int[]> all, long voxels, long counts, int capacity,
		boolean async) {
		int s = 1 << lod;
		long b0 = System.nanoTime();
		int[][] band = new int[n][];
		for (int c = 0; c < n; c++) {
			int[] r = p.surfaceRange(c);
			if (this.debugBand-- > 0) {
				LOGGER.info("Pyridium: Band L{} Spalte {},{}: Oberflaeche {}..{}", lod, xz[c * 2], xz[c * 2 + 1], r[0], r[1]);
			}
			int lo = r[0] - ExactLodPipeline.BAND_BELOW, hi = r[1] + ExactLodPipeline.BAND_ABOVE;
			if (r[0] < this.sea) {
				hi = Math.max(hi, this.sea + 1); // Wasseroberflaeche
			}
			band[c] = new int[]{lo, hi};
		}
		// nur Chunks im Band: exakte Dichte in ihrem eigenen y-Bereich
		java.util.List<int[]> sel = new java.util.ArrayList<>();
		for (int[] j : all) {
			if (j[0] < c0 || j[0] >= c0 + n) {
				continue;
			}
			int cy0 = j[1] * 32 * s, cy1 = cy0 + 32 * s;
			int[] b = band[j[0] - c0];
			if (cy1 > b[0] && cy0 < b[1]) {
				sel.add(j);
			} else {
				this.skipped[lod]++;
			}
		}
		this.msBand += (System.nanoTime() - b0) / 1e6;
		for (int j0 = 0; j0 < sel.size(); j0 += p.chunkCapacity) {
			int nj = Math.min(p.chunkCapacity, sel.size() - j0);
			int[] jd = new int[nj * 4];
			for (int q = 0; q < nj; q++) {
				int[] j = sel.get(j0 + q);
				jd[q * 4] = j[0] - c0;
				jd[q * 4 + 1] = j[1];
				jd[q * 4 + 2] = j[2];
			}
			// Host-Puffer der Stufe werden je Lauf neu beschrieben: nur der letzte darf offen bleiben
			if (async && j0 + nj >= sel.size()) {
				p.chunksAsync(xz, nj, jd, voxels, counts, capacity);
			} else {
				p.chunks(xz, nj, jd, voxels, counts, capacity);
			}
			this.levelJobs[lod] += nj;
		}
	}

	private ExactLodPipeline pipeline(int lod) {
		ExactLodPipeline p = this.pipes.get(lod);
		if (p != null || this.failed.containsKey(lod)) {
			return p;
		}
		try {
			long t0 = System.nanoTime();
			ExactPlan plan2 = ExactLodPipeline.plan2d(this.rs, this.settings, lod);
			ExactPlan plan3 = ExactLodPipeline.plan3d(this.rs, this.settings, lod);
			if (!plan2.unsupported.isEmpty() || !plan3.unsupported.isEmpty()) {
				throw new IllegalStateException("nicht unterstuetzt: " + plan2.unsupported + plan3.unsupported);
			}
			if (this.shared == null) {
				// gemeinsame Arbeitspuffer aller Stufen
				// Arbeitsspeicher nach freiem VRAM: mehr Chunks je Lauf = bessere Auslastung
				this.shared = new ExactLodPipeline.Shared(this.gpu, Long.getLong("pyridium.lodScratchMiB", 768L) << 20, 48L << 20, 512);
			}
			boolean codegen = !Boolean.getBoolean("pyridium.lodInterpreter");
			p = new ExactLodPipeline(this.gpu, plan2, plan3, this.biomes, this.biomeTable, lod, this.minY, this.height, this.shared, this.lpBase,
				ExactTerrainPipeline.JAR, codegen);
			this.pipes.put(lod, p);
			LOGGER.info("Pyridium: LOD-Stufe {} (Schritt {}): 2D {} Aufgaben, 3D {} Aufgaben/{} floats, {} Spalten, {} Chunks je Lauf, {} ms", lod,
				1 << lod, plan2.tasks.size(), plan3.tasks.size(), plan3.scratchFloats, p.colCapacity, p.chunkCapacity,
				(System.nanoTime() - t0) / 1000000);
			return p;
		} catch (Exception e) {
			LOGGER.error("Pyridium: LOD-Stufe {} nicht verfuegbar", lod, e);
			this.failed.put(lod, String.valueOf(e));
			return null;
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
			BlockState top = Blocks.GRASS_BLOCK.defaultBlockState(), filler = Blocks.DIRT.defaultBlockState(), under = Blocks.DIRT.defaultBlockState();
			BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState();
			float trees = 0.0005f;
			boolean tintTop = true, tintLeaves = true;
			if (id.contains("desert")) {
				top = filler = under = Blocks.SAND.defaultBlockState();
				trees = 0;
			} else if (id.contains("badlands")) {
				top = Blocks.RED_SAND.defaultBlockState();
				filler = Blocks.TERRACOTTA.defaultBlockState();
				under = Blocks.RED_SAND.defaultBlockState();
				trees = id.contains("wooded") ? 0.004f : 0;
			} else if (id.contains("beach") || id.equals("river") || id.contains("ocean")) {
				top = filler = Blocks.SAND.defaultBlockState();
				under = id.contains("deep") || id.contains("cold") ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
				if (id.contains("frozen") || id.contains("snowy")) {
					top = Blocks.SNOW_BLOCK.defaultBlockState();
				}
				trees = 0;
			} else if (id.contains("mushroom")) {
				top = Blocks.MYCELIUM.defaultBlockState();
				tintTop = false;
				trees = 0.002f;
			} else if (id.contains("peaks") || id.contains("jagged") || id.contains("frozen") || id.contains("snowy_slopes")) {
				top = Blocks.SNOW_BLOCK.defaultBlockState();
				filler = Blocks.STONE.defaultBlockState();
				trees = 0;
			} else if (id.contains("stony")) {
				top = filler = Blocks.STONE.defaultBlockState();
				trees = 0;
			} else if (id.contains("snowy") || id.contains("ice") || id.contains("grove")) {
				top = Blocks.GRASS_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, true);
				tintTop = false;
				trees = id.contains("taiga") || id.contains("grove") ? 0.02f : 0.0003f;
				leaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
				log = Blocks.SPRUCE_LOG.defaultBlockState();
				foliage = 0x619961;
			} else if (id.contains("swamp") || id.contains("mangrove")) {
				under = Blocks.MUD.defaultBlockState();
				trees = 0.01f;
				if (id.contains("mangrove")) {
					leaves = Blocks.MANGROVE_LEAVES.defaultBlockState();
					log = Blocks.MANGROVE_LOG.defaultBlockState();
					foliage = 0x8DB127;
					trees = 0.04f;
				}
			}
			if (id.contains("taiga")) {
				leaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
				log = Blocks.SPRUCE_LOG.defaultBlockState();
				foliage = 0x619961; // Vanilla: feste Fichtenfarbe
				trees = Math.max(trees, 0.025f);
				if (id.contains("old_growth")) {
					top = Blocks.PODZOL.defaultBlockState();
					tintTop = false;
				}
			} else if (id.contains("birch")) {
				leaves = Blocks.BIRCH_LEAVES.defaultBlockState();
				log = Blocks.BIRCH_LOG.defaultBlockState();
				foliage = 0x80A755; // Vanilla: feste Birkenfarbe
				trees = 0.03f;
			} else if (id.contains("dark_forest")) {
				leaves = Blocks.DARK_OAK_LEAVES.defaultBlockState();
				log = Blocks.DARK_OAK_LOG.defaultBlockState();
				trees = 0.06f;
			} else if (id.contains("pale_garden")) {
				leaves = Blocks.PALE_OAK_LEAVES.defaultBlockState();
				log = Blocks.PALE_OAK_LOG.defaultBlockState();
				tintLeaves = false;
				trees = 0.06f;
			} else if (id.contains("jungle")) {
				leaves = Blocks.JUNGLE_LEAVES.defaultBlockState();
				log = Blocks.JUNGLE_LOG.defaultBlockState();
				trees = id.contains("sparse") ? 0.01f : 0.05f;
			} else if (id.contains("savanna")) {
				leaves = Blocks.ACACIA_LEAVES.defaultBlockState();
				log = Blocks.ACACIA_LOG.defaultBlockState();
				trees = 0.003f;
			} else if (id.contains("cherry")) {
				leaves = Blocks.CHERRY_LEAVES.defaultBlockState();
				log = Blocks.CHERRY_LOG.defaultBlockState();
				tintLeaves = false;
				trees = 0.02f;
			} else if (id.contains("forest")) {
				trees = 0.035f;
			} else if (id.contains("windswept")) {
				trees = id.contains("forest") ? 0.03f : 0.004f;
			} else if (id.contains("meadow")) {
				trees = 0.001f;
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
		return (attr & 0xff) | (rgb & 0xffffff) << 8;
	}
}
