package simon.pyridium.client.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.client.gpu.Native;
import simon.pyridium.worldgen.structures.StructureTables;
import simon.pyridium.worldgen.structures.TemplateGeometry;

/**
 * Strukturen als eigene Pyrit-Geometrieschicht, getrennt vom LOD-Terrain: jede Vorlage (und ihre dyadischen Mips) wird einmal zu
 * geteilter HashDAG-Geometrie gebacken ({@link TemplateGeometry} -&gt; {@code pyd_model_create}); je platziertem Teil gibt es eine
 * Instanz mit Transformation (Drehung/Spiegelung aus dem Teil), Mip-Ebene nach Abstand und Bildschirmgroesse. Dazu eine
 * Fundament-Instanz (eingefaerbter Quader von der Gelaendehoehe der LOD-Pipeline bis zur Unterkante des Teils). Die Teilelisten
 * kommen je 2048-Block-Region aus der GPU-Arena ({@link LodStructures}); das Terrain wird nie angefasst und nie verlangsamt.
 */
final class LodStructureLayer {
	private static final Logger LOGGER = LoggerFactory.getLogger("Pyridium");
	static volatile LodStructureLayer active;
	private static final long ID_BASE = 1L << 62, ID_FOUND = 1L << 61;
	/** pro Frame hoechstens so viele neue Geometrien (jede Erzeugung synchronisiert kurz den Stream) */
	private static final int CREATE_PER_FRAME = Integer.getInteger("pyridium.structCreatePerFrame", 3);
	private static final int MAX_INSTANCES = 150_000;
	/** hoechste Fundamenttiefe (Bloecke) unter einem Teil; tiefer = Teil schwebt ueber Wasser/Abgrund und entfaellt */
	private static final int MAX_FOUNDATION = Integer.getInteger("pyridium.structMaxFoundation", 12);

	/** Region: Teile als Instanzdaten (float[16] je Teil: 3x4 Matrix Ebene 0 ohne Verschiebung, Mitte xyz, Radius ... siehe unten) */
	static final class Sb {
		int id;
		long key;
		int n;
		int[] tpl;
		float[] m;        // je Teil 9 Eintraege der Matrix (Zeile-Haupt 3x3, ohne Skalierung)
		double[] t;       // je Teil Verschiebung (Welt) + 1 Ebene-0-Eckpunktverschiebung eingerechnet
		float[] c;        // je Teil Mitte (xyz) und Radius
		float[] f;        // je Teil Fundament: x0, y0, z0, w, h, d (h <= 0: keins), dazu Farbe in fc
		int[] fc;
		int[] bio;       // Biomindex je Teil (Tint)
		int x0, z0;       // Blockursprung der Region (fuer Abstandsprueffung)
		int size;
	}

	private final StructureTables t;
	private final ConcurrentHashMap<Long, Sb> sbs = new ConcurrentHashMap<>();
	private int nextId = 1;
	/** je Vorlage und Ebene: Geometrie-Handle, 0 = fehlgeschlagen, fehlt = noch nicht gebaut */
	private final java.util.HashMap<Long, Long> geos = new java.util.HashMap<>();
	private final java.util.HashMap<Integer, Long> boxes = new java.util.HashMap<>();
	private final java.util.HashMap<Integer, Integer> lowest = new java.util.HashMap<>();
	private final java.util.HashMap<Integer, int[]> lowestState = new java.util.HashMap<>();
	private final int maxLevel;
	private int created;
	private long nextLog;
	private int nearLogs;
	long instancesLast, geometriesCreated;

	/** Biome in der Reihenfolge der GPU-Biomsuche (Tint der Strukturen) */
	private final java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes;

	LodStructureLayer(StructureTables t, java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes) {
		this.t = t;
		this.biomes = biomes;
		int ml = 0;
		for (int i = 0; i < t.numTemplates(); i++) {
			int mip = t.templates.getInt(i * StructureTables.TPL_W + StructureTables.TP_MIP);
			if (mip >= 0) {
				ml = Math.max(ml, t.mips.getInt(mip));
			}
		}
		this.maxLevel = ml;
	}

