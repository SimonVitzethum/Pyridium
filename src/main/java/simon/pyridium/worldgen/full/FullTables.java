package simon.pyridium.worldgen.full;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.worldgen.exact.ExactPlan;

/**
 * Compiles registry/datapack data into the plain tables of the full-chunk GPU stages: per-state flags
 * (wg_full_common.cuh F_*), cave/canyon parameters from the biomes' carvers, ore parameters from the
 * common underground ore features (CountPlacement, RarityFilter, HeightRangePlacement, ore targets) and the
 * storage-bits table of Minecraft's palette strategy. Layouts match wg_carve.cuh structs byte for byte.
 */
public final class FullTables {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/full-tables");

    public static final int F_AIR = 1, F_FLUID = 2, F_MOTION = 4, F_LEAVES = 8, F_CARVABLE = 16, F_ORE_STONE = 32, F_ORE_DEEP = 64,
            F_BEDROCK = 128, F_OPAQUE = 256, F_DAMP = 512, F_SOLID_GROUND = 1024, F_GRASS = 2048, F_SAND = 4096, F_WATER = 8192,
            F_LAVA = 16384, F_HM_MB = 65536, F_HM_NL = 131072, F_HASFLUID = 262144, F_RTICK = 524288, F_FTICK = 1048576;

    public static final int MAX_ORE = 64;
    public static final int CFG_BITS_TABLE = 16, CFG_DECOR = 300;

    public final int[] flags;
    public final byte[] carve, ore;
    public final int oreCount;
    public final List<String> report = new ArrayList<>();

    private FullTables(int[] flags, byte[] carve, byte[] ore, int oreCount) {
        this.flags = flags;
        this.carve = carve;
        this.ore = ore;
        this.oreCount = oreCount;
    }

