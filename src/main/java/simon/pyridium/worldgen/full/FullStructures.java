package simon.pyridium.worldgen.full;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
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
 * Strukturen fuer den Vollchunk-Generator (Nahwelt): dieselbe GPU-Strukturgenerierung wie im LOD ({@link StructureGpu},
 * {@link StructureEnv}), aber als echte Bloecke: die Stufe {@code wg_full_struct.cu} liest die Arena je Spalte und schreibt Zustaende
 * in den Chunk. Vor jedem Stapel erzeugt {@link #prepare} synchron die 2048-Block-Regionen (plus Reichweite), die die Chunks beruehren
 * koennen; Regionen bleiben in der Arena (Cache), laeuft sie voll, wird sie geleert und spaeter identisch neu erzeugt. Alles auf der GPU.
 */
public final class FullStructures implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/full-gpu");
    static final int SBC = 128;     // Chunks je Regionskante (2048 Bloecke)

    public final StructureGpu sg;
    final StructureEnv env;
    final int reach;
    private final LongOpenHashSet done = new LongOpenHashSet();
    private boolean needReset;

    private FullStructures(StructureGpu sg, StructureEnv env, int reach) {
        this.sg = sg;
        this.env = env;
        this.reach = reach;
    }

    /** null, wenn nicht verfuegbar (Fehler geloggt) */
    public static FullStructures create(CudaCompute gpu, MinecraftServer server, RandomState rs, NoiseGeneratorSettings settings, ExactBiomes eb, long seed,
                                        int minY, int height, int sea) {
        if (Boolean.getBoolean("pyridium.fullNoStructures")) return null;
        try {
            List<String> ids = new ArrayList<>();
            for (var h : eb.biomes) ids.add(h.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains"));
            StructureTables t = StructureCompiler.compile(StructureData.ofServer(server.getResourceManager()), ids, seed, minY, height, sea, 0);
            // Ring-Sets (Strongholds, Teile fehlen) und Sets ohne Biom der Welt abschalten
            for (int s = 0; s < t.numSets(); s++) {
                int b = s * StructureTables.SET_W;
                boolean any = false;
                {
                    int es = t.sets.getInt(b + StructureTables.SET_ENTRY_START), ec = t.sets.getInt(b + StructureTables.SET_ENTRY_COUNT);
                    for (int e = 0; e < ec && !any; e++) {
                        int sb = t.entries.getInt((es + e) * StructureTables.ENTRY_W) * StructureTables.STRUCT_W;
                        if ((t.structs.getInt(sb + StructureTables.ST_FLAGS) & 1) == 0) continue;
                        int mo = t.structs.getInt(sb + StructureTables.ST_BIOME_MASK);
                        for (int w = 0; w < t.biomeWords && mo >= 0; w++) if (t.biomeMasks.getInt(mo + w) != 0) { any = true; break; }
                    }
                }
                if (!any) {
                    t.sets.set(b + StructureTables.SET_ENTRY_COUNT, 0);
                    t.sets.set(b + StructureTables.SET_ENV_INTS, 0);
                }
            }
            StructureGpu sg = new StructureGpu(gpu, t, ExactTerrainPipeline.JAR, "wg_struct_envfill", 1024, 1 << 14, 1 << 18, 128, 1 << 19, 10_000_000);
            StructureEnv env = new StructureEnv(sg, ExactTerrainPipeline.JAR, rs, settings, eb, 24L << 20);
            sg.solveRingsAsync(env);
            sg.sync();
            int reach = 0;
            for (int s = 0; s < t.numSets(); s++) {
                int b = s * StructureTables.SET_W;
                if (t.sets.getInt(b + StructureTables.SET_ENV_INTS) > 0) reach = Math.max(reach, t.sets.getInt(b + StructureTables.SET_ENV_R) + 16);
            }
            LOG.info("GPU-Vollchunk: Strukturen aktiv (Reichweite {} Bloecke)", reach);
            return new FullStructures(sg, env, reach);
        } catch (Throwable e) {
            LOG.error("GPU-Vollchunk: Strukturen nicht verfuegbar", e);
            return null;
        }
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) ^ (rz & 0xffffffffL);
    }

    /** Regionen fuer die Chunks (Block-Minima chunkXZ, n Chunks) bereitstellen; synchron, im Stream der Pipeline */
    public void prepare(int n, int[] chunkXZ) {
        try {
            LongOpenHashSet need = new LongOpenHashSet();
            long span = SBC * 16L;
            for (int c = 0; c < n; c++) {
                int rx0 = (int) Math.floorDiv(chunkXZ[c * 2] - reach, span), rx1 = (int) Math.floorDiv(chunkXZ[c * 2] + 15 + reach, span);
                int rz0 = (int) Math.floorDiv(chunkXZ[c * 2 + 1] - reach, span), rz1 = (int) Math.floorDiv(chunkXZ[c * 2 + 1] + 15 + reach, span);
                for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) need.add(key(rx, rz));
            }
            if (needReset) {
                sg.reset();
                done.clear();
                needReset = false;
            }
            boolean any = false;
            for (long k : need) {
                if (done.contains(k)) continue;
                generateRect((int) (k >> 32) * SBC, (int) k * SBC, SBC, SBC);
                done.add(k);
                any = true;
            }
            if (any) {
                int[] st = sg.arenaState();
                if (st[2] != 0 || st[4] != 0 || st[0] >= sg.capStart || st[1] >= sg.capPieces) {
                    LOG.warn("GPU-Vollchunk: Strukturarena voll, geleert");
                    sg.reset();
                    done.clear();
                } else if (st[1] > sg.capPieces * 0.8 || st[0] > sg.capStart * 0.8) {
                    needReset = true;
                }
            }
        } catch (Throwable e) {
            LOG.error("GPU-Vollchunk: Strukturregionen", e);
        }
    }

    private void generateRect(int cx0, int cz0, int cw, int cd) {
        sg.generateAsync(cx0, cz0, cw, cd, env);
        sg.sync();
        if (env.overflow) {
            if (cw <= 4 && cd <= 4) {
                LOG.warn("GPU-Vollchunk: Umgebungspuffer der Strukturen zu klein bei {} {}", cx0, cz0);
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
        env.close();
        sg.close();
    }
}
