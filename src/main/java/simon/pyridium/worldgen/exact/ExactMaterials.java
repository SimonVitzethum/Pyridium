package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.condition.*;
import net.minecraft.world.level.levelgen.material.rule.*;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/**
 * Materialregeln (26.3 {@code MaterialSystem.buildSurface}: Oberflaeche, Untergrund, Erzadern,
 * Grundgestein) fuer die GPU: Regelbaum als Sprungcode, Bedingungen als Tabelle, dazu alle
 * Zufalls-Seeds, Rauschstapel, Tonbaender und Biomdaten. Ergebnis sind globale BlockState-IDs
 * ({@code Block.BLOCK_STATE_REGISTRY}).
 *
 * <p>Regelcode (ints): R_IF [op, Bedingung, Sprungziel], R_BLOCK [op, Zustand], R_BANDS [op],
 * R_ORE [op, Ader], R_END [op]. Eine Sequenz ist schlicht die Verkettung ihrer Kinder, weil jede
 * Regel mit Ergebnis sofort zurueckkehrt (wie SequenceRule: erstes Nicht-null gewinnt).
 */
public final class ExactMaterials {
    public static final int R_IF = 1, R_BLOCK = 2, R_BANDS = 3, R_ORE = 4, R_END = 5;
    public static final int C_NOT = 1, C_Y = 2, C_WATER = 3, C_STONE = 4, C_BIOME = 5, C_NOISE = 6, C_VGRAD = 7,
            C_STEEP = 8, C_HOLE = 9, C_ABOVE = 10, C_TEMP = 11;
    public static final int COND_WORDS = 8, ORE_WORDS = 12, BIOME_WORDS = 4;

    public final List<String> unsupported = new ArrayList<>();
    public final IntArrayList rules = new IntArrayList();
    public final IntArrayList conds = new IntArrayList();
    public final IntArrayList ores = new IntArrayList();
    public final IntArrayList extra = new IntArrayList(); // Biom-Bitsets
    /** Erzadern: Dichte-/Reichtums-Funktion (Volumenpfad, Chunk) und Luecke (Punktpfad). */
    public final List<net.minecraft.world.level.levelgen.densityfunction.DensityFunction> oreDensity = new ArrayList<>(),
            oreRichness = new ArrayList<>();
    public final List<ExactPointProgram.Entry> oreGap = new ArrayList<>();
    public final int[] clayBands = new int[192];
    public int[] biomeTable = new int[0];
    public final int[] noiseStacks = new int[9]; // surface, secondary, clayOffset, badPillar, badRoof, badSurface, icePillar, iceRoof, iceSurface
    public long rootSeedLo, rootSeedHi;
    public final int defaultState, waterState, lavaState, airState, packedIce, snowBlock, seaLevel;
    /** Scratch-Offset der Aufgabe chunkSurfaceLevel auf (16, 1, 16) – setzt der Aufrufer. */
    public int preliminarySurfaceTask = -1;

    private final ExactBiomes biomes;
    private final ExactPlan host;
    private final ExactPointProgram points;
    private final RandomState rs;
    private final WorldGenerationContext genCtx;
    private final Map<Object, Integer> condIds = new IdentityHashMap<>();

    /** Erzadern des Regelbaums (Dichte, Reichtum je Ader in Regelreihenfolge) – fuer die Plan-Wurzeln. */
    public static List<OreVeinRule> oreVeins(MaterialRule r) {
        List<OreVeinRule> out = new ArrayList<>();
        collectOres(r, out);
        return out;
    }

    private static void collectOres(MaterialRule r, List<OreVeinRule> out) {
        if (r instanceof MaterialRule.HolderHolder h) collectOres(h.holder().value(), out);
        else if (r instanceof SequenceRule seq) for (MaterialRule c : seq.sequence()) collectOres(c, out);
        else if (r instanceof ConditionRule c) collectOres(c.thenRun(), out);
        else if (r instanceof OreVeinRule o) out.add(o);
    }

