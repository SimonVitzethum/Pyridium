package simon.pyridium.worldgen.exact;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Aquifer (Vanilla {@code Aquifer.NoiseBasedAquifer}, 26.3) fuer die GPU: Zufallsquelle,
 * Punktprogramme der sechs Aquifer-Funktionen, Meeresspiegel. Die GPU rechnet je Chunk
 * die Oberflaechentabelle (Quart-Raster), die Aquifer-Zellen und dann jeden Block
 * ({@code computeSubstance}) – Ergebnis 0 = fest (Standardblock), 1 Luft, 2 Wasser, 3 Lava.
 *
 * <p>Parameterblock (ints, {@link #encode}): siehe Feldkommentare in wg_exact_aquifer.cu.
 */
public final class ExactAquifer {
    public static final int SUBST_SOLID = 0, SUBST_AIR = 1, SUBST_WATER = 2, SUBST_LAVA = 3;
    /**
     * Quart-Tabelle der Oberflaeche je Chunk (relativ zum Chunk-Minimum): Aquifer-Zellen liegen in
     * x/z [-16, +25], computeFluid tastet bis 3 Chunks davor/1 dahinter (x) bzw. +-1 (z) ab:
     * x -64..+40 (27 Quarts), z -32..+40 (19 Quarts).
     */
    public static final int SURF_X0 = -64, SURF_Z0 = -32, SURF_NX = 27, SURF_NZ = 19;

    /** Aquifer-Zellen je Chunk (3 x Rasterhoehe x 3). */
    public static int cells(int minY, int height) {
        int gy0 = Math.floorDiv(minY + 1, 12) - 1, gy1 = Math.floorDiv(minY + height - 1 + 1, 12) + 1;
        return 3 * (gy1 - gy0 + 1) * 3;
    }

    /** Zustandsworte je Chunk in wg_exact_aquifer.cu (Obergrenze + 5 je Zelle). */
    public static int stateWords(int minY, int height) {
        return 1 + cells(minY, height) * 5;
    }

    /**
     * Vorlaeufige Oberflaeche dedupliziert: das Fenster eines Chunks (Quarts x -64..40, z -32..40
     * relativ) liegt in den Nachbar-Chunks x -4..2, z -2..2. idx: je Chunk 35 Indizes auf pos
     * (eindeutige Chunk-Minima, je 2 ints); count Bloecke, je 16 Werte auf der GPU.
     */
    public record SurfTable(int[] idx, int[] pos, int count) {
    }

    public static SurfTable surfaceTable(int[] chunkXZ, int n) {
        int[] idx = new int[n * 35];
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        map.defaultReturnValue(-1);
        it.unimi.dsi.fastutil.ints.IntArrayList pos = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int c = 0; c < n; c++) {
            int cx = chunkXZ[c * 2] >> 4, cz = chunkXZ[c * 2 + 1] >> 4;
            for (int dz = 0; dz < 5; dz++) for (int dx = 0; dx < 7; dx++) {
                int bx = cx + dx - 4, bz = cz + dz - 2;
                long key = ((long) bx << 32) ^ (bz & 0xffffffffL);
                int u = map.get(key);
                if (u < 0) {
                    u = pos.size() / 2;
                    map.put(key, u);
                    pos.add(bx << 4);
                    pos.add(bz << 4);
                }
                idx[c * 35 + dz * 7 + dx] = u;
            }
        }
        return new SurfTable(idx, pos.toIntArray(), pos.size() / 2);
    }

    public final List<String> unsupported = new ArrayList<>();
    public final ExactPointProgram.Entry barrier, floodedness, spread, lava, exclusion, surfaceLevel;
    public final long seedLo, seedHi;
    public final int seaLevel, minY, height;
    public final boolean seaIsWater;

    public ExactAquifer(RandomState rs, NoiseGeneratorSettings settings, ExactPointProgram prog, int minY, int height) {
        var aq = settings.aquifers().orElse(null);
        this.minY = minY;
        this.height = height;
        this.seaLevel = settings.seaLevel();
        this.seaIsWater = settings.defaultFluid().is(Blocks.WATER);
        if (!seaIsWater) unsupported.add("defaultFluid " + settings.defaultFluid());
        if (aq == null) {
            unsupported.add("keine Aquifer");
            barrier = floodedness = spread = lava = exclusion = surfaceLevel = null;
            seedLo = seedHi = 0;
            return;
        }
        barrier = prog.compile(rs.getSampler(aq.barrierNoise()));
        floodedness = prog.compile(rs.getSampler(aq.fluidLevelFloodednessNoise()));
        spread = prog.compile(rs.getSampler(aq.fluidLevelSpreadNoise()));
        lava = prog.compile(rs.getSampler(aq.lavaNoise()));
        exclusion = prog.compile(rs.getSampler(aq.exclusion()));
        surfaceLevel = prog.compile(rs.getSampler(aq.surfaceLevel()));
        var factory = rs.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));
        long lo = 0, hi = 0;
        if (factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory) {
            try {
                lo = (long) ExactPlan.fieldIn(factory, "seedLo");
                hi = (long) ExactPlan.fieldIn(factory, "seedHi");
            } catch (ReflectiveOperationException e) {
                unsupported.add("aquifer-seed");
            }
        } else {
            unsupported.add("Zufall " + factory.getClass().getSimpleName());
        }
        seedLo = lo;
        seedHi = hi;
    }

    /** [seedLo lo/hi, seedHi lo/hi, sea, minY, height, Code-Starts x6 (barrier..surfaceLevel), WAY_BELOW_MIN_Y]. */
    public int[] encode() {
        ExactPointProgram.Entry[] e = {barrier, floodedness, spread, lava, exclusion, surfaceLevel};
        int[] a = new int[16];
        a[13] = net.minecraft.world.level.dimension.DimensionType.WAY_BELOW_MIN_Y;
        a[0] = (int) seedLo;
        a[1] = (int) (seedLo >>> 32);
        a[2] = (int) seedHi;
        a[3] = (int) (seedHi >>> 32);
        a[4] = seaLevel;
        a[5] = minY;
        a[6] = height;
        for (int i = 0; i < 6; i++) a[7 + i] = e[i] == null ? 0 : e[i].start();
        return a;
    }
}
