package simon.pyridium.worldgen.structures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prozessorlisten (worldgen/processor_list) zu kleinen GPU-Programmen (Operationen auf dem Blockzustand je Voxel,
 * Zufall aus dem Positions-Hash wie in Vanilla: {@code RandomSource.create(Mth.getSeed(pos))}).
 *
 * <p>Unterstuetzt: {@code rule} (Eingabe-Pr&auml;dikate always_true, block_match, blockstate_match, random_block_match,
 * random_blockstate_match, tag_match; Ort-Praedikat always_true), {@code block_rot}, {@code capped} (ohne Zaehlgrenze),
 * {@code block_ignore}. Nicht uebersetzbar und deshalb uebersprungen: Regeln mit Ort-Praedikaten, die den Weltblock
 * pruefen (Wasser/Eis unter Strassen), Positions-Praedikate, protected_blocks (Welt abhaengig), block_age,
 * blackstone_replace.
 *
 * <p>Code je Liste: {@code [nOps, op...]}; op RULE = {@code [1, nRules, (inKind, inA, inB, outState)*]}, op ROT =
 * {@code [2, integrityBits, tagIdx]} (-1: alle Bloecke), op IGNORE = {@code [3, tagIdx]}. inKind: 0 always_true,
 * 1 block_match (inA Block-Id), 2 blockstate_match (inA Zustands-Id), 3 random_block_match (inA Block, inB
 * Wahrscheinlichkeit als float-Bits), 4 random_blockstate_match, 5 tag_match (inA Tag-Index).
 */
final class ProcessorCompiler {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/structures");
    private final StructureCompiler c;
    private final StructureTables t;
    private final Map<String, Integer> cache = new HashMap<>();
    private final Map<String, Integer> tagIndex = new HashMap<>();
    private final List<Set<Integer>> tagBlocks = new ArrayList<>();
    private final Map<String, Set<Integer>> tagResolved = new HashMap<>();
    int skippedRules;

    ProcessorCompiler(StructureCompiler c, StructureTables t) {
        this.c = c;
        this.t = t;
    }

    /** Index der Prozessorliste (Name oder Inline-Definition); -1 = keine Operationen */
    int indexOf(JsonElement e) {
        String key = e.toString();
        Integer have = cache.get(key);
        if (have != null) return have;
        JsonObject list;
        if (e.isJsonPrimitive()) {
            String id = e.getAsString();
            if (id.equals("minecraft:empty")) {
                cache.put(key, -1);
                return -1;
            }
            List<JsonElement> stack = c.data().jsonStack("worldgen/processor_list", id);
            if (stack.isEmpty()) {
                LOG.warn("Prozessorliste {} nicht gefunden", id);
                cache.put(key, -1);
                return -1;
            }
            list = stack.get(stack.size() - 1).getAsJsonObject();
        } else {
            list = e.getAsJsonObject();
        }
        List<int[]> ops = new ArrayList<>();
        JsonArray procs = list.has("processors") ? list.getAsJsonArray("processors") : new JsonArray();
        for (JsonElement p : procs) compileProcessor(p.getAsJsonObject(), ops);
        int idx = -1;
        if (!ops.isEmpty()) {
            idx = t.procOff.size();
            t.procOff.add(t.procCode.size());
            t.procCode.add(ops.size());
            for (int[] op : ops) for (int w : op) t.procCode.add(w);
        }
        cache.put(key, idx);
        return idx;
    }

    private void compileProcessor(JsonObject p, List<int[]> ops) {
        String type = p.get("processor_type").getAsString();
        switch (type) {
            case "minecraft:rule" -> {
                List<Integer> words = new ArrayList<>();
                int n = 0;
                for (JsonElement re : p.getAsJsonArray("rules")) {
                    JsonObject r = re.getAsJsonObject();
                    JsonObject loc = r.has("location_predicate") ? r.getAsJsonObject("location_predicate") : null;
                    boolean locOk = loc == null || loc.get("predicate_type").getAsString().equals("minecraft:always_true");
                    boolean posOk = !r.has("position_predicate") || r.getAsJsonObject("position_predicate").get("predicate_type").getAsString().equals("minecraft:always_true");
                    if (!locOk || !posOk) {
                        skippedRules++;
                        continue;
                    }
                    int[] in = input(r.getAsJsonObject("input_predicate"));
                    if (in == null) {
                        skippedRules++;
                        continue;
                    }
                    int out = state(r.get("output_state"));
                    if (out < 0) {
                        skippedRules++;
                        continue;
                    }
                    words.add(in[0]); words.add(in[1]); words.add(in[2]); words.add(out);
                    n++;
                }
                if (n > 0) {
                    int[] op = new int[2 + words.size()];
                    op[0] = 1;
                    op[1] = n;
                    for (int i = 0; i < words.size(); i++) op[2 + i] = words.get(i);
                    ops.add(op);
                }
            }
            case "minecraft:block_rot" -> {
                int tag = -1;
                if (p.has("rottable_blocks")) tag = tag(p.get("rottable_blocks").getAsString());
                ops.add(new int[]{2, Float.floatToRawIntBits(p.get("integrity").getAsFloat()), tag});
            }
            case "minecraft:capped" -> compileProcessor(p.getAsJsonObject("delegate"), ops);
            case "minecraft:block_ignore" -> {
                int tag = tagOfBlocks(p.getAsJsonArray("blocks"));
                ops.add(new int[]{3, tag});
            }
            default -> {
                // protected_blocks, block_age, blackstone_replace ...: nicht uebersetzt
            }
        }
    }