    /** Scratch-Offsets der Volumen-Aufgaben fuer Dichte/Reichtum der Ader k. */
    public void setOreTasks(int k, int densityOffset, int richnessOffset) {
        ores.set(k * ORE_WORDS + 4, densityOffset);
        ores.set(k * ORE_WORDS + 5, richnessOffset);
    }

    /**
     * Regel-Ausgaben (Block, Tonband, Erz): Luft aendert die Spaltenhoehe, Wasser/Lava das
     * Luft/Wasser-Muster, das der Eisberg liest. Sonst darf die Vorhersage die Regeln auslassen.
     */
    private boolean rulesMayPlaceAir, rulesMayPlaceFluid;

    /** Grund, warum die Vorhersage die Regeln mitrechnen muss (null = nie). */
    public String rulesChangeColumnsWhy;

    private void changesClass(BlockState s) {
        boolean fluid = s == Blocks.WATER.defaultBlockState() || s == Blocks.LAVA.defaultBlockState();
        if (s.isAir()) {
            rulesMayPlaceAir = true;
            rulesChangeColumnsWhy = s.toString();
        }
        if (fluid) rulesMayPlaceFluid = true;
    }

    public static int stateId(BlockState s) {
        return Block.BLOCK_STATE_REGISTRY.getId(s);
    }

    public ExactMaterials(RandomState rs, NoiseGeneratorSettings settings, WorldGenerationContext genCtx, ExactBiomes biomes,
                          ExactPlan host, ExactPointProgram points) {
        this.rs = rs;
        this.genCtx = genCtx;
        this.biomes = biomes;
        this.host = host;
        this.points = points;
        defaultState = stateId(settings.defaultBlock());
        waterState = stateId(Blocks.WATER.defaultBlockState());
        lavaState = stateId(Blocks.LAVA.defaultBlockState());
        airState = stateId(Blocks.AIR.defaultBlockState());
        packedIce = stateId(Blocks.PACKED_ICE.defaultBlockState());
        snowBlock = stateId(Blocks.SNOW_BLOCK.defaultBlockState());
        seaLevel = settings.seaLevel();
        MaterialSystem sys = rs.surfaceSystem();
        try {
            String[] names = {"surfaceNoise", "surfaceSecondaryNoise", "clayBandsOffsetNoise", "badlandsPillarNoise",
                    "badlandsPillarRoofNoise", "badlandsSurfaceNoise", "icebergPillarNoise", "icebergPillarRoofNoise",
                    "icebergSurfaceNoise"};
            for (int i = 0; i < names.length; i++) noiseStacks[i] = host.stackIdPublic(ExactPlan.fieldIn(sys, names[i]));
            BlockState[] bands = (BlockState[]) ExactPlan.fieldIn(sys, "clayBands");
            for (int i = 0; i < 192; i++) {
                clayBands[i] = stateId(bands[i]);
                changesClass(bands[i]);
            }
            long[] root = seeds((PositionalRandomFactory) ExactPlan.fieldIn(sys, "noiseRandom"));
            rootSeedLo = root[0];
            rootSeedHi = root[1];
        } catch (ReflectiveOperationException e) {
            unsupported.add("MaterialSystem (" + e.getMessage() + ")");
        }
        // Biomtabelle: Grundtemperatur, Modifikator FROZEN, Sonderbiome der Erweiterungen
        biomeTable = new int[biomes.biomes.size() * BIOME_WORDS];
        for (int i = 0; i < biomes.biomes.size(); i++) {
            Holder<Biome> b = biomes.biomes.get(i);
            float base = b.value().getBaseTemperature();
            int flags = 0;
            try {
                Object climate = ExactPlan.fieldIn(b.value(), "climateSettings");
                Object mod = ExactPlan.get(climate, "temperatureModifier");
                if (mod.toString().equalsIgnoreCase("frozen") || ((Enum<?>) mod).name().equals("FROZEN")) flags |= 1;
            } catch (ReflectiveOperationException e) {
                unsupported.add("Biomklima");
            }
            if (b.is(Biomes.ERODED_BADLANDS)) flags |= 2;
            if (b.is(Biomes.FROZEN_OCEAN) || b.is(Biomes.DEEP_FROZEN_OCEAN)) flags |= 4;
            biomeTable[i * BIOME_WORDS] = Float.floatToRawIntBits(base);
            biomeTable[i * BIOME_WORDS + 1] = flags;
        }
        compileRule(settings.materialRule().value());
        rules.add(R_END);
        markRuleOutputsPerBiome();
    }

