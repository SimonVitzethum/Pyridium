package simon.pyridium.worldgen.exact;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

/**
 * Pyridium: Erz-Features der Biome (Datapack eingeschlossen, Schritt UNDERGROUND_ORES) als flache GPU-Tabelle
 * fuer wg_carve.cuh/wg_lod_ore.cuh. Einmalige Vorbereitung beim Laden. Je Feature: Versuche je Chunk,
 * Seltenheit, Hoehenverteilung (aus Stichproben des HeightProviders), Groesse, Austauschblock fuer Stein
 * und Tiefenschiefer sowie die Menge der Biome, die das Feature tragen.
 *
 * <p>Tabelle: [anzahl, woerterJeMaske, je Eintrag 10 Ints (minY, maxY, dreieck, count, rarityInv, size, discard,
 * stein, tiefenschiefer, salz), dann je Eintrag woerterJeMaske Biombits].
 */
public final class ExactOres {
    public final int[] table;
    /** Block-Zustands-IDs aller Erzbloecke (fuer die Attributtabelle) */
    public final int[] stateIds;
    public final int count;

    private record Entry(PlacedFeature pf, OreFeature ore, int[] bits) {
    }

    public ExactOres(ExactBiomes eb, WorldGenerationContext ctx) {
        int nb = Math.max(1, eb.biomes.size());
        int words = (nb + 31) / 32;
        Map<PlacedFeature, Entry> map = new IdentityHashMap<>();
        List<Entry> list = new ArrayList<>();
        int step = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        for (int b = 0; b < eb.biomes.size(); b++) {
            Biome biome = eb.biomes.get(b).value();
            var features = biome.getGenerationSettings().features();
            if (step >= features.size()) continue;
            for (Holder<PlacedFeature> h : features.get(step)) {
                PlacedFeature pf = h.value();
                if (!(pf.feature().value() instanceof OreFeature ore)) continue;
                Entry e = map.get(pf);
                if (e == null) {
                    e = new Entry(pf, ore, new int[words]);
                    map.put(pf, e);
                    list.add(e);
                }
                e.bits[b >> 5] |= 1 << (b & 31);
            }
        }
        IntArrayList t = new IntArrayList();
        IntLinkedOpenHashSet ids = new IntLinkedOpenHashSet();
        BlockState stone = Blocks.STONE.defaultBlockState(), deep = Blocks.DEEPSLATE.defaultBlockState();
        List<int[]> rows = new ArrayList<>();
        List<int[]> masks = new ArrayList<>();
        int salt = 0;
        for (Entry e : list) {
            if (rows.size() >= 64) break;
            RandomSource r = RandomSource.create(99 + salt);
            double cnt = 1, rarity = 1;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, mid = 0, samples = 0;
            boolean haveHeight = false;
            for (PlacementModifier m : e.pf.placement()) {
                if (m instanceof RepeatingPlacement rp) {
                    double sum = 0;
                    for (int i = 0; i < 256; i++) sum += rp.count(r, BlockPos.ZERO);
                    cnt *= sum / 256.0;
                } else if (m instanceof RarityFilter rf) {
                    rarity /= Math.max(1, rf.chance());
                } else if (m instanceof HeightRangePlacement hr) {
                    haveHeight = true;
                    int[] v = new int[512];
                    for (int i = 0; i < 512; i++) {
                        v[i] = hr.height().sample(r, ctx);
                        lo = Math.min(lo, v[i]);
                        hi = Math.max(hi, v[i]);
                    }
                    int third = (hi - lo) / 3;
                    for (int x : v) if (x >= lo + third && x <= hi - third) mid++;
                    samples = v.length;
                }
            }
            if (!haveHeight || cnt <= 0) continue;
            int stoneId = -1, deepId = -1;
            for (BlockReplacement br : e.ore.targetStates()) {
                if (stoneId < 0 && br.target().test(stone, BlockPos.ZERO, r)) stoneId = ExactMaterials.stateId(br.state());
                if (deepId < 0 && br.target().test(deep, BlockPos.ZERO, r)) deepId = ExactMaterials.stateId(br.state());
            }
            if (stoneId < 0 && deepId < 0) continue;
            if (stoneId >= 0) ids.add(stoneId);
            if (deepId >= 0) ids.add(deepId);
            // mehr als die Haelfte der Proben im mittleren Drittel: Dreiecksverteilung (Trapez)
            int triangle = mid * 2 > samples ? 1 : 0;
            rows.add(new int[]{lo, hi, triangle, Float.floatToRawIntBits((float) cnt), Float.floatToRawIntBits((float) (1.0 / Math.max(rarity, 1e-6))),
                e.ore.size(), Float.floatToRawIntBits(e.ore.discardChanceOnAirExposure()), stoneId, deepId, salt++});
            masks.add(e.bits);
        }
        count = rows.size();
        t.add(count);
        t.add(words);
        for (int[] row : rows) for (int x : row) t.add(x);
        for (int[] m : masks) for (int x : m) t.add(x);
        table = t.toIntArray();
        stateIds = ids.toIntArray();
    }
}