    private int[] input(JsonObject o) {
        String type = o.get("predicate_type").getAsString();
        switch (type) {
            case "minecraft:always_true":
                return new int[]{0, 0, 0};
            case "minecraft:block_match":
                return new int[]{1, blockId(o.get("block").getAsString()), 0};
            case "minecraft:blockstate_match":
                return new int[]{2, state(o.get("block_state")), 0};
            case "minecraft:random_block_match":
                return new int[]{3, blockId(o.get("block").getAsString()), Float.floatToRawIntBits(o.get("probability").getAsFloat())};
            case "minecraft:random_blockstate_match":
                return new int[]{4, state(o.get("block_state")), Float.floatToRawIntBits(o.get("probability").getAsFloat())};
            case "minecraft:tag_match":
                return new int[]{5, tag(o.get("tag").getAsString()), 0};
            default:
                return null;
        }
    }

    private static int blockId(String id) {
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        return BuiltInRegistries.BLOCK.getId(b);
    }

    private int state(JsonElement e) {
        try {
            BlockState s;
            if (e.isJsonPrimitive()) s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, e.getAsString(), false).blockState();
            else s = BlockState.CODEC.parse(JsonOps.INSTANCE, e).getOrThrow();
            return Block.BLOCK_STATE_REGISTRY.getId(s);
        } catch (Exception ex) {
            LOG.warn("Blockzustand {} nicht lesbar: {}", e, ex.toString());
            return -1;
        }
    }

    // ---- Block-Tags ----
    private int tag(String idOrHash) {
        String id = idOrHash.startsWith("#") ? idOrHash.substring(1) : idOrHash;
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        Integer i = tagIndex.get(id);
        if (i != null) return i;
        int idx = tagBlocks.size();
        tagIndex.put(id, idx);
        tagBlocks.add(resolve(id, new HashSet<>()));
        return idx;
    }

    private int tagOfBlocks(JsonArray blocks) {
        Set<Integer> set = new HashSet<>();
        for (JsonElement b : blocks) {
            JsonObject o = b.isJsonObject() ? b.getAsJsonObject() : null;
            String name = o != null && o.has("Name") ? o.get("Name").getAsString() : b.isJsonPrimitive() ? b.getAsString() : o != null && o.has("id") ? o.get("id").getAsString() : null;
            if (name != null) set.add(blockId(name));
        }
        int idx = tagBlocks.size();
        tagBlocks.add(set);
        return idx;
    }

    private Set<Integer> resolve(String tag, Set<String> visiting) {
        Set<Integer> cached = tagResolved.get(tag);
        if (cached != null) return cached;
        if (!visiting.add(tag)) return Set.of();
        Set<Integer> out = new HashSet<>();
        for (JsonElement e : c.data().jsonStack("tags/block", tag)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("replace") && o.get("replace").getAsBoolean()) out.clear();
            for (JsonElement v : o.getAsJsonArray("values")) {
                String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (s.startsWith("#")) {
                    String sub = s.substring(1);
                    out.addAll(resolve(sub.indexOf(':') < 0 ? "minecraft:" + sub : sub, visiting));
                } else {
                    out.add(blockId(s));
                }
            }
        }
        visiting.remove(tag);
        tagResolved.put(tag, out);
        return out;
    }

    /** Tabellen abschliessen: Zustand -&gt; Block und Tag-Bitmengen */
    void finish() {
        int states = Block.BLOCK_STATE_REGISTRY.size();
        int[] sb = new int[states];
        for (int i = 0; i < states; i++) {
            BlockState s = Block.BLOCK_STATE_REGISTRY.byId(i);
            sb[i] = s == null ? -1 : BuiltInRegistries.BLOCK.getId(s.getBlock());
        }
        t.stateBlock = sb;
        int blocks = BuiltInRegistries.BLOCK.size();
        t.tagWords = (blocks + 31) / 32;
        for (Set<Integer> set : tagBlocks) {
            int base = t.tagBits.size();
            for (int i = 0; i < t.tagWords; i++) t.tagBits.add(0);
            for (int b : set) if (b >= 0) t.tagBits.set(base + (b >> 5), t.tagBits.getInt(base + (b >> 5)) | (1 << (b & 31)));
        }
        if (skippedRules > 0) LOG.info("Prozessoren: {} Regeln mit Welt-/Positionspraedikaten uebersprungen", skippedRules);
    }
}
