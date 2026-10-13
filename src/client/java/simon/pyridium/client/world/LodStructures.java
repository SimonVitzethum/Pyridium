package simon.pyridium.client.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.ExactBiomes;
import simon.pyridium.worldgen.exact.ExactTerrainPipeline;
import simon.pyridium.worldgen.structures.StructureCompiler;
import simon.pyridium.worldgen.structures.StructureData;
import simon.pyridium.worldgen.structures.StructureEnv;
import simon.pyridium.worldgen.structures.StructureGpu;
import simon.pyridium.worldgen.structures.StructureTables;

/**
 * Strukturen im LOD, ganz auf der GPU: die Registry (Vanilla + Datapacks aus dem Ressourcen-Manager des Servers) wird einmal zu
 * Tabellen kompiliert. Ein eigener Arbeits-Thread mit eigenem CUDA-Stream (gleicher Primaerkontext, daher teilen sich die Voxelkerne
 * die Arena ohne Kopie) erzeugt 512-Block-Regionen (Platzierung, Umgebung aus der LOD-Gelaendefunktion via {@link StructureEnv},
 * Jigsaw-Loeser) und haelt sie in der GPU-Arena: zuerst dringende Anfragen des LOD-Generators (feinste Stufe, nah zuerst), sonst
 * Regionen rund um die Kamera. Der LOD-Generator wartet nie auf die Erzeugung: {@link #ensure} liest nur den Fertig-Stand.
 * Laeuft die Arena voll, wird sie geleert; die Erzeugung ist zustandslos, dieselben Regionen entstehen identisch neu. Die Voxelkerne
 * (wg_lod_voxels.cu, wg_lod_height.cu) fragen die Arena je Spalte/Voxel ab ({@code wg_struct_query.cuh}, Anpassung ans Gelaende:
 * {@code wg_struct_adapt.cuh}).
 *
 * <p>Welche LOD-Stufen Strukturen bekommen, folgt aus der Groesse der Strukturteile ({@link #maxStep}), nicht aus einer festen Stufe.
 */
