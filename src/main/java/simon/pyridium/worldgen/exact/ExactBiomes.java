package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * Biome der NOISE-Stufe (26.3 {@code MultiNoiseBiomeSource.createResolverForChunk}) fuer die
 * GPU: Vanillas R-Baum (Climate.RTree) als flache Knotenliste in Kind-Reihenfolge.
 *
 * <p>Suche exakt wie Vanilla: {@code SubTree.search} findet immer die minimale Distanz m;
 * zurueck kommt der Startkandidat (letztes Ergebnis des Threads), wenn er ebenfalls m hat,
 * sonst das erste Blatt mit Distanz m in Tiefensuch-Reihenfolge. Die GPU sucht je Quart ohne
 * Kandidat (parallel) und loest die Gleichstaende danach je Chunk in Vanillas Fuellreihenfolge.
 * Vanillas threadlokaler Startwert zu Chunkbeginn ist nicht deterministisch; hier: kein Kandidat.
 *
 * <p>Knoten: {@link #NODE_WORDS} ints = 7 x (min, max) als long-Paare (28), Kind-Start,
 * Kind-Anzahl (0 = Blatt), Biom-Index des Blatts.
 */
public final class ExactBiomes {
    public static final int NODE_WORDS = 31;
    public final List<Holder<Biome>> biomes = new ArrayList<>();
    public final List<String> unsupported = new ArrayList<>();
    public int[] nodes = new int[0];
    public int nodeCount;
    private final Map<Object, Integer> biomeIndex = new IdentityHashMap<>();

    public ExactBiomes(BiomeSource source) {
        if (!(source instanceof MultiNoiseBiomeSource ms)) {
            unsupported.add("Biomquelle " + source.getClass().getSimpleName());
            return;
        }
        try {
            var m = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
            m.setAccessible(true);
            Climate.ParameterList<?> list = (Climate.ParameterList<?>) m.invoke(ms);
            Object tree = ExactPlan.fieldIn(list, "index");
            Object root = ExactPlan.fieldIn(tree, "root");
            List<Object> order = new ArrayList<>();
            IntArrayList data = new IntArrayList();
            // Breitenweise Ablage: Kinder eines Knotens liegen zusammenhaengend, Reihenfolge wie Vanilla
            order.add(root);
            List<int[]> childRefs = new ArrayList<>();
            for (int i = 0; i < order.size(); i++) {
                Object node = order.get(i);
                Object[] kids = node.getClass().getSimpleName().equals("SubTree")
                        ? (Object[]) ExactPlan.fieldIn(node, "children") : null;
                int start = order.size();
                if (kids != null) for (Object k : kids) order.add(k);
                childRefs.add(new int[]{kids == null ? 0 : start, kids == null ? 0 : kids.length});
            }
            nodeCount = order.size();
            nodes = new int[nodeCount * NODE_WORDS];
            for (int i = 0; i < nodeCount; i++) {
                Object node = order.get(i);
                Climate.Parameter[] ps = (Climate.Parameter[]) ExactPlan.fieldIn(node, "parameterSpace");
                int b = i * NODE_WORDS;
                for (int d = 0; d < 7; d++) {
                    nodes[b + d * 4] = (int) ps[d].min();
                    nodes[b + d * 4 + 1] = (int) (ps[d].min() >>> 32);
                    nodes[b + d * 4 + 2] = (int) ps[d].max();
                    nodes[b + d * 4 + 3] = (int) (ps[d].max() >>> 32);
                }
                nodes[b + 28] = childRefs.get(i)[0];
                nodes[b + 29] = childRefs.get(i)[1];
                if (childRefs.get(i)[1] == 0) {
                    @SuppressWarnings("unchecked")
                    Holder<Biome> v = (Holder<Biome>) ExactPlan.fieldIn(node, "value");
                    nodes[b + 30] = biomeIndex.computeIfAbsent(v, k -> {
                        biomes.add(v);
                        return biomes.size() - 1;
                    });
                } else {
                    nodes[b + 30] = -1;
                }
            }
            data.size(0);
        } catch (ReflectiveOperationException e) {
            unsupported.add("R-Baum (" + e.getMessage() + ")");
        }
    }

    public int indexOf(Holder<Biome> b) {
        Integer i = biomeIndex.get(b);
        return i == null ? -1 : i;
    }
}