	boolean has(long key) {
		return sbs.containsKey(key);
	}

	int count() {
		return sbs.size();
	}

	void clear() {
		sbs.clear();
	}

	/** nur Regionen behalten, deren Mitte hoechstens radius vom Punkt entfernt ist */
	void evict(double x, double z, double radius) {
		sbs.values().removeIf(s -> Math.hypot(s.x0 + s.size / 2.0 - x, s.z0 + s.size / 2.0 - z) > radius);
	}

	// ---------------------------------------------------------------------------------------
	// Worker-Thread: Teileliste einer Region -> Instanzdaten
	// ---------------------------------------------------------------------------------------

	private static int[] xform(int x, int z, int rot, int mirror, int pvx, int pvz) {
		if (mirror == 1) {
			z = -z;
		} else if (mirror == 2) {
			x = -x;
		}
		return switch (rot) {
			case 1 -> new int[]{pvx + pvz - z, pvz - pvx + x};
			case 2 -> new int[]{pvx + pvx - x, pvz + pvz - z};
			case 3 -> new int[]{pvx - pvz + z, pvx + pvz - x};
			default -> new int[]{x, z};
		};
	}

	static int colorOf(int state) {
		BlockState st = Block.BLOCK_STATE_REGISTRY.byId(state);
		if (st == null || st.isAir() || BlockPalette.attrOf(st) == 0) {
			return -1;
		}
		if (!st.getFluidState().isEmpty()) {
			return -1;
		}
		if (st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
			return -1;
		}
		int c = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		return c == 0 ? 0x808080 : c & 0xffffff;
	}

	/** tiefste sichtbare Schicht der Vorlage (Ebene 0) und der Zustand dort; {-1, 0} wenn nichts sichtbar */
	private int[] lowestOf(int tpl) {
		int[] r = lowestState.get(tpl);
		if (r != null) {
			return r;
		}
		int b = tpl * StructureTables.TPL_W;
		int sx = t.templates.getInt(b + StructureTables.TP_SX), sy = t.templates.getInt(b + StructureTables.TP_SY), sz = t.templates.getInt(b + StructureTables.TP_SZ);
		int vox = t.templates.getInt(b + StructureTables.TP_VOX), pal = t.templates.getInt(b + StructureTables.TP_PAL);
		r = new int[]{-1, 0};
		outer:
		for (int y = 0; y < sy; y++) {
			for (int i = 0; i < sx * sz; i++) {
				int v = t.voxels.getShort(vox + y * sx * sz + i) & 0xffff;
				if (v == 0) {
					continue;
				}
				int st = t.palette.getInt(pal + v - 1);
				if (st != 0 && colorOf(st) >= 0) {
					r = new int[]{y, st};
					break outer;
				}
			}
		}
		lowestState.put(tpl, r);
		return r;
	}

