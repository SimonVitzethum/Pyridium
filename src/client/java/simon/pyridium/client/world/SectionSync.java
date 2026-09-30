package simon.pyridium.client.world;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.system.MemoryUtil;
import simon.pyridium.client.gpu.Native;

/**
 * Haelt Pyrits Sektionsspeicher mit den geladenen Chunks des Clients
 * synchron: neue Chunks, Blockaenderungen, entladene Chunks.
 */
public final class SectionSync {
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final java.util.concurrent.ConcurrentMap<Long, LevelChunk> LOADED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Loaded chunk data, independent of Vanilla's disabled terrain meshes. */
    public static Iterable<LevelChunk> loadedChunks() { return LOADED.values(); }
	/** 4096 Zustaende (u16), danach Gras- und Laubfarbe je Blockspalte (u32) – types.zig slot_bytes */
	private static boolean statesUploaded;

	private SectionSync() {
	}

    public static void reset() {
        LOADED.clear();
		synchronized (DIRTY) {
			DIRTY.clear();
		}
		epoch++;
		PENDING.clear();
		TOP_SEEN.clear();
		statesUploaded = false;
	}

	public static void markDirty(int sx, int sy, int sz) {
		synchronized (DIRTY) {
			DIRTY.add(SectionPos.asLong(sx, sy, sz));
		}
	}

    public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        LOADED.put(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()), chunk);
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
			markDirty(cx, sy, cz);
		}
	}

    public static void chunkUnloaded(ClientLevel level, LevelChunk chunk) {
        LOADED.remove(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		Native.colSet(cx, cz, false);
		PENDING.remove(ChunkPos.pack(cx, cz));
		TOP_SEEN.remove(ChunkPos.pack(cx, cz));
		for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
			Native.secRemove(cx, sy, cz);
		}
	}

	/** Kopie einer Sektion fuer den Arbeiter (wie Vanillas Sektionsbau: Kopie auf dem Hauptthread) */
	private record Job(int epoch, long pos, PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
	}

	/** Ergebnis: slot != 0 -> Daten (Aufrufer gibt frei), sonst uniform (-1 = Luft) */
	private record Result(int epoch, long pos, long slot, int uniform) {
	}

	private static final java.util.concurrent.ConcurrentLinkedQueue<Result> DONE = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Pyridium Sektionen");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	/** offene Auftraege je Spalte; Spalte gilt als geladen, wenn 0 und die oberste Sektion durch ist */
	private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap PENDING = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet TOP_SEEN = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static volatile int epoch;

	/** Render-Thread, vor dem Weltframe: Sektionen kopieren (Arbeiter kodiert) und Fertiges uebernehmen. */
	public static void tick(int budget) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (!statesUploaded) {
			BlockPalette.upload();
			statesUploaded = true;
		}
		long t0 = System.nanoTime();
		long limit = Long.getLong("pyridium.sectionNanos", 1_500_000L);
		int ep = epoch;
		for (int i = 0; i < budget && System.nanoTime() - t0 < limit; i++) {
			long p;
			synchronized (DIRTY) {
				if (DIRTY.isEmpty()) {
					break;
				}
				p = DIRTY.removeFirstLong();
			}
			int sx = SectionPos.x(p), sy = SectionPos.y(p), sz = SectionPos.z(p);
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
			if (chunk == null) {
				continue;
			}
			long col = ChunkPos.pack(sx, sz);
			if (sy == level.getMaxSectionY()) {
				TOP_SEEN.add(col);
			}
			int idx = sy - level.getMinSectionY();
			LevelChunkSection[] secs = chunk.getSections();
			if (idx < 0 || idx >= secs.length || secs[idx].hasOnlyAir()) {
				Native.secRemove(sx, sy, sz);
				PENDING.addTo(col, 0);
				continue;
			}
			LevelChunkSection sec = secs[idx];
			PENDING.addTo(col, 1);
			Job j = new Job(ep, p, sec.getStates().copy(), sec.getBiomes().copy());
			WORKER.execute(() -> DONE.add(encode(j)));
		}
		// Fertiges uebernehmen (Kopie in Pyrits Tabelle, billig)
		Result r;
		while ((r = DONE.poll()) != null) {
			int sx = SectionPos.x(r.pos), sy = SectionPos.y(r.pos), sz = SectionPos.z(r.pos);
			if (r.epoch == ep && level.getChunkSource().getChunk(sx, sz, false) != null) {
				if (r.slot != 0) {
					Native.secPut(sx, sy, sz, r.slot, 0);
				} else if (r.uniform < 0) {
					Native.secRemove(sx, sy, sz);
				} else {
					Native.secPut(sx, sy, sz, 0L, r.uniform);
				}
				PENDING.addTo(ChunkPos.pack(sx, sz), -1);
			}
			if (r.slot != 0) {
				MemoryUtil.nmemFree(r.slot);
			}
		}
		// Spalten ohne offene Auftraege, deren oberste Sektion durch ist: geladen
        if (!TOP_SEEN.isEmpty()) {
			var it = TOP_SEEN.iterator();
			while (it.hasNext()) {
				long col = it.nextLong();
				if (PENDING.get(col) <= 0) {
					PENDING.remove(col);
					Native.colSet(ChunkPos.getX(col), ChunkPos.getZ(col), true);
					it.remove();
        }
        BlockPalette.uploadPending(32);
			}
		}
	}

	/** Arbeiter: Zustands-IDs und Biomfarben einer kopierten Sektion */
	private static Result encode(Job j) {
		int[] single = {-1, 0};
		j.states.forEachInPalette(st -> {
            BlockPalette.requireState(Block.getId(st));
			single[1]++;
			single[0] = Block.getId(st);
		});
		if (single[1] == 1) {
			return new Result(j.epoch, j.pos, 0L, single[0]);
		}
		long slot = MemoryUtil.nmemAlloc(8192 + 2048);
		BlockState last = null;
		int lastId = 0, k = 0;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState st = j.states.get(x, y, z);
					if (st != last) {
						last = st;
						lastId = Block.getId(st);
					}
					MemoryUtil.memPutShort(slot + k * 2L, (short)lastId);
					k++;
				}
			}
		}
		// Biomfarben je Blockspalte aus den Biom-Vierteln (Mitte der Sektion)
		int sx = SectionPos.x(j.pos), sz = SectionPos.z(j.pos);
		long cols = slot + 8192;
		for (int qz = 0; qz < 4; qz++) {
			for (int qx = 0; qx < 4; qx++) {
				Biome b = j.biomes.get(qx, 2, qz).value();
				int foliage = b.getFoliageColor() & 0xffffff;
				for (int dz = 0; dz < 4; dz++) {
					for (int dx = 0; dx < 4; dx++) {
						int x = qx * 4 + dx, z = qz * 4 + dz;
						MemoryUtil.memPutInt(cols + (z * 16L + x) * 4, b.getGrassColor(sx * 16 + x, sz * 16 + z) & 0xffffff);
						MemoryUtil.memPutInt(cols + 1024 + (z * 16L + x) * 4, foliage);
					}
				}
			}
		}
		return new Result(j.epoch, j.pos, slot, 0);
	}
}