    /** Biom-Flag: in diesem Biom kann eine Regel Luft setzen (Spaltenhoehe aendert sich). */
    public static final int BF_RULE_AIR = 256;
    /** Biom-Flag: in diesem Biom kann eine Regel Wasser/Lava setzen (Eisberg-Muster). */
    public static final int BF_RULE_FLUID = 512;

    /**
     * Abstrakte Auswertung des Regelcodes je Biom: Biom-Bedingungen (auch negiert) sind bekannt,
     * alle anderen offen (beide Zweige). Erreichbare Ausgaben mit Luft/Fluessigkeit setzen die
     * Flags; die Vorhersage (Pass 0) darf die Regeln nur fuer Spalten ohne solche Biome auslassen.
     */
    private void markRuleOutputsPerBiome() {
        int bandClass = 0;
        for (int st : clayBands) bandClass |= stateClass(st);
        for (int bi = 0; bi < biomes.biomes.size(); bi++) {
            int cls = 0;
            java.util.BitSet seen = new java.util.BitSet();
            java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
            todo.push(0);
            while (!todo.isEmpty()) {
                int pc = todo.pop();
                if (pc < 0 || pc >= rules.size() || seen.get(pc)) continue;
                seen.set(pc);
                int op = rules.getInt(pc);
                if (op == R_IF) {
                    int v = condValue(rules.getInt(pc + 1), bi, 0);
                    if (v != 0) todo.push(pc + 3);
                    if (v != 1) todo.push(rules.getInt(pc + 2));
                } else if (op == R_BLOCK) {
                    cls |= stateClass(rules.getInt(pc + 1));
                } else if (op == R_BANDS) {
                    cls |= bandClass;
                } else if (op == R_ORE) {
                    int o = rules.getInt(pc + 1) * ORE_WORDS;
                    for (int k = 0; k < 3; k++) cls |= stateClass(ores.getInt(o + k));
                    todo.push(pc + 2);
                }
            }
            biomeTable[bi * BIOME_WORDS + 1] |= cls;
        }
    }

    private static boolean hasFluid(int id) {
        BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
        return st != null && !st.getFluidState().isEmpty();
    }

    private int stateClass(int id) {
        return id == airState ? BF_RULE_AIR : (id == waterState || id == lavaState) ? BF_RULE_FLUID : 0;
    }

    /** 1 wahr, 0 falsch, 2 offen (nur Biom-Bedingungen sind je Biom bekannt). */
    private int condValue(int c, int bi, int depth) {
        int b = c * COND_WORDS;
        int type = conds.getInt(b);
        if (type == C_NOT && depth < 16) {
            int v = condValue(conds.getInt(b + 1), bi, depth + 1);
            return v == 2 ? 2 : 1 - v;
        }
        if (type == C_BIOME) {
            int words = conds.getInt(b + 2);
            if (bi >= words * 32) return 0;
            return (extra.getInt(conds.getInt(b + 1) + (bi >> 5)) & (1 << (bi & 31))) != 0 ? 1 : 0;
        }
        return 2;
    }

    private static long[] seeds(PositionalRandomFactory f) throws ReflectiveOperationException {
        if (!(f instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory))
            throw new ReflectiveOperationException("Zufall " + f.getClass().getSimpleName());
        return new long[]{(long) ExactPlan.fieldIn(f, "seedLo"), (long) ExactPlan.fieldIn(f, "seedHi")};
    }