	/** Region veroeffentlichen. pieces: PIECE_W ints je Teil; grid: int16-Hoehenraster (Abstand 8) ab (gx0, gz0), Breite gw */
	synchronized void publish(long key, int x0, int z0, int size, int[] pieces, short[] grid, int gx0, int gz0, int gw, byte[] bio, int bx0, int bz0, int bcols) {
		int pw = StructureTables.PIECE_W;
		int np = pieces.length / pw;
		List<Integer> keep = new ArrayList<>();
		for (int i = 0; i < np; i++) {
			if (pieces[i * pw + StructureTables.PC_TPL] >= 0) {
				keep.add(i);
			}
		}
		Sb s = new Sb();
		s.id = nextId++ & 0xffff;
		s.key = key;
		s.x0 = x0;
		s.z0 = z0;
		s.size = size;
		int n = keep.size();
		s.n = n;
		s.tpl = new int[n];
		s.m = new float[n * 9];
		s.t = new double[n * 3];
		s.c = new float[n * 4];
		s.f = new float[n * 6];
		s.fc = new int[n];
		s.bio = new int[n];
		for (int k = 0; k < n; k++) {
			int[] p = java.util.Arrays.copyOfRange(pieces, keep.get(k) * pw, keep.get(k) * pw + pw);
			String sn = t.structNames.get(p[StructureTables.PC_STRUCT]);
			if (!sn.startsWith("minecraft:") && Math.abs(p[StructureTables.PC_X]) + Math.abs(p[StructureTables.PC_Z]) < 1000 && customLogs++ < 12) {
				LOGGER.info("Pyridium: Datapack-Struktur {} Teil bei {} {} {} (Vorlage {})", sn, p[StructureTables.PC_X], p[StructureTables.PC_Y], p[StructureTables.PC_Z], t.templateNames.get(p[StructureTables.PC_TPL]));
			}
			int tpl = p[StructureTables.PC_TPL];
			s.tpl[k] = tpl;
			{
				int bi = Math.min(Math.max(((p[StructureTables.PC_X] - bx0) / 48), 0), bcols - 1), bj = Math.min(Math.max(((p[StructureTables.PC_Z] - bz0) / 48), 0), bcols - 1);
				s.bio[k] = bio[bj * bcols + bi] & 0xff;
			}
			boolean proc = p[StructureTables.PC_KIND] == 1;
			int rot = p[StructureTables.PC_ROT], mir = proc ? p[StructureTables.PC_MIRROR] : 0;
			int pvx = proc ? p[StructureTables.PC_P0] : 0, pvz = proc ? p[StructureTables.PC_P0 + 1] : 0;
			int[] o = xform(0, 0, rot, mir, pvx, pvz), ex = xform(1, 0, rot, mir, pvx, pvz), ez = xform(0, 1, rot, mir, pvx, pvz);
			int axx = ex[0] - o[0], azx = ex[1] - o[1];       // Spalte: lokales x -> (welt x, welt z)
			int axz = ez[0] - o[0], azz = ez[1] - o[1];
			// Zellindex -> Eckpunkt: je Ausgabeachse +1, wenn der Eintrag -1 ist
			double tx = p[StructureTables.PC_X] + o[0] + ((axx < 0 || axz < 0) ? 1 : 0);
			double tz = p[StructureTables.PC_Z] + o[1] + ((azx < 0 || azz < 0) ? 1 : 0);
			double ty = p[StructureTables.PC_Y];
			s.m[k * 9] = axx;
			s.m[k * 9 + 1] = axz;
			s.m[k * 9 + 2] = 0;
			s.m[k * 9 + 3] = 0;
			s.m[k * 9 + 4] = 1;
			s.m[k * 9 + 5] = 0;
			s.m[k * 9 + 6] = azx;
			s.m[k * 9 + 7] = azz;
			s.m[k * 9 + 8] = 0;
			s.t[k * 3] = tx;
			s.t[k * 3 + 1] = ty;
			s.t[k * 3 + 2] = tz;
			int minx = p[StructureTables.PC_MINX], maxx = p[StructureTables.PC_MAXX], minz = p[StructureTables.PC_MINZ], maxz = p[StructureTables.PC_MAXZ];
			int miny = p[StructureTables.PC_MINY], maxy = p[StructureTables.PC_MAXY];
			s.c[k * 4] = (minx + maxx + 1) * 0.5f;
			s.c[k * 4 + 1] = (miny + maxy + 1) * 0.5f;
			s.c[k * 4 + 2] = (minz + maxz + 1) * 0.5f;
			s.c[k * 4 + 3] = (float)(0.5 * Math.sqrt((double)(maxx - minx + 1) * (maxx - minx + 1) + (double)(maxy - miny + 1) * (maxy - miny + 1) + (double)(maxz - minz + 1) * (maxz - minz + 1)));
			// Fundament: Boden = Minimum der LOD-Hoehe (vorlaeufige Oberflaeche) unter dem Grundriss, bis zur Unterkante der Vorlage
			int[] low = lowestOf(tpl);
			boolean tm = (p[StructureTables.PC_FLAGS] & 2) != 0;
			int base = p[StructureTables.PC_Y] + Math.max(0, low[0]);
			int ground = Integer.MAX_VALUE;
			int gmax = Integer.MIN_VALUE;
			for (int gz = minz; gz <= maxz + 8; gz += 8) {
				for (int gx = minx; gx <= maxx + 8; gx += 8) {
					int ix = Math.min(Math.max((Math.min(gx, maxx) - gx0) >> 3, 0), gw - 1), iz = Math.min(Math.max((Math.min(gz, maxz) - gz0) >> 3, 0), gw - 1);
					ground = Math.min(ground, grid[iz * gw + ix]);
					gmax = Math.max(gmax, grid[iz * gw + ix]);
				}
			}
			if (low[0] >= 0 && maxy >= ground - 6 && !tm && (p[StructureTables.PC_FLAGS] & 2) == 0) {
				int dlt = base - gmax;      // Unterkante gegen hoechsten Boden unter dem Grundriss
				hist[dlt < -8 ? 0 : dlt < -2 ? 1 : dlt <= 2 ? 2 : dlt <= 6 ? 3 : dlt <= 12 ? 4 : 5]++;
			}
			if (maxy < ground - 6 || low[0] < 0) {
				s.f[k * 6 + 4] = -1;           // unterirdisch: gar nicht zeichnen
				s.c[k * 4 + 3] = -1;
			} else if (!tm && (base - ground > MAX_FOUNDATION || (ground < t.seaLevel - 3 && base - ground > 3) || gmax - ground > 16) || tm && ground < t.seaLevel - 2) {
				// ueber tiefem Wasser/Abgrund waere nur ein langer Pfeiler moeglich: Teil weglassen (wie ein Dorf, das am Ufer endet)
				s.f[k * 6 + 4] = -1;
				s.c[k * 4 + 3] = -1;
			} else if (!tm && base - ground >= 2) {
				int depth = Math.min(base - ground + 2, 64);
				s.f[k * 6] = minx;
				s.f[k * 6 + 1] = base - depth;
				s.f[k * 6 + 2] = minz;
				s.f[k * 6 + 3] = maxx - minx + 1;
				s.f[k * 6 + 4] = depth;
				s.f[k * 6 + 5] = maxz - minz + 1;
				BlockState lb = Block.BLOCK_STATE_REGISTRY.byId(low[1]);
				// Fundamentmaterial: Material des tiefsten Blocks; Bewuchs/Schnee wird zu Erde
				if (lb != null && (lb.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || lb.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
					|| lb.is(net.minecraft.world.level.block.Blocks.PODZOL) || lb.is(net.minecraft.world.level.block.Blocks.MYCELIUM)
					|| lb.is(net.minecraft.world.level.block.Blocks.SNOW) || lb.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) {
					lb = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
				}
				int at = lb == null ? 0 : BlockPalette.attrOf(lb);
				s.fc[k] = at != 0 ? at : BlockPalette.attrOf(net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		// unterirdische/verworfene Teile entfernen (Speicher, Schleife je Frame)
		int m = 0;
		for (int k = 0; k < n; k++) {
			if (s.c[k * 4 + 3] >= 0) {
				m++;
			}
		}
		if (m < n) {
			int[] ntpl = new int[m], nfc = new int[m], nbio = new int[m];
			float[] nm = new float[m * 9], nc = new float[m * 4], nf = new float[m * 6];
			double[] nt = new double[m * 3];
			int j = 0;
			for (int k = 0; k < n; k++) {
				if (s.c[k * 4 + 3] < 0) {
					continue;
				}
				ntpl[j] = s.tpl[k];
				nfc[j] = s.fc[k];
				nbio[j] = s.bio[k];
				System.arraycopy(s.m, k * 9, nm, j * 9, 9);
				System.arraycopy(s.c, k * 4, nc, j * 4, 4);
				System.arraycopy(s.f, k * 6, nf, j * 6, 6);
				System.arraycopy(s.t, k * 3, nt, j * 3, 3);
				j++;
			}
			s.tpl = ntpl;
			s.fc = nfc;
			s.bio = nbio;
			s.m = nm;
			s.c = nc;
			s.f = nf;
			s.t = nt;
			s.n = m;
		}
		sbs.put(key, s);
		int shown = s.n;
		if (publishLogs++ < 12) {
			LOGGER.info("Pyridium: Strukturschicht Region {} {}: {} Teile, {} sichtbar, {} verworfen; Unterkante-Boden-Verteilung {}", x0, z0, n, shown, n - shown, java.util.Arrays.toString(hist));
		}
	}
	private int publishLogs, customLogs;
	/** Verteilung Unterkante - hoechster Boden (Bloecke): <-8, -8..-3, -2..2, 3..6, 7..12, >12 */
	private final long[] hist = new long[6];

	// ---------------------------------------------------------------------------------------
	// Render-Thread
	// ---------------------------------------------------------------------------------------

	/** Attribut eines Blockzustands mit Biom-Tint (Gras/Laub wie im LOD-Terrain: LodGenerator.biomeTable) */
	private int tintedAttr(BlockState bs, int biome) {
		int attr = BlockPalette.attrOf(bs);
		if (attr == 0 || biome < 0 || biome >= biomes.size()) {
			return attr;
		}
		var b = biomes.get(biome).value();
		net.minecraft.world.level.block.Block blk = bs.getBlock();
		if (bs.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) && !bs.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
			|| blk == net.minecraft.world.level.block.Blocks.SHORT_GRASS || blk == net.minecraft.world.level.block.Blocks.TALL_GRASS || blk == net.minecraft.world.level.block.Blocks.FERN
			|| blk == net.minecraft.world.level.block.Blocks.LARGE_FERN) {
			return BlockPalette.attr(attr & 0xffff, b.getGrassColor(0, 0) & 0xffffff);
		}
		if (bs.is(net.minecraft.tags.BlockTags.LEAVES)) {
			if (blk == net.minecraft.world.level.block.Blocks.BIRCH_LEAVES) {
				return BlockPalette.attr(attr & 0xffff, 0x80A755);
			}
			if (blk == net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES) {
				return BlockPalette.attr(attr & 0xffff, 0x619961);
			}
			if (blk == net.minecraft.world.level.block.Blocks.MANGROVE_LEAVES) {
				return BlockPalette.attr(attr & 0xffff, 0x8DB127);
			}
			if (blk == net.minecraft.world.level.block.Blocks.CHERRY_LEAVES || blk == net.minecraft.world.level.block.Blocks.PALE_OAK_LEAVES
				|| blk == net.minecraft.world.level.block.Blocks.AZALEA_LEAVES || blk == net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA_LEAVES) {
				return attr;
			}
			return BlockPalette.attr(attr & 0xffff, b.getFoliageColor() & 0xffffff);
		}
		return attr;
	}

	private long geometry(int tpl, int level, int biome) {
		long k = ((long)tpl << 24) | ((long)level << 16) | (biome & 0xffff);
		Long g = geos.get(k);
		if (g != null) {
			return g;
		}
		if (created >= CREATE_PER_FRAME) {
			return -1;
		}
		created++;
		long handle = 0;
		TemplateGeometry.Voxels v = TemplateGeometry.bakeVoxels(t, tpl, level, st -> {
			BlockState bs = Block.BLOCK_STATE_REGISTRY.byId(st);
			return bs == null || bs.isAir() ? 0 : tintedAttr(bs, biome);
		});
		if (v != null) {
			handle = Native.modelCreateVoxels(MemoryUtil.memAddress(v.data()), v.count(), v.log2());
			if (handle != 0) {
				geometriesCreated++;
			}
		}
		geos.put(k, handle);
		return handle;
	}

	private long create(TemplateGeometry.Baked b) {
		long h = Native.modelCreate(MemoryUtil.memAddress(b.faces()), b.faceCount(), b.samples(), b.log2());
		if (h != 0) {
			geometriesCreated++;
		}
		return h;
	}

	/** 16er-Quader aus einem Material (Fundamente); Instanzen skalieren ihn auf Grundriss und Tiefe */
	private long box(int attr) {
		Long g = boxes.get(attr);
		if (g != null) {
			return g;
		}
		if (created >= CREATE_PER_FRAME) {
			return -1;
		}
		created++;
		ByteBuffer f = ByteBuffer.allocateDirect(16 * 16 * 16 * 16).order(ByteOrder.nativeOrder());
		int n = 0;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					if (x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15) continue;
					f.putInt(x).putInt(y).putInt(z).putInt(attr);
					n++;
				}
		f.flip();
		long h = Native.modelCreateVoxels(MemoryUtil.memAddress(f), n, 4);
		boxes.put(attr, h);
		if (h != 0) {
			geometriesCreated++;
		}
		return h;
	}

	/**
	 * Instanzen dieses Frames in EntityScene einreihen (Rendern in Kamera-relativen Koordinaten wie die Entities).
	 * @param ox, oz Render-Ursprung (Vielfache von 1024)
	 */
	void collect(double camX, double camY, double camZ, float fx, float fy, float fz, double ppb, double ox, double oz, double viewBlocks) {
		created = 0;
		int count = 0, near = 0;
		double minPx = Double.parseDouble(System.getProperty("pyridium.structMinPx", "0.6"));
		float[] m = new float[12];
		for (Sb s : sbs.values()) {
			double dxs = Math.max(Math.max(s.x0 - camX, 0), camX - (s.x0 + s.size)), dzs = Math.max(Math.max(s.z0 - camZ, 0), camZ - (s.z0 + s.size));
			if (Math.hypot(dxs, dzs) - 64 > viewBlocks) {
				continue;
			}
			for (int k = 0; k < s.n && count < MAX_INSTANCES; k++) {
				float rad = s.c[k * 4 + 3];
				if (rad < 0) {
					continue;
				}
				double dx = s.c[k * 4] - camX, dy = s.c[k * 4 + 1] - camY, dz = s.c[k * 4 + 2] - camZ;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-3;
				if (2 * rad * ppb / dist < minPx) {
					continue;
				}
				if ((dx * fx + dy * fy + dz * fz) < -rad) {
					continue;
				}
				int level = Math.max(0, Math.min(maxLevel, (int)Math.floor(Math.log(Math.max(1.0, dist / ppb)) / Math.log(2))));
				long geo = 0;
				int used = level;
				for (int l = level; l <= maxLevel + 1 && geo == 0; l++) {
					long g = geometry(s.tpl[k], l, s.bio[k]);
					if (g < 0) {
						geo = -1;
						break;
					}
					if (g != 0) {
						geo = g;
						used = l;
					}
				}
				if (geo > 0) {
					if (dist < 300) {
						near++;
					}
					float sc = 1 << used;
					m[0] = s.m[k * 9] * sc;
					m[0] = s.m[k * 9] * sc;       // x' aus lx
					m[1] = 0;                     // x' aus ly
					m[2] = s.m[k * 9 + 1] * sc;   // x' aus lz
					m[3] = (float)(s.t[k * 3] - ox);
					m[4] = 0;
					m[5] = sc;
					m[6] = 0;
					m[7] = (float)s.t[k * 3 + 1];
					m[8] = s.m[k * 9 + 6] * sc;   // z' aus lx
					m[9] = 0;
					m[10] = s.m[k * 9 + 7] * sc;  // z' aus lz
					m[11] = (float)(s.t[k * 3 + 2] - oz);
					EntityScene.appendExternal(ID_BASE | ((long)s.id << 24) | k, geo, m);
					count++;
				}
				float fh = s.f[k * 6 + 4];
				if (fh > 0 && Math.abs(s.f[k * 6] + s.f[k * 6 + 3] * 0.5 - camX) < s.f[k * 6 + 3] * 0.5 + 3 && Math.abs(s.f[k * 6 + 2] + s.f[k * 6 + 5] * 0.5 - camZ) < s.f[k * 6 + 5] * 0.5 + 3 && nearLogs++ < 8) {
					LOGGER.info("Pyridium: Kamera ueber Fundament: Vorlage {} ({}) Box x{} y{} z{} {}x{}x{} Teil-Mitte {} {} {}", s.tpl[k], t.templateNames.get(s.tpl[k]), s.f[k * 6], s.f[k * 6 + 1], s.f[k * 6 + 2], s.f[k * 6 + 3], fh, s.f[k * 6 + 5], s.c[k * 4], s.c[k * 4 + 1], s.c[k * 4 + 2]);
				}
				if (fh > 0 && dist < viewBlocks) {
					long bg = box(s.fc[k]);
					if (bg > 0) {
						m[0] = s.f[k * 6 + 3] / 16f;
						m[1] = 0;
						m[2] = 0;
						m[3] = (float)(s.f[k * 6] - ox);
						m[4] = 0;
						m[5] = fh / 16f;
						m[6] = 0;
						m[7] = s.f[k * 6 + 1];
						m[8] = 0;
						m[9] = 0;
						m[10] = s.f[k * 6 + 5] / 16f;
						m[11] = (float)(s.f[k * 6 + 2] - oz);
						EntityScene.appendExternal(ID_BASE | ID_FOUND | ((long)s.id << 24) | k, bg, m);
						count++;
					}
				}
			}
		}
		instancesLast = count;
		if (System.currentTimeMillis() > nextLog) {
			nextLog = System.currentTimeMillis() + 10000;
			LOGGER.info("Pyridium: Strukturschicht: {} Regionen, {} Instanzen im Frame ({} naeher als 300 Bloecke), {} Geometrien, Kamera {} {} {}", sbs.size(), count, near, geometriesCreated, (int)camX, (int)camY, (int)camZ);
		}
	}

	/** Aufruf aus EntityScene.render (Render-Thread): Kamera -> Instanzen; gibt der Arbeitsschleife die Kamera */
	static void renderHook(net.minecraft.client.renderer.state.level.CameraRenderState camera, net.minecraft.world.phys.Vec3 renderPosition) {
		LodStructureLayer l = active;
		if (l == null) {
			return;
		}
		org.joml.Vector3f f = camera.orientation.transform(new org.joml.Vector3f(0, 0, -1));
		double ox = Math.floor(renderPosition.x / 1024) * 1024, oz = Math.floor(renderPosition.z / 1024) * 1024;
		double view = Integer.getInteger("pyridium.viewChunks", 1024) * 16.0;
		l.collect(renderPosition.x, renderPosition.y, renderPosition.z, f.x, f.y, f.z, LodGenerator.pixelsPerBlock, ox, oz, view);
		LodStructures ls = l.owner;
		if (ls != null) {
			ls.setCamera(renderPosition.x, renderPosition.z, Math.min(view, 48.0 * LodGenerator.pixelsPerBlock));
			double hl = Math.hypot(f.x, f.z);
			ls.dirX = hl > 1e-3 ? f.x / hl : 0;
			ls.dirZ = hl > 1e-3 ? f.z / hl : 0;
		}
	}

	volatile LodStructures owner;

	static void logStats(LodStructureLayer l) {
		LOGGER.info("Pyridium: Strukturschicht: {} Regionen, {} Instanzen, {} Geometrien", l.sbs.size(), l.instancesLast, l.geometriesCreated);
	}
}
