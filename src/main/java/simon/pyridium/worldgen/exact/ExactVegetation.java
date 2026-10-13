package simon.pyridium.worldgen.exact;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

/**
 * Pyridium: Baumdichte und -arten eines Bioms aus den Features des Datapacks (VEGETAL_DECORATION), statt
 * aus dem Biomnamen zu raten: Datapack-Biome bekommen so ihre eigenen Baeume. Erwartungswerte aus den
 * Platzierungsmodifikatoren (count, Rarity), Auswahl-Features nach Gewicht. Einmalige Tabellenvorbereitung.
 */
public record ExactVegetation(float treesPerBlock, BlockState leaves, BlockState log) {
    public static final ExactVegetation NONE = new ExactVegetation(0f, null, null);

    private record Tree(TreeFeature feature, double perChunk) {
    }

    public static ExactVegetation of(Biome biome) {
        List<Tree> trees = new ArrayList<>();
        var features = biome.getGenerationSettings().features();
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (step < features.size()) {
            for (Holder<PlacedFeature> pf : features.get(step)) collect(pf.value(), 1.0, trees, 0);
        }
        double total = 0;
        Tree best = null;
        for (Tree t : trees) {
            total += t.perChunk;
            if (best == null || t.perChunk > best.perChunk) best = t;
        }
        if (best == null || total <= 0) return NONE;
        BlockState leaves = state(best.feature.foliageProvider().value());
        BlockState log = state(best.feature.trunkProvider().value());
        return new ExactVegetation((float) (total / 256.0), leaves, log);
    }

    private static void collect(PlacedFeature pf, double weight, List<Tree> out, int depth) {
        if (depth > 6 || weight <= 0) return;
        double m = weight;
        RandomSource r = RandomSource.create(1234);
        for (PlacementModifier mod : pf.placement()) {
            if (mod instanceof RepeatingPlacement rp) {
                double sum = 0;
                for (int i = 0; i < 256; i++) sum += rp.count(r, BlockPos.ZERO);
                m *= sum / 256.0;
            } else if (mod instanceof RarityFilter rf) {
                m /= Math.max(1, rf.chance());
            }
        }
        Feature f = pf.feature().value();
        if (f instanceof TreeFeature t) {
            out.add(new Tree(t, m));
        } else if (f instanceof RandomSelectorFeature rs) {
            double rest = 1.0;
            for (WeightedPlacedFeature w : rs.features()) {
                collect(w.feature().value(), m * w.chance(), out, depth + 1);
                rest -= w.chance();
            }
            collect(rs.defaultFeature().value(), m * Math.max(0, rest), out, depth + 1);
        } else if (f instanceof SimpleRandomSelectorFeature sr) {
            int n = sr.features().size();
            for (Holder<PlacedFeature> h : sr.features()) collect(h.value(), m / Math.max(1, n), out, depth + 1);
        } else if (f instanceof RandomBooleanSelectorFeature rb) {
            collect(rb.featureTrue().value(), m * 0.5, out, depth + 1);
            collect(rb.featureFalse().value(), m * 0.5, out, depth + 1);
        }
    }

    private static BlockState state(net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider p) {
        try {
            return p.getState(null, RandomSource.create(7), BlockPos.ZERO);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