    // ---------------------------------------------------------------------------------------
    private void compileRule(MaterialRule r) {
        if (r instanceof MaterialRule.HolderHolder h) {
            compileRule(h.holder().value());
        } else if (r instanceof SequenceRule seq) {
            for (MaterialRule child : seq.sequence()) compileRule(child);
        } else if (r instanceof ConditionRule c) {
            int cond = cond(c.ifTrue());
            int at = rules.size();
            rules.add(R_IF);
            rules.add(cond);
            rules.add(0); // Sprungziel
            compileRule(c.thenRun());
            rules.set(at + 2, rules.size());
        } else if (r instanceof BlockRule b) {
            rules.add(R_BLOCK);
            rules.add(stateId(b.resultState()));
            changesClass(b.resultState());
        } else if (r instanceof BandlandsRule) {
            rules.add(R_BANDS);
        } else if (r instanceof OreVeinRule o) {
            int idx = ores.size() / ORE_WORDS;
            try {
                long[] s = seeds(rs.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore")));
                ExactPointProgram.Entry gap = points.compile(rs.getSampler(o.fillerGap()));
                oreDensity.add(o.density());
                oreRichness.add(o.richness());
                oreGap.add(gap);
                changesClass(o.oreBlock());
                changesClass(o.rawOreBlock());
                changesClass(o.fillerBlock());
                ores.add(stateId(o.oreBlock()));
                ores.add(stateId(o.rawOreBlock()));
                ores.add(stateId(o.fillerBlock()));
                ores.add(Float.floatToRawIntBits(o.rawOreChance()));
                ores.add(-1); // Dichte-Aufgabe (Scratch-Offset), setzt der Aufrufer
                ores.add(-1); // Reichtum
                ores.add(gap.start());
                ores.add((int) s[0]);
                ores.add((int) (s[0] >>> 32));
                ores.add((int) s[1]);
                ores.add((int) (s[1] >>> 32));
                ores.add(0);
            } catch (ReflectiveOperationException e) {
                unsupported.add("Erzader (" + e.getMessage() + ")");
            }
            rules.add(R_ORE);
            rules.add(idx);
        } else {
            unsupported.add("Regel " + r.getClass().getSimpleName());
        }
    }

    private int cond(MaterialCondition c) {
        if (c instanceof MaterialCondition.HolderHolder h) return cond(h.holder().value());
        Integer known = condIds.get(c);
        if (known != null) return known;
        int[] w = new int[COND_WORDS];
        if (c instanceof NotCondition n) {
            w[0] = C_NOT;
            w[1] = cond(n.target());
        } else if (c instanceof YCondition y) {
            w[0] = C_Y;
            w[1] = anchor(y.anchor());
            w[2] = y.surfaceDepthMultiplier();
            w[3] = y.addStoneDepth() ? 1 : 0;
        } else if (c instanceof WaterCondition wc) {
            w[0] = C_WATER;
            w[1] = wc.offset();
            w[2] = wc.surfaceDepthMultiplier();
            w[3] = wc.addStoneDepth() ? 1 : 0;
        } else if (c instanceof StoneDepthCondition s) {
            w[0] = C_STONE;
            w[1] = s.offset();
            w[2] = s.addSurfaceDepth() ? 1 : 0;
            w[3] = s.secondaryDepthRange();
            w[4] = s.surfaceType() == CaveSurface.CEILING ? 1 : 0;
        } else if (c instanceof BiomeCondition b) {
            w[0] = C_BIOME;
            int words = (biomes.biomes.size() + 31) / 32;
            w[1] = extra.size();
            w[2] = words;
            int[] bits = new int[words];
            HolderSet<Biome> set = b.biomes();
            for (int i = 0; i < biomes.biomes.size(); i++) if (set.contains(biomes.biomes.get(i))) bits[i >> 5] |= 1 << (i & 31);
            for (int x : bits) extra.add(x);
        } else if (c instanceof NoiseThresholdCondition n) {
            w[0] = C_NOISE;
            try {
                w[1] = host.stackIdPublic(rs.getOrCreateNoise(n.noise()));
            } catch (ReflectiveOperationException e) {
                unsupported.add("Rauschen " + n.noise());
            }
            w[2] = n.is3d() ? 1 : 0;
            long mn = Double.doubleToRawLongBits(n.minThreshold()), mx = Double.doubleToRawLongBits(n.maxThreshold());
            w[3] = (int) mn;
            w[4] = (int) (mn >>> 32);
            w[5] = (int) mx;
            w[6] = (int) (mx >>> 32);
        } else if (c instanceof VerticalGradientCondition v) {
            w[0] = C_VGRAD;
            w[1] = anchor(v.trueAtAndBelow());
            w[2] = anchor(v.falseAtAndAbove());
            try {
                long[] s = seeds(rs.getOrCreateRandomFactory(v.randomName()));
                w[3] = (int) s[0];
                w[4] = (int) (s[0] >>> 32);
                w[5] = (int) s[1];
                w[6] = (int) (s[1] >>> 32);
            } catch (ReflectiveOperationException e) {
                unsupported.add("Gradient-Zufall " + v.randomName());
            }
        } else if (c == SteepCondition.INSTANCE) {
            w[0] = C_STEEP;
        } else if (c == HoleCondition.INSTANCE) {
            w[0] = C_HOLE;
        } else if (c == AbovePreliminarySurfaceCondition.INSTANCE) {
            w[0] = C_ABOVE;
        } else if (c == TemperatureCondition.INSTANCE) {
            w[0] = C_TEMP;
        } else {
            unsupported.add("Bedingung " + c.getClass().getSimpleName());
        }
        int id = conds.size() / COND_WORDS;
        for (int x : w) conds.add(x);
        condIds.put(c, id);
        return id;
    }

    private int anchor(VerticalAnchor a) {
        return a.resolveY(genCtx);
    }

    // ---- Simplex-Lagen der Biomtemperatur (Biome.TEMPERATURE_NOISE, FROZEN_TEMPERATURE_NOISE, BIOME_INFO_NOISE) ----
    private int tempLayer = -1, infoLayer = -1;
    private final int[] frozenLayers = new int[3];
    private final float[] frozenAmps = new float[3];
    private final double[] frozenFreqs = new double[3];
    private double simplexF2, simplexG2;

    /** Einmal nach dem Konstruktor: Simplex-Lagen im Wirt anlegen (vor encodeStacks/encodeLayers). */
    public void prepareSimplex() {
        try {
            Class<?> bc = net.minecraft.world.level.biome.Biome.class;
            var ft = bc.getDeclaredField("TEMPERATURE_NOISE");
            ft.setAccessible(true);
            tempLayer = host.simplexLayer(ft.get(null));
            infoLayer = host.simplexLayer(net.minecraft.world.level.biome.Biome.BIOME_INFO_NOISE);
            Object frozen = net.minecraft.world.level.biome.Biome.FROZEN_TEMPERATURE_NOISE;
            Object[] layers = (Object[]) ExactPlan.fieldIn(frozen, "layers");
            if (layers.length != 3) unsupported.add("Frost-Rauschen mit " + layers.length + " Lagen");
            for (int i = 0; i < 3 && i < layers.length; i++) {
                frozenLayers[i] = host.simplexLayer(ExactPlan.get(layers[i], "noise"));
                frozenAmps[i] = ((Number) ExactPlan.get(layers[i], "amplitude")).floatValue();
                frozenFreqs[i] = ((Number) ExactPlan.get(layers[i], "frequency")).doubleValue();
            }
            Class<?> sc = net.minecraft.world.level.levelgen.synth.SimplexNoise.class;
            var f2 = sc.getDeclaredField("F2");
            var g2 = sc.getDeclaredField("G2");
            f2.setAccessible(true);
            g2.setAccessible(true);
            simplexF2 = f2.getDouble(null);
            simplexG2 = g2.getDouble(null);
        } catch (ReflectiveOperationException e) {
            unsupported.add("Simplex (" + e.getMessage() + ")");
        }
    }

    /**
     * Parameterblock fuer wg_exact_surface.cu (Kopf 55 ints, danach Regeln, Bedingungen,
     * Adern, Biom-Bitsets, Biomtabelle, Tonbaender). Erst nach encodeStacks() des Wirts.
     */
    public int[] encode(int minY, int height, int maxY, long worldSeed) {
        IntArrayList a = new IntArrayList();
        for (int i = 0; i < 55; i++) a.add(0);
        a.set(0, defaultState);
        a.set(1, waterState);
        a.set(2, lavaState);
        a.set(3, airState);
        a.set(4, seaLevel);
        a.set(5, minY);
        a.set(6, height);
        a.set(7, maxY);
        int[] ns = encodeNoiseStacks();
        for (int i = 0; i < 9; i++) a.set(14 + i, ns[i]);
        a.set(23, (int) rootSeedLo);
        a.set(24, (int) (rootSeedLo >>> 32));
        a.set(25, (int) rootSeedHi);
        a.set(26, (int) (rootSeedHi >>> 32));
        a.set(27, preliminarySurfaceTask);
        long zoom = net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(worldSeed);
        a.set(28, (int) zoom);
        a.set(29, (int) (zoom >>> 32));
        a.set(30, packedIce);
        a.set(31, snowBlock);
        a.set(32, tempLayer);
        a.set(33, infoLayer);
        for (int i = 0; i < 3; i++) {
            a.set(34 + i, frozenLayers[i]);
            a.set(37 + i, Float.floatToRawIntBits(frozenAmps[i]));
            long fb = Double.doubleToRawLongBits(frozenFreqs[i]);
            a.set(40 + i * 2, (int) fb);
            a.set(41 + i * 2, (int) (fb >>> 32));
        }
        long f2 = Double.doubleToRawLongBits(simplexF2), g2 = Double.doubleToRawLongBits(simplexG2);
        a.set(46, (int) f2);
        a.set(47, (int) (f2 >>> 32));
        a.set(48, (int) g2);
        a.set(49, (int) (g2 >>> 32));
        a.set(50, height >> 2);
        a.set(51, net.minecraft.world.level.dimension.DimensionType.WAY_BELOW_MIN_Y);
        // SF_RULES_SOLID: Bit 0 = Regeln setzen nie Luft, Bit 1 = nie Wasser/Lava
        a.set(52, (rulesMayPlaceAir ? 0 : 1) | (rulesMayPlaceFluid ? 0 : 2));
        a.set(8, a.size());
        a.addAll(rules);
        a.set(9, a.size());
        for (int x : encodeConds()) a.add(x);
        a.set(10, a.size());
        a.addAll(ores);
        a.set(11, a.size());
        a.addAll(extra);
        a.set(12, a.size());
        for (int x : biomeTable) a.add(x);
        a.set(13, a.size());
        for (int x : clayBands) a.add(x);
        // SF_FLUIDS/SF_NFLUIDS: Regel-Ausgaben mit Fluessigkeit (MaterialSystem markiert sie fuer Post-Processing)
        it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet fl = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
        for (int i = 0; i + 1 < rules.size(); ) {
            int op = rules.getInt(i);
            if (op == R_IF) i += 3;
            else if (op == R_BLOCK) { if (hasFluid(rules.getInt(i + 1))) fl.add(rules.getInt(i + 1)); i += 2; }
            else if (op == R_ORE) i += 2;
            else i += 1;
        }
        for (int o = 0; o < ores.size(); o += ORE_WORDS) for (int k = 0; k < 3; k++) if (hasFluid(ores.getInt(o + k))) fl.add(ores.getInt(o + k));
        for (int b : clayBands) if (hasFluid(b)) fl.add(b);
        a.set(53, a.size());
        a.set(54, fl.size());
        for (int x : fl) a.add(x);
        return a.toIntArray();
    }

    /** Stapel-Indizes (noiseStacks, C_NOISE) als Offsets nach encodeStacks() des Wirts. */
    public int[] encodeConds() {
        int[] a = conds.toIntArray();
        for (int i = 0; i < a.length; i += COND_WORDS) if (a[i] == C_NOISE) a[i + 1] = host.stackOffsets[a[i + 1]];
        return a;
    }

    public int[] encodeNoiseStacks() {
        int[] a = new int[noiseStacks.length];
        for (int i = 0; i < a.length; i++) a[i] = host.stackOffsets[noiseStacks[i]];
        return a;
    }
}
