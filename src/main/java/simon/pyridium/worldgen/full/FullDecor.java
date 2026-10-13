package simon.pyridium.worldgen.full;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import simon.pyridium.worldgen.exact.ExactPlan;

/**
 * Compiles the datapack's vegetal-decoration features of each biome into the per-biome table of wg_full_decor.cu
 * (tree rate and up to 4 tree kinds: trunk/leaf states, height, foliage radius and shape from the trunk and foliage
 * placers; up to 8 ground-cover block states with a per-chunk rate). Selector/sequence features are walked through
 * Feature.getSubFeatures(). This is a "vanilla-like" approximation, not Vanilla's per-feature algorithms.
 */
public final class FullDecor {
    public static final int STRIDE = 96;

    private FullDecor() {
    }

    private record Tree(int trunk, int[] leaves, int base, int rand, int radius, int shape) {
    }

    private static double avg(IntProvider p) {
        return (p.minInclusive() + p.maxInclusive()) * 0.5;
    }

    private static void collect(Feature f, int depth, List<Tree> trees, List<BlockState> cover) {
        if (depth > 6) return;
        try {
            if (f instanceof TreeFeature tf) {
                trees.add(tree(tf));
                return;
            }
            if (f instanceof SimpleBlockFeature sb) {
                BlockState s = sb.toPlace().value().getState(null, RandomSource.create(7), BlockPos.ZERO);
                if (s != null) cover.add(s);
                return;
            }
            f.getSubFeatures().forEach(h -> collect(h.value(), depth + 1, trees, cover));
        } catch (RuntimeException | ReflectiveOperationException e) {
            // unsupported provider: skip this leaf
        }
    }

    private static Tree tree(TreeFeature tf) throws ReflectiveOperationException {
        RandomSource rs = RandomSource.create(3);
        BlockState trunk = tf.trunkProvider().value().getState(null, rs, BlockPos.ZERO);
        BlockState leaf = tf.foliageProvider().value().getState(null, rs, BlockPos.ZERO);
        int[] leaves = new int[7];
        for (int d = 1; d <= 7; d++) {
            BlockState l = leaf.hasProperty(BlockStateProperties.DISTANCE) ? leaf.setValue(BlockStateProperties.DISTANCE, d) : leaf;
            if (l.hasProperty(BlockStateProperties.PERSISTENT)) l = l.setValue(BlockStateProperties.PERSISTENT, false);
            leaves[d - 1] = Block.BLOCK_STATE_REGISTRY.getId(l);
        }
        var tp = tf.trunkPlacer();
        int base = (Integer) ExactPlan.fieldIn(tp, "baseHeight");
        int ra = (Integer) ExactPlan.fieldIn(tp, "heightRandA"), rb = (Integer) ExactPlan.fieldIn(tp, "heightRandB");
        var fp = tf.foliagePlacer();
        int radius = (int) Math.round(avg((IntProvider) ExactPlan.fieldIn(fp, "radius")));
        String n = fp.getClass().getSimpleName();
        int shape = n.startsWith("Spruce") || n.startsWith("MegaPine") ? 1 : n.startsWith("Pine") ? 2 : n.startsWith("Acacia") ? 3
                : n.startsWith("Bush") || n.startsWith("Fancy") || n.startsWith("MegaJungle") || n.startsWith("DarkOak") ? 4 : 0;
        if (n.startsWith("MegaPine") || n.startsWith("MegaJungle") || n.startsWith("DarkOak")) radius = Math.max(radius, 3);
        return new Tree(Block.BLOCK_STATE_REGISTRY.getId(trunk), leaves, base, ra + rb, Math.max(1, radius), shape);
    }

    /** Table for the biomes in the given (biome pipe) order. */
    public static int[] build(List<Holder<Biome>> biomes) {
        int[] t = new int[biomes.size() * STRIDE];
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        for (int bi = 0; bi < biomes.size(); bi++) {
            double treeRate = 0;
            List<Tree> kinds = new ArrayList<>();
            Map<Integer, Double> cover = new LinkedHashMap<>();
            var steps = biomes.get(bi).value().getGenerationSettings().features();
            if (step < steps.size()) {
                HolderSet<PlacedFeature> set = steps.get(step);
                for (Holder<PlacedFeature> pf : set) {
                    double count = 1;
                    for (PlacementModifier m : pf.value().placement()) {
                        if (m instanceof CountPlacement cp) count *= avg(cp.count());
                        else if (m instanceof RarityFilter rf) count /= rf.chance();
                    }
                    List<Tree> trees = new ArrayList<>();
                    List<BlockState> cv = new ArrayList<>();
                    collect(pf.value().feature().value(), 0, trees, cv);
                    if (!trees.isEmpty()) {
                        treeRate += count;
                        for (Tree tr : trees) if (kinds.size() < 4 && kinds.stream().noneMatch(k -> k.trunk == tr.trunk && k.leaves[0] == tr.leaves[0])) kinds.add(tr);
                    } else if (!cv.isEmpty()) {
                        // random patches: ~8 plants per attempt; spread over the sampled states
                        for (BlockState s : cv) cover.merge(Block.BLOCK_STATE_REGISTRY.getId(s), count * 8.0 / cv.size(), Double::sum);
                    }
                }
            }
            int o = bi * STRIDE;
            t[o] = (int) Math.min(65535, Math.round(treeRate * 256));
            t[o + 1] = kinds.size();
            for (int k = 0; k < kinds.size(); k++) {
                Tree tr = kinds.get(k);
                int b = o + 2 + 12 * k;
                t[b] = tr.trunk;
                System.arraycopy(tr.leaves, 0, t, b + 1, 7);
                t[b + 8] = tr.base;
                t[b + 9] = tr.rand;
                t[b + 10] = tr.radius;
                t[b + 11] = tr.shape;
            }
            int j = 0;
            double total = 0;
            for (var e : cover.entrySet()) {
                if (j >= 8) break;
                double r = Math.min(80, e.getValue());
                total += r;
                t[o + 51 + 2 * j] = e.getKey();
                t[o + 52 + 2 * j] = (int) Math.round(r * 256);
                j++;
            }
            t[o + 50] = j;
        }
        return t;
    }
}