    /** Flags je Block-State-ID. */
    public static int[] buildFlags() {
        int n = Block.BLOCK_STATE_REGISTRY.size();
        int[] f = new int[Math.max(n, 1)];
        TagKey<Block> carver = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("overworld_carver_replaceables"));
        var surf = Heightmap.Types.WORLD_SURFACE.isOpaque();
        var ocean = Heightmap.Types.OCEAN_FLOOR.isOpaque();
        var mb = Heightmap.Types.MOTION_BLOCKING.isOpaque();
        var nl = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();
        for (int id = 0; id < n; id++) {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (st == null) continue;
            int v = 0;
            if (st.isAir()) v |= F_AIR;
            if (st.getBlock() instanceof LiquidBlock) v |= F_FLUID;
            if (ocean.test(st)) v |= F_MOTION;
            if (mb.test(st)) v |= F_HM_MB;
            if (nl.test(st)) v |= F_HM_NL;
            if (!surf.test(st)) v |= F_AIR;           // WORLD_SURFACE predicate = not air (keeps both notions equal)
            if (st.is(BlockTags.LEAVES)) v |= F_LEAVES;
            if (st.is(carver) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.TERRACOTTA)
                    || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.SNOW_BLOCK)
                    || st.is(Blocks.PACKED_ICE) || st.is(Blocks.CALCITE) || st.is(Blocks.RED_SAND) || st.is(Blocks.PODZOL))
                v |= F_CARVABLE;
            if (st.is(BlockTags.STONE_ORE_REPLACEABLES)) v |= F_ORE_STONE;
            if (st.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) v |= F_ORE_DEEP;
            if (st.is(Blocks.BEDROCK)) v |= F_BEDROCK;
            int damp = st.getLightDampening();
            if (damp >= 15) v |= F_OPAQUE;
            else if (damp > 0) v |= F_DAMP;
            if (st.is(Blocks.GRASS_BLOCK)) v |= F_GRASS;
            if (st.is(BlockTags.DIRT)) v |= F_SOLID_GROUND;
            if (st.is(BlockTags.SAND)) v |= F_SAND;
            if (st.is(Blocks.WATER)) v |= F_WATER;
            if (st.is(Blocks.LAVA)) v |= F_LAVA;
            if (!st.getFluidState().isEmpty()) v |= F_HASFLUID;
            if (st.isRandomlyTicking()) v |= F_RTICK;
            if (st.getFluidState().isRandomlyTicking()) v |= F_FTICK;
            f[id] = v;
        }
        return f;
    }

    /** bits je Palettengroesse 0..256 aus Minecrafts Strategy (reflektiv), Index = Anzahl. */
    public static int[] bitsTable(LevelChunkSection sample) {
        int[] t = new int[257];
        try {
            var sf = net.minecraft.world.level.chunk.PalettedContainer.class.getDeclaredField("strategy");
            sf.setAccessible(true);
            var cm = net.minecraft.world.level.chunk.Strategy.class.getDeclaredMethod("getConfigurationForPaletteSize", int.class);
            cm.setAccessible(true);
            Object strategy = sf.get(sample.getStates());
            for (int i = 1; i <= 256; i++) t[i] = ((net.minecraft.world.level.chunk.Configuration) cm.invoke(strategy, i)).bitsInStorage();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    public static FullTables build(NoiseGeneratorSettings settings, Collection<Holder<Biome>> biomes, WorldGenerationContext ctx, long seed,
                                   int minY, int height) {
        int[] flags = buildFlags();
        int sea = settings.seaLevel();
        // ---- carvers ----
        Map<WorldCarver, Boolean> seen = new IdentityHashMap<>();
        double caveRate = 0, canyonRate = 0;
        for (Holder<Biome> b : biomes)
            for (Holder<WorldCarver> h : b.value().getGenerationSettings().getCarvers()) {
                WorldCarver c = h.value();
                if (seen.put(c, true) != null) continue;
                if (c instanceof CaveWorldCarver cc) {
                    IntProvider cnt = cc.count();
                    caveRate += cc.probability() * (cnt.minInclusive() + cnt.maxInclusive()) * 0.5;
                } else if (c instanceof CanyonWorldCarver cy) {
                    canyonRate += cy.probability();
                }
            }
        // 64x64 cell = 16 chunks; ~1.5 tunnels per start; 128x128 cell = 64 chunks
        float caveMean = (float) Math.clamp(caveRate * 16 * 1.5, 0, 20);
        float canyonChance = (float) Math.clamp(canyonRate * 64, 0, 1);
        ByteBuffer cb = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        cb.putLong(seed ^ 0x5DEECE66DL).putInt(minY).putInt(minY + height - 1).putInt(sea).putInt(minY + 10);
        cb.putFloat(caveMean).putFloat(0.15f).putFloat(1.0f).putFloat(canyonChance).putInt((caveMean > 0 ? 1 : 0) | (canyonChance > 0 ? 2 : 0)).putInt(0);
        // ---- ores: underground-ore features common to all biomes ----
        List<Object[]> entries = new ArrayList<>();
        Map<PlacedFeature, Integer> common = new LinkedHashMap<>();
        int biomeCount = 0;
        int step = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        for (Holder<Biome> b : biomes) {
            biomeCount++;
            var steps = b.value().getGenerationSettings().features();
            if (step >= steps.size()) continue;
            for (Holder<PlacedFeature> pf : steps.get(step)) common.merge(pf.value(), 1, Integer::sum);
        }
        ByteBuffer ob = ByteBuffer.allocate(16 + MAX_ORE * 40).order(ByteOrder.LITTLE_ENDIAN);
        ob.putLong(seed ^ 0x2545F4914F6CDD1DL).putInt(0).putInt(0);
        int count = 0;
        // gather placed ores; require presence in at least 60 % of biomes (common ores, not biome specials)
        List<String> skipped = new ArrayList<>();
        for (var e : common.entrySet()) {
            PlacedFeature pf = e.getKey();
            if (!(pf.feature().value() instanceof AbstractOreFeature of)) continue;
            if (e.getValue() * 10 < biomeCount * 6) {
                skipped.add(pf.feature().getRegisteredName() + " (nur " + e.getValue() + "/" + biomeCount + " Biome)");
                continue;
            }
            if (count >= MAX_ORE) break;
            float cnt = 1, rarity = 1;
            int[] range = {minY, minY + height - 1};
            boolean tri = false;
            for (PlacementModifier m : pf.placement()) {
                if (m instanceof CountPlacement cp) cnt *= (cp.count().minInclusive() + cp.count().maxInclusive()) * 0.5f;
                else if (m instanceof RarityFilter rf) rarity *= 1.0f / rf.chance();
                else if (m instanceof HeightRangePlacement hr) tri = height(hr.height(), ctx, range);
            }
            int stone = -1, deep = -1;
            BlockState stoneRef = Blocks.STONE.defaultBlockState(), deepRef = Blocks.DEEPSLATE.defaultBlockState();
            RandomSource rs = RandomSource.create(1);
            for (BlockReplacement br : of.targetStates()) {
                if (stone < 0 && br.target().test(stoneRef, net.minecraft.core.BlockPos.ZERO, rs)) stone = Block.BLOCK_STATE_REGISTRY.getId(br.state());
                if (deep < 0 && br.target().test(deepRef, net.minecraft.core.BlockPos.ZERO, rs)) deep = Block.BLOCK_STATE_REGISTRY.getId(br.state());
            }
            if (stone < 0 && deep < 0) continue;
            float vol = 256f * (range[1] - range[0] + 1);
            float lambda = cnt * rarity * (tri ? 2f : 1f) * 4096f / vol;
            float radius = Math.max(0.9f, (float) Math.cbrt(0.75 * Math.max(1, of.size()) / Math.PI));
            ob.putInt(range[0]).putInt(range[1]).putInt(tri ? 1 : 0).putFloat(lambda).putFloat(radius).putFloat(of.discardChanceOnAirExposure())
                    .putInt(stone).putInt(deep).putInt(count + 1).putInt(0);
            entries.add(new Object[]{pf.feature().getRegisteredName(), cnt, rarity, range[0], range[1], tri, of.size()});
            count++;
        }
        ob.putInt(8, count);
        FullTables t = new FullTables(flags, cb.array(), ob.array(), count);
        t.report.add(String.format("Carver: caveMean %.1f, canyonChance %.2f; %d Erz-Features (%s)", caveMean, canyonChance, count,
                skipped.isEmpty() ? "alle gemeinsamen" : "uebersprungen: " + skipped));
        for (Object[] en : entries)
            t.report.add(String.format("  ore %s count %.1f rarity %.2f y %s..%s %s size %s", en));
        LOG.info("{}", String.join("\n", t.report));
        return t;
    }

    /** Hoehe -> {min, max}, Rueckgabe: Dreiecksverteilung. */
    private static boolean height(Object hp, WorldGenerationContext ctx, int[] out) {
        try {
            String n = hp.getClass().getSimpleName();
            if (n.equals("ConstantHeight")) {
                int v = ((net.minecraft.world.level.levelgen.VerticalAnchor) ExactPlan.fieldIn(hp, "value")).resolveY(ctx);
                out[0] = out[1] = v;
                return false;
            }
            if (n.equals("WeightedListHeight")) return false;
            int lo = ((net.minecraft.world.level.levelgen.VerticalAnchor) ExactPlan.fieldIn(hp, "minInclusive")).resolveY(ctx);
            int hi = ((net.minecraft.world.level.levelgen.VerticalAnchor) ExactPlan.fieldIn(hp, "maxInclusive")).resolveY(ctx);
            out[0] = lo;
            out[1] = hi;
            return n.equals("TrapezoidHeight");
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