final class LodStructures implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("Pyridium");
	/** Regionskante in Chunks (512 Bloecke) */
	static final int REGION = 32;
	/** Regionen je Kante eines Erzeugungsblocks: die Jigsaw-Loeser sind latenzgebunden (ein Thread je Struktur), viele Kandidaten je Lauf amortisieren das */
	static final int SB = Integer.getInteger("pyridium.structBlock", 4);
	/** Kante eines Erzeugungsblocks in Chunks */
	static final int SBC = REGION * SB;

	final StructureGpu sg;
	final StructureEnv env;
	/** groesster Voxelschritt (Bloecke), ab dem Strukturen im Voxelkern abgefragt werden */
	final int maxStep;
	/** Reichweite der Strukturen um ihren Start-Chunk (Bloecke) */
	final int reach;
	private final CudaCompute gpu;
	/** Strukturen direkt im Voxelkern (Altpfad, kostet Durchsatz) statt als eigene Geometrieschicht */
	static final boolean KERNEL_MODE = Boolean.getBoolean("pyridium.lodStructuresInKernel");
	final LodStructureLayer layer;
	/** weitere Bahnen (eigene Stream/Arena/Thread) fuer gleichzeitige Bloecke: der Jigsaw-Loeser ist latenzgebunden */
	private final java.util.List<LodStructures> lanes = new java.util.ArrayList<>();
	private LodStructures primary = this;
	private final java.util.Set<Long> inflight;
	final StructureTables tables;
	private final Object lock = new Object();
	/** fertige Regionen (unter lock) */
	private final LongOpenHashSet done = new LongOpenHashSet();
	/** dringende Regionen: Schluessel -> Stufe (kleiner = dringender); Reihenfolge der Anfrage bleibt */
	private final LinkedHashMap<Long, Integer> urgent = new LinkedHashMap<>();
	private volatile boolean stop;
	private Thread worker;
	private int[] lastAttr;
	private boolean needReset;
	/** Kamera (x, z) fuer die Vorausplanung; NaN = unbekannt */
	private volatile double camX = Double.NaN, camZ = Double.NaN;
	private volatile double camRadius;
	/** Blickrichtung (xz, normiert) fuer die Reihenfolge der Regionen: voraus zuerst */
	volatile double dirX, dirZ;
	private int generation;
	volatile long regionsGenerated, resets, startsLast, piecesLast;
	volatile double msTotal;
	private int rectLogs, layerLogs;

	private void lock2Notify() {
		regionsGenerated++;
	}

	private LodStructures(CudaCompute gpu, StructureGpu sg, StructureEnv env, int maxStep, int reach, StructureTables tables, LodStructures first, java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes) {
		this.gpu = gpu;
		this.sg = sg;
		this.env = env;
		this.maxStep = maxStep;
		this.reach = reach;
		this.tables = tables;
		this.layer = first == null ? new LodStructureLayer(tables, biomes) : first.layer;
		this.inflight = first == null ? java.util.concurrent.ConcurrentHashMap.newKeySet() : first.inflight;
		if (first == null) {
			layer.owner = this;
		} else {
			primary = first;
		}
	}

	/** null, wenn Strukturen nicht verfuegbar sind (Fehler werden geloggt, das LOD laeuft dann ohne) */
	static LodStructures create(CudaCompute gpuMain, MinecraftServer server, RandomState rs, NoiseGeneratorSettings settings, ExactBiomes eb, long seed, int minY, int height, int sea) {
		if (Boolean.getBoolean("pyridium.lodNoStructures")) {
			return null;
		}
		try {
			CudaCompute gpu = CudaCompute.create();   // eigener Stream (gleicher Primaerkontext), unabhaengig vom LOD-Stream
			if (gpu == null) {
				return null;
			}
			long t0 = System.nanoTime();
			List<String> ids = new ArrayList<>();
			for (var h : eb.biomes) {
				ids.add(h.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains"));
			}
			StructureTables t = StructureCompiler.compile(StructureData.ofServer(server.getResourceManager()), ids, seed, minY, height, sea, 0);
			int active = prune(t);
			if (active == 0) {
				LOGGER.info("Pyridium: keine Strukturen fuer die Biome dieser Welt");
				return null;
			}
			// Groessen nach freiem VRAM: andere Prozesse teilen sich die GPU
			long free = gpuMain.freeMemory();
			double scale = Math.max(0.25, Math.min(1.0, free / (double)(1L << 30)));
			int slots = Integer.getInteger("pyridium.structSlots", Math.max(64, (int)(256 * scale) & ~31));
			int capPieces = 1 << (scale >= 0.9 ? 18 : 17);
			int capStart = 1 << 14;
			int hashCap = 1 << (scale >= 0.9 ? 19 : 18);
			int capCand = 1024;
			int envInts = (int)(16_000_000 * Math.max(0.5, scale));
			long budget = (long)(24L * (1 << 20) * Math.max(0.5, scale));
			StructureGpu sg = new StructureGpu(gpu, t, ExactTerrainPipeline.JAR, "wg_struct_envfill", capCand, capStart, capPieces, slots, hashCap, envInts);
			sg.setAttrTable(BlockPalette.ATTR);
			StructureEnv env = new StructureEnv(sg, ExactTerrainPipeline.JAR, rs, settings, eb, budget);
			sg.solveRingsAsync(env);   // Stronghold-Ringe (Biom-Ebenen auf der GPU)
			sg.sync();
			int reach = 0;
			for (int s = 0; s < t.numSets(); s++) {
				int b = s * StructureTables.SET_W;
				if (t.sets.getInt(b + StructureTables.SET_ENV_INTS) > 0) {
					reach = Math.max(reach, t.sets.getInt(b + StructureTables.SET_ENV_R) + 16);
				}
			}
			int maxStep = Integer.getInteger("pyridium.structMaxStep", featureStep(t));
			LodStructures ls = new LodStructures(gpu, sg, env, maxStep, reach, t, null, eb.biomes);
			ls.lastAttr = BlockPalette.ATTR;
			if (!KERNEL_MODE) {
				LodStructureLayer.active = ls.layer;
			}
			int nl = Integer.getInteger("pyridium.structLanes", gpuMain.freeMemory() > (3L << 30) ? 3 : gpuMain.freeMemory() > (1500L << 20) ? 2 : 1);
			for (int i = 1; i < nl; i++) {
				try {
					CudaCompute g2 = CudaCompute.create();
					StructureGpu sg2 = new StructureGpu(g2, t, ExactTerrainPipeline.JAR, "wg_struct_envfill", capCand, capStart, capPieces, slots, hashCap, envInts);
					sg2.setAttrTable(BlockPalette.ATTR);
					StructureEnv env2 = new StructureEnv(sg2, ExactTerrainPipeline.JAR, rs, settings, eb, budget);
					sg2.solveRingsAsync(env2);
					sg2.sync();
					LodStructures l2 = new LodStructures(g2, sg2, env2, maxStep, reach, t, ls, eb.biomes);
					l2.lastAttr = BlockPalette.ATTR;
					ls.lanes.add(l2);
				} catch (Throwable e) {
					LOGGER.warn("Pyridium: weitere Struktur-Bahn nicht moeglich ({})", String.valueOf(e));
					break;
				}
			}
			for (LodStructures l : ls.lanes) {
				l.start();
			}
			ls.start();
			LOGGER.info("Pyridium: LOD-Strukturen: {} Sets aktiv, Reichweite {} Bloecke, Voxelschritt <= {}, {} Slots, {} Teile, {} ms", active, reach, maxStep, slots,
				capPieces, (System.nanoTime() - t0) / 1_000_000);
			return ls;
		} catch (Throwable e) {
			LOGGER.error("Pyridium: LOD-Strukturen nicht verfuegbar", e);
			return null;
		}
	}

	/**
	 * Sets, die in dieser Welt nie etwas ergeben, abschalten (Ring-Sets: Teile fehlen noch; Sets ohne unterstuetzte Struktur im
	 * Biomuniversum). Spart Kacheln und Kandidaten.
	 */
	private static int prune(StructureTables t) {
		int active = 0;
		for (int s = 0; s < t.numSets(); s++) {
			int b = s * StructureTables.SET_W;
			boolean any = false;
			{
				int es = t.sets.getInt(b + StructureTables.SET_ENTRY_START), ec = t.sets.getInt(b + StructureTables.SET_ENTRY_COUNT);
				for (int e = 0; e < ec && !any; e++) {
					int si = t.entries.getInt((es + e) * StructureTables.ENTRY_W);
					int sb = si * StructureTables.STRUCT_W;
					if ((t.structs.getInt(sb + StructureTables.ST_FLAGS) & 1) == 0) {
						continue;
					}
					int mo = t.structs.getInt(sb + StructureTables.ST_BIOME_MASK);
					for (int w = 0; w < t.biomeWords && mo >= 0; w++) {
						if (t.biomeMasks.getInt(mo + w) != 0) {
							any = true;
							break;
						}
					}
				}
			}
			if (any) {
				active++;
			} else {
				t.sets.set(b + StructureTables.SET_ENTRY_COUNT, 0);
				t.sets.set(b + StructureTables.SET_ENV_INTS, 0);
			}
		}
		return active;
	}

	/**
	 * Groesster Voxelschritt, bei dem eine Struktur noch sichtbar wird: Zweierpotenz unter der typischen Bauteilbreite
	 * (Median der groessten waagrechten Kante aller nicht winzigen Vorlagen), begrenzt auf 2..32.
	 */
	private static int featureStep(StructureTables t) {
		List<Integer> ext = new ArrayList<>();
		for (int i = 0; i < t.numTemplates(); i++) {
			int b = i * StructureTables.TPL_W;
			int sx = t.templates.getInt(b + StructureTables.TP_SX), sy = t.templates.getInt(b + StructureTables.TP_SY), sz = t.templates.getInt(b + StructureTables.TP_SZ);
			if ((long)sx * sy * sz >= 64) {
				ext.add(Math.max(sx, sz));
			}
		}
		if (ext.isEmpty()) {
			return 8;
		}
		ext.sort(null);
		int median = ext.get(ext.size() / 2);
		return Math.max(2, Math.min(32, Integer.highestOneBit(Math.max(1, median))));
	}

	long[] view() {
		return sg.viewBuffer();
	}

	private static long key(int rx, int rz) {
		return ((long)rx << 32) ^ (rz & 0xffffffffL);
	}

	private void start() {
		worker = new Thread(this::workerLoop, "Pyridium Strukturen");
		worker.setDaemon(true);
		worker.setPriority(Thread.NORM_PRIORITY - 1);
		worker.start();
	}

	/** Kamera fuer die Vorausplanung (Bloecke); radius = Abstand, bis zu dem Regionen vorab erzeugt werden */
	void setCamera(double x, double z, double radius) {
		camX = x;
		camZ = z;
		camRadius = radius;
	}

	/**
	 * Fertig-Stand je Anfrage (lod, x, y, z, Index): true, wenn alle Regionen vorliegen, die Strukturen im Chunk beruehren koennen.
	 * Wartet nicht. Fehlende Regionen werden dringend vorgemerkt (Arbeits-Thread erzeugt sie nach Stufe, dann Anfrage-Reihenfolge).
	 */
	boolean[] ensure(List<int[]> reqs) {
		boolean[] ready = new boolean[reqs.size()];
		java.util.Arrays.fill(ready, true);
		synchronized (lock) {
			for (int i = 0; i < reqs.size(); i++) {
				int[] r = reqs.get(i);
				int s = 1 << r[0];
				if (s > maxStep) {
					continue;
				}
				long x0 = (long)r[1] * 32 * s, z0 = (long)r[3] * 32 * s, w = 32L * s;
				int rx0 = (int)Math.floorDiv(x0 - reach, SBC * 16L), rx1 = (int)Math.floorDiv(x0 + w - 1 + reach, SBC * 16L);
				int rz0 = (int)Math.floorDiv(z0 - reach, SBC * 16L), rz1 = (int)Math.floorDiv(z0 + w - 1 + reach, SBC * 16L);
				for (int rz = rz0; rz <= rz1; rz++) {
					for (int rx = rx0; rx <= rx1; rx++) {
						long k = key(rx, rz);
						if (!done.contains(k)) {
							ready[i] = false;
							urgent.merge(k, r[0], Math::min);
						}
					}
				}
			}
			if (!urgent.isEmpty()) {
				lock.notifyAll();
			}
		}
		return ready;
	}

	/** wartet hoechstens ms, bis alle Anfragen bereit sind (fuer Auftraege, die sofort Daten liefern muessen); Rueckgabe: Fertig-Stand */
	boolean[] await(List<int[]> reqs, long ms) {
		long end = System.nanoTime() + ms * 1_000_000L;
		boolean[] ready = ensure(reqs);
		while (true) {
			boolean all = true;
			for (boolean b : ready) {
				all &= b;
			}
			long left = (end - System.nanoTime()) / 1_000_000L;
			if (all || left <= 0 || stop) {
				return ready;
			}
			synchronized (lock) {
				try {
					lock.wait(Math.min(left, 20));
				} catch (InterruptedException e) {
					return ready;
				}
			}
			ready = ensure(reqs);
		}
	}

	private void workerLoop() {
		try {
			while (!stop) {
				if (BlockPalette.ATTR != lastAttr) {
					lastAttr = BlockPalette.ATTR;
					sg.setAttrTable(lastAttr);
				}
				if (needReset) {
					synchronized (lock) {
						sg.reset();
						done.clear();
						needReset = false;
						resets++;
					}
				}
				long k = nextRegion();
				if (k == Long.MIN_VALUE) {
					synchronized (lock) {
						lock.wait(40);
					}
					continue;
				}
				long t0 = System.nanoTime();
				int before = KERNEL_MODE ? 0 : 0;
				generateRect((int)(k >> 32) * SBC, (int)k * SBC, SBC, SBC);
				int[] st = sg.arenaState();
				startsLast = st[0];
				piecesLast = st[1];
				boolean hard = st[2] != 0 || st[4] != 0 || st[0] >= sg.capStart || st[1] >= sg.capPieces || st[3] >= sg.capNodes - 8;
				if (!KERNEL_MODE) {
					// Strukturschicht: Teileliste + Hoehenraster der Region zum Host (klein), Arena danach leeren
					int x0 = (int)(k >> 32) * SBC * 16, z0 = (int)k * SBC * 16;
					int cols = SBC * 16 / StructureEnv.SUB + 2;
					short[] grid = env.heightGrid(x0 - StructureEnv.SUB, z0 - StructureEnv.SUB, cols);
					int[] pieces = sg.downloadPieces(0, Math.min(st[1], sg.capPieces));
					int bc = (SBC * 16 + 2 * StructureEnv.SUB) / 48 + 1;
					byte[] bio = env.biomeGrid(x0 - StructureEnv.SUB, z0 - StructureEnv.SUB, bc);
					layer.publish(k, x0, z0, SBC * 16, pieces, grid, x0 - StructureEnv.SUB, z0 - StructureEnv.SUB, cols * 16, bio, x0 - StructureEnv.SUB, z0 - StructureEnv.SUB, bc);
					sg.reset();
					inflight.remove(k);
					if (layerLogs++ < 12) {
						LOGGER.info("Pyridium: Strukturregion {} {}: {} Starts, {} Teile, {} ms", x0, z0, st[0], st[1], (int)((System.nanoTime() - t0) / 1e6));
					}
					startsLast = 0;
					lock2Notify();
					msTotal += (System.nanoTime() - t0) / 1e6;
					continue;
				}
				synchronized (lock) {
					if (hard) {
						sg.reset();
						done.clear();
						resets++;
						LOGGER.warn("Pyridium: LOD-Strukturen: Arena voll ({} Starts, {} Teile), geleert", st[0], st[1]);
					} else {
						done.add(k);
						regionsGenerated++;
						if (st[1] > sg.capPieces * 0.8 || st[0] > sg.capStart * 0.8 || st[3] > sg.capNodes * 0.8) {
							needReset = true;
						}
					}
					urgent.remove(k);
					lock.notifyAll();
				}
				msTotal += (System.nanoTime() - t0) / 1e6;
			}
		} catch (Throwable e) {
			LOGGER.error("Pyridium: LOD-Strukturen Arbeits-Thread", e);
		}
	}

	/** dringendste fehlende Region, sonst die der Kamera naechste; Long.MIN_VALUE = nichts zu tun */
	private long nextRegion() {
		if (!KERNEL_MODE) {
			return nextLayerRegion();
		}
		synchronized (lock) {
			long best = Long.MIN_VALUE;
			int bestLod = Integer.MAX_VALUE;
			for (Map.Entry<Long, Integer> e : urgent.entrySet()) {
				if (done.contains(e.getKey())) {
					continue;
				}
				if (e.getValue() < bestLod) {
					bestLod = e.getValue();
					best = e.getKey();
				}
			}
			if (best != Long.MIN_VALUE) {
				return best;
			}
			urgent.clear();
		}
		double cx = camX, cz = camZ, rad = camRadius;
		if (Double.isNaN(cx) || rad <= 0) {
			return Long.MIN_VALUE;
		}
		int rcx = (int)Math.floor(cx / (SBC * 16.0)), rcz = (int)Math.floor(cz / (SBC * 16.0));
		int rr = (int)Math.ceil(rad / (SBC * 16.0));
		// Arena nicht ueberfordern: hoechstens ~60 % der Teile fuer Vorausregionen (ca. 200 Teile je Region)
		rr = Math.min(rr, (int)Math.sqrt(sg.capPieces * 0.6 / (250.0 * SB * SB) / Math.PI));
		long best = Long.MIN_VALUE;
		double bd = Double.MAX_VALUE;
		synchronized (lock) {
			for (int dz = -rr; dz <= rr; dz++) {
				for (int dx = -rr; dx <= rr; dx++) {
					double d = (double)dx * dx + (double)dz * dz;
					if (d > (double)rr * rr || d >= bd) {
						continue;
					}
					long k = key(rcx + dx, rcz + dz);
					if (!done.contains(k)) {
						bd = d;
						best = k;
					}
				}
			}
		}
		return best;
	}

	private long nextLayerRegion() {
		double cx = primary.camX, cz = primary.camZ, rad = primary.camRadius;
		if (Double.isNaN(cx) || rad <= 0) {
			return Long.MIN_VALUE;
		}
		int rcx = (int)Math.floor(cx / (SBC * 16.0)), rcz = (int)Math.floor(cz / (SBC * 16.0));
		int rr = (int)Math.ceil(rad / (SBC * 16.0));
		long best = Long.MIN_VALUE;
		double bd = Double.MAX_VALUE;
		double vx = primary.dirX, vz = primary.dirZ;
		for (int dz = -rr; dz <= rr; dz++) {
			for (int dx = -rr; dx <= rr; dx++) {
				double d0 = (double)dx * dx + (double)dz * dz;
				if (d0 > (double)rr * rr) {
					continue;
				}
				// Blickrichtung: Regionen voraus gelten als bis zu 40 % naeher (Kosinus zur Blickrichtung)
				double cosv = d0 > 0 ? (dx * vx + dz * vz) / Math.sqrt(d0) : 1.0;
				double d = d0 * (1.0 - 0.4 * cosv);
				if (d >= bd) {
					continue;
				}
				long k = key(rcx + dx, rcz + dz);
				if (!layer.has(k) && !inflight.contains(k)) {
					bd = d;
					best = k;
				}
			}
		}
		if (best == Long.MIN_VALUE && layer.count() > 0 && this == primary) {
			layer.evict(cx, cz, rad + SBC * 16.0 * 2);
		}
		if (best != Long.MIN_VALUE && !inflight.add(best)) {
			return Long.MIN_VALUE;
		}
		return best;
	}

	/** Chunk-Rechteck erzeugen; bei Kachel-/Kandidatenueberlauf geviertelt */
	private void generateRect(int cx0, int cz0, int cw, int cd) {
		long t0 = System.nanoTime();
		sg.generateAsync(cx0, cz0, cw, cd, env);
		sg.sync();
		if (rectLogs++ < 8) {
			LOGGER.info("Pyridium: Strukturregion {} {}: {} ms, {} Kandidaten, {} Kachel-ints", cx0, cz0, (int)((System.nanoTime() - t0) / 1e6), env.lastCandidates, env.lastEnvInts);
		}
		if (env.overflow) {
			if (cw <= 4 && cd <= 4) {
				LOGGER.warn("Pyridium: LOD-Strukturen: Umgebungspuffer zu klein fuer {} x {} Chunks bei {} {}", cw, cd, cx0, cz0);
				return;
			}
			int w1 = Math.max(1, cw / 2), d1 = Math.max(1, cd / 2);
			generateRect(cx0, cz0, w1, d1);
			generateRect(cx0 + w1, cz0, cw - w1, d1);
			generateRect(cx0, cz0 + d1, w1, cd - d1);
			generateRect(cx0 + w1, cz0 + d1, cw - w1, cd - d1);
		}
	}

	@Override
	public void close() {
		for (LodStructures l : lanes) {
			l.close();
		}
		stop = true;
		synchronized (lock) {
			lock.notifyAll();
		}
		try {
			if (worker != null) {
				worker.join(3000);
			}
		} catch (InterruptedException ignored) {
		}
		gpu.close();
	}
}
