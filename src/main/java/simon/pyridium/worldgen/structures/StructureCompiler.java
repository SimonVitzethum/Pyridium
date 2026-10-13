package simon.pyridium.worldgen.structures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kompiliert die Strukturregistry (worldgen/structure_set, worldgen/structure, worldgen/template_pool,
 * Vorlagen-NBT, Biom-Tags; Vanilla und Datapacks) EINMAL beim Laden zu flachen GPU-Tabellen
 * ({@link StructureTables}), analog zu {@code ExactPlan} fuer Dichtefunktionen. Zur Laufzeit wird
 * nichts mehr aus der Registry gelesen; die Generierung laeuft ausschliesslich in CUDA.
 */
public final class StructureCompiler {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/structures");

    private final StructureData data;
    private final StructureTables t = new StructureTables();
    private final TemplateBaker baker = new TemplateBaker(t);
    private final ProcessorCompiler procs = new ProcessorCompiler(this, t);
    private final Map<String, Integer> poolIndex = new HashMap<>();
    private final Map<String, Integer> elemIndex = new HashMap<>();
    private final Map<String, Integer> structIndex = new LinkedHashMap<>();
    private final Map<String, Integer> setIndex = new LinkedHashMap<>();
    private final Map<String, Set<String>> tagCache = new HashMap<>();
    private final Map<String, JsonObject> poolJson = new TreeMap<>();
    private final Map<String, Integer> biomeBit = new HashMap<>();

    private StructureCompiler(StructureData data) {
        this.data = data;
    }

    /**
     * @param biomeUniverse Biome der Dimension in der Reihenfolge der GPU-Biomsuche (Index = Bit in den Masken)
     * @param dimension 0 Overworld, 1 Nether, 2 End (nur Kennzeichnung fuer Kernel mit Dimensionswissen)
     */
    public static StructureTables compile(StructureData data, List<String> biomeUniverse, long seed, int minY, int height, int seaLevel, int dimension) {
        StructureCompiler c = new StructureCompiler(data);
        c.t.seed = seed;
        c.t.minY = minY;
        c.t.height = height;
        c.t.seaLevel = seaLevel;
        c.t.dimension = dimension;
        c.t.biomeUniverse = biomeUniverse;
        for (int i = 0; i < biomeUniverse.size(); i++) c.biomeBit.put(biomeUniverse.get(i), i);
        c.t.biomeWords = (biomeUniverse.size() + 31) / 32;
        long t0 = System.nanoTime();
        c.run();
        LOG.info("Strukturregistry kompiliert: {} Sets, {} Strukturen ({} nicht unterstuetzt: {}), {} Pools, {} Elemente, {} Vorlagen, {} Jigsaws, {} KiB Voxel ({} ms)",
                c.t.numSets(), c.t.numStructs(), c.t.unsupported.size(), c.t.unsupported.keySet(), c.t.numPools(), c.t.numElems(), c.t.numTemplates(),
                c.t.jigsaws.size() / StructureTables.JIG_W, c.t.voxels.size() * 2 / 1024, (System.nanoTime() - t0) / 1_000_000);
        return c.t;
    }

    /** Biome einer Dimension laut Tag (is_overworld / is_nether / is_end); Reihenfolge nach Id. Nur Testwerkzeug/Vorgabe, Produktion nimmt die Biomsuche. */
    public static List<String> biomesOfTag(StructureData data, String tag) {
        StructureCompiler c = new StructureCompiler(data);
        return new java.util.ArrayList<>(new TreeMap<>(c.resolveTag(tag, new HashSet<>()).stream().collect(java.util.stream.Collectors.toMap(x -> x, x -> x))).keySet());
    }

    // ---------------------------------------------------------------------------------------------
    private void run() {
        t.emptyString = t.intern("minecraft:empty");
        compilePools();
        compileStructures();
        compileSets();
        fixJigsawPools();
        // GPU-seitige Konstanten: Pool "empty" und die Zeichenkette fuer Feature-Jigsaws
        t.emptyPool = poolIndex.getOrDefault("minecraft:empty", -1);
        procs.finish();
    }

    private static String id(String s) {
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    // ------------------------------------------------------------------ Biome (Tags)
    private Set<String> resolveTag(String tag, Set<String> visiting) {
        Set<String> cached = tagCache.get(tag);
        if (cached != null) return cached;
        if (!visiting.add(tag)) return Set.of();
        Set<String> out = new HashSet<>();
        for (JsonElement e : data.jsonStack("tags/worldgen/biome", tag)) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("replace") && o.get("replace").getAsBoolean()) out.clear();
            for (JsonElement v : o.getAsJsonArray("values")) {
                String s = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (s.startsWith("#")) out.addAll(resolveTag(id(s.substring(1)), visiting));
                else out.add(id(s));
            }
        }
        visiting.remove(tag);
        tagCache.put(tag, out);
        return out;
    }

    private Set<String> biomes(JsonElement e) {
        Set<String> out = new HashSet<>();
        if (e == null) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.addAll(biomes(x));
        } else {
            String s = e.getAsString();
            if (s.startsWith("#")) out.addAll(resolveTag(id(s.substring(1)), new HashSet<>()));
            else out.add(id(s));
        }
        return out;
    }

    private int[] biomeMask(JsonElement e) {
        int[] m = new int[Math.max(1, t.biomeWords)];
        for (String b : biomes(e)) {
            Integer bit = biomeBit.get(b);
            if (bit != null) m[bit >> 5] |= 1 << (bit & 31);
        }
        return m;
    }

    // ------------------------------------------------------------------ Pools
    private void compilePools() {
        Map<String, JsonElement> all = data.json("worldgen/template_pool");
        for (var e : new TreeMap<>(all).entrySet()) poolJson.put(e.getKey(), e.getValue().getAsJsonObject());
        if (!poolJson.containsKey("minecraft:empty")) {
            JsonObject empty = new JsonObject();
            empty.addProperty("fallback", "minecraft:empty");
            empty.add("elements", new JsonArray());
            poolJson.put("minecraft:empty", empty);
        }
        int i = 0;
        for (String name : poolJson.keySet()) {
            poolIndex.put(name, i++);
            t.poolNames.add(name);
        }
        // Zeilen anlegen
        for (String name : poolJson.keySet()) {
            for (int k = 0; k < StructureTables.POOL_W; k++) t.pools.add(0);
        }
        for (String name : poolJson.keySet()) {
            JsonObject p = poolJson.get(name);
            int pi = poolIndex.get(name);
            int start = t.poolElems.size();
            int maxSize = 0;
            JsonArray els = p.getAsJsonArray("elements");
            for (JsonElement we : els) {
                JsonObject wo = we.getAsJsonObject();
                int weight = wo.get("weight").getAsInt();
                int el = compileElement(wo.getAsJsonObject("element"), name);
                for (int w = 0; w < weight; w++) t.poolElems.add(el);
                int kind = t.elems.getInt(el * StructureTables.ELEM_W + StructureTables.EL_KIND);
                if (kind != 0) maxSize = Math.max(maxSize, elementYSpan(el));
            }
            int b = pi * StructureTables.POOL_W;
            t.pools.set(b + StructureTables.PL_START, start);
            t.pools.set(b + StructureTables.PL_COUNT, t.poolElems.size() - start);
            String fb = p.has("fallback") ? id(p.get("fallback").getAsString()) : "minecraft:empty";
            t.pools.set(b + StructureTables.PL_FALLBACK, poolIndex.getOrDefault(fb, -1));
            t.pools.set(b + StructureTables.PL_MAXSIZE, maxSize);
            t.pools.set(b + StructureTables.PL_IS_EMPTYKEY, name.equals("minecraft:empty") ? 1 : 0);
            t.pools.set(b + StructureTables.PL_EXISTS, 1);
        }
    }

    /** Y-Spanne (Rotation NONE) eines Elements, wie StructureTemplatePool.getMaxSize */
    private int elementYSpan(int el) {
        int b = el * StructureTables.ELEM_W;
        int kind = t.elems.getInt(b + StructureTables.EL_KIND);
        if (kind == 1) {
            int tpl = t.elems.getInt(b + StructureTables.EL_TEMPLATE);
            return t.templates.getInt(tpl * StructureTables.TPL_W + StructureTables.TP_SY);
        }
        if (kind == 2) {
            int span = 0;
            int s = t.elems.getInt(b + StructureTables.EL_SUB_START), n = t.elems.getInt(b + StructureTables.EL_SUB_COUNT);
            for (int i = 0; i < n; i++) {
                int se = t.subElems.getInt(s + i);
                if (t.elems.getInt(se * StructureTables.ELEM_W + StructureTables.EL_KIND) != 0) span = Math.max(span, elementYSpan(se));
            }
            return span;
        }
        return 1;
    }

    private int compileElement(JsonObject o, String pool) {
        String key = o.toString();
        Integer have = elemIndex.get(key);
        if (have != null) return have;
        String type = id(o.get("element_type").getAsString());
        int[] row = new int[StructureTables.ELEM_W];
        row[StructureTables.EL_TEMPLATE] = -1;
        row[StructureTables.EL_PROCS] = -1;
        row[StructureTables.EL_FEATURE] = -1;
        String proj = o.has("projection") ? o.get("projection").getAsString() : "rigid";
        row[StructureTables.EL_PROJ] = proj.equals("terrain_matching") ? 0 : 1;
        List<Integer> subs = new ArrayList<>();
        switch (type) {
            case "minecraft:empty_pool_element" -> row[StructureTables.EL_KIND] = 0;
            case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                row[StructureTables.EL_KIND] = 1;
                row[StructureTables.EL_TEMPLATE] = baker.get(data, id(o.get("location").getAsString()));
                if (type.equals("minecraft:legacy_single_pool_element")) row[StructureTables.EL_FLAGS] |= 1;
                if (o.has("override_liquid_settings") && o.get("override_liquid_settings").getAsString().equals("ignore_waterlogging"))
                    row[StructureTables.EL_FLAGS] |= 2;
                row[StructureTables.EL_PROCS] = o.has("processors") ? processorList(o.get("processors")) : -1;
            }
            case "minecraft:list_pool_element" -> {
                row[StructureTables.EL_KIND] = 2;
                for (JsonElement se : o.getAsJsonArray("elements")) subs.add(compileElement(se.getAsJsonObject(), pool));
            }
            case "minecraft:feature_pool_element" -> {
                row[StructureTables.EL_KIND] = 3;
                row[StructureTables.EL_FEATURE] = t.intern(id(o.get("feature").getAsString()));
            }
            default -> {
                LOG.warn("Unbekannter Pool-Element-Typ {} in {}", type, pool);
                row[StructureTables.EL_KIND] = 0;
            }
        }
        // List-Elemente erben die Projektion auf die Unterelemente (ListPoolElement.setProjection)
        row[StructureTables.EL_SUB_START] = t.subElems.size();
        row[StructureTables.EL_SUB_COUNT] = subs.size();
        for (int s : subs) t.subElems.add(s);
        int idx = t.numElems();
        for (int w : row) t.elems.add(w);
        elemIndex.put(key, idx);
        return idx;
    }

    /** Prozessorlisten: Name oder Inline-Definition -&gt; Index (Prozessor-VM folgt, derzeit nur Kennzeichnung) */
    private int processorList(JsonElement e) {
        return procs.indexOf(e);
    }

    StructureData data() {
        return data;
    }

    // ------------------------------------------------------------------ Strukturen
    private void compileStructures() {
        Map<String, JsonElement> all = new TreeMap<>(data.json("worldgen/structure"));
        // Mitgelieferte Jigsaw-Ersatzdefinitionen prozeduraler Vanilla-Strukturen: pyridium:<ns>/<pfad> (siehe PackExporter, docs/STRUCTURES.md)
        Map<String, JsonElement> overrides = data.json("worldgen/structure_override");
        for (var e : all.entrySet()) {
            int[] row = new int[StructureTables.STRUCT_W];
            String name = e.getKey();
            try {
                compileStructure(name, overridden(name, e.getValue().getAsJsonObject(), overrides), row);
            } catch (RuntimeException ex) {
                t.unsupported.put(name, "Fehler: " + ex);
                row[StructureTables.ST_TYPE] = StructureTables.STT_UNSUPPORTED;
                row[StructureTables.ST_FLAGS] = 0;
                LOG.warn("Struktur {} nicht uebersetzbar: {}", name, ex.toString());
            }
            structIndex.put(name, t.numStructs());
            t.structNames.add(name);
            for (int w : row) t.structs.add(w);
        }
    }

    private int heightMap(String s) {
        return switch (s) {
            case "WORLD_SURFACE_WG" -> 1;
            case "OCEAN_FLOOR_WG" -> 2;
            case "WORLD_SURFACE" -> 3;
            case "OCEAN_FLOOR" -> 4;
            case "MOTION_BLOCKING" -> 5;
            case "MOTION_BLOCKING_NO_LEAVES" -> 6;
            default -> throw new IllegalArgumentException("Heightmap " + s);
        };
    }

    private static void anchor(JsonElement e, int[] row, int kindIdx, int valIdx) {
        JsonObject o = e.getAsJsonObject();
        if (o.has("absolute")) {
            row[kindIdx] = 0;
            row[valIdx] = o.get("absolute").getAsInt();
        } else if (o.has("above_bottom")) {
            row[kindIdx] = 1;
            row[valIdx] = o.get("above_bottom").getAsInt();
        } else if (o.has("below_top")) {
            row[kindIdx] = 2;
            row[valIdx] = o.get("below_top").getAsInt();
        } else {
            throw new IllegalArgumentException("VerticalAnchor " + o);
        }
    }

    static void heightProviderOf(JsonElement e, int[] row) {
        heightProvider(e, row);
    }

    private static void heightProvider(JsonElement e, int[] row) {
        JsonObject o = e.getAsJsonObject();
        if (!o.has("type")) {   // ConstantHeight (Anker direkt)
            row[StructureTables.ST_HP_KIND] = 0;
            anchor(o, row, StructureTables.ST_HP_MIN_KIND, StructureTables.ST_HP_MIN_VAL);
            return;
        }
        String type = id(o.get("type").getAsString());
        switch (type) {
            case "minecraft:constant" -> {
                row[StructureTables.ST_HP_KIND] = 0;
                anchor(o.get("value"), row, StructureTables.ST_HP_MIN_KIND, StructureTables.ST_HP_MIN_VAL);
            }
            case "minecraft:uniform" -> {
                row[StructureTables.ST_HP_KIND] = 1;
                anchor(o.get("min_inclusive"), row, StructureTables.ST_HP_MIN_KIND, StructureTables.ST_HP_MIN_VAL);
                anchor(o.get("max_inclusive"), row, StructureTables.ST_HP_MAX_KIND, StructureTables.ST_HP_MAX_VAL);
            }
            default -> throw new IllegalArgumentException("HeightProvider " + type);
        }
    }

    /** Strukturtyp-Register aus den Daten (data/&lt;ns&gt;/structure_type/*.json: {"type": ..., "generator": ...}), siehe docs/STRUCTURES.md */
    private Map<String, String> typeRegistry;

    /** Erzeugerart eines Strukturtyps: Eintrag im Register (Datapacks koennen eigene Typen auf einen vorhandenen Erzeuger abbilden), sonst der Typ selbst */
    private String generatorOf(String declared) {
        if (typeRegistry == null) {
            typeRegistry = new HashMap<>();
            try {
                for (var e : data.json("structure_type").values()) {
                    JsonObject r = e.getAsJsonObject();
                    typeRegistry.put(id(r.get("type").getAsString()), id(r.get("generator").getAsString()));
                }
            } catch (RuntimeException ex) {
                LOG.warn("structure_type-Register nicht lesbar: {}", ex.toString());
            }
        }
        String g = typeRegistry.get(declared);
        return g == null ? declared : g;
    }

    /**
     * Ersetzt die Definition einer prozeduralen Struktur (Typ ohne GPU-Erzeuger im Typ-Register) durch die mitgelieferte Jigsaw-Fassung;
     * Biome, Schritt, Gelaendeanpassung und Spawns bleiben die der Original-Struktur (Datapacks koennen sie weiter aendern).
     */
    private JsonObject overridden(String name, JsonObject o, Map<String, JsonElement> overrides) {
        String type = id(o.get("type").getAsString());
        if (type.equals("minecraft:jigsaw")) return o;
        JsonElement ov = overrides.get("pyridium:" + name.replace(':', '/'));
        if (ov == null || !generatorOf(type).equals(type) && !generatorOf(type).equals("minecraft:jigsaw")) return o;
        if (!ov.isJsonObject()) return o;
        JsonObject m = ov.getAsJsonObject().deepCopy();
        t.converted.add(name);
        for (String k : new String[]{"biomes", "step", "terrain_adaptation", "spawn_overrides"}) if (o.has(k)) m.add(k, o.get(k));
        return m;
    }

    private void compileStructure(String name, JsonObject o, int[] row) {
        String type = generatorOf(id(o.get("type").getAsString()));
        row[StructureTables.ST_BIOME_MASK] = t.biomeMasks.size();
        for (int w : biomeMask(o.get("biomes"))) t.biomeMasks.add(w);
        row[StructureTables.ST_STEP] = step(o.has("step") ? o.get("step").getAsString() : "surface_structures");
        String adapt = o.has("terrain_adaptation") ? o.get("terrain_adaptation").getAsString() : "none";
        row[StructureTables.ST_ADAPT] = switch (adapt) {
            case "bury" -> 1;
            case "beard_thin" -> 2;
            case "beard_box" -> 3;
            case "encapsulate" -> 4;
            default -> 0;
        };
        row[StructureTables.ST_START_JIGSAW] = -1;
        row[StructureTables.ST_FLAGS] = 0;
        switch (type) {
            case "minecraft:jigsaw" -> {
                row[StructureTables.ST_TYPE] = StructureTables.STT_JIGSAW;
                String startPool = id(o.get("start_pool").getAsString());
                Integer sp = poolIndex.get(startPool);
                if (sp == null) throw new IllegalArgumentException("start_pool " + startPool);
                row[StructureTables.ST_START_POOL] = sp;
                if (o.has("start_jigsaw_name")) row[StructureTables.ST_START_JIGSAW] = t.intern(id(o.get("start_jigsaw_name").getAsString()));
                row[StructureTables.ST_MAX_DEPTH] = o.get("size").getAsInt();
                heightProvider(o.get("start_height"), row);
                row[StructureTables.ST_EXPANSION] = o.get("use_expansion_hack").getAsBoolean() ? 1 : 0;
                row[StructureTables.ST_PROJECT] = o.has("project_start_to_heightmap") ? heightMap(o.get("project_start_to_heightmap").getAsString()) : 0;
                JsonElement md = o.get("max_distance_from_center");
                if (md.isJsonObject()) {
                    row[StructureTables.ST_MAXDIST_H] = md.getAsJsonObject().get("horizontal").getAsInt();
                    row[StructureTables.ST_MAXDIST_V] = md.getAsJsonObject().has("vertical") ? md.getAsJsonObject().get("vertical").getAsInt() : 384;
                } else {
                    row[StructureTables.ST_MAXDIST_H] = md.getAsInt();
                    row[StructureTables.ST_MAXDIST_V] = md.getAsInt();
                }
                if (o.has("dimension_padding")) {
                    JsonElement dp = o.get("dimension_padding");
                    if (dp.isJsonObject()) {
                        row[StructureTables.ST_PAD_BOTTOM] = dp.getAsJsonObject().has("bottom") ? dp.getAsJsonObject().get("bottom").getAsInt() : 0;
                        row[StructureTables.ST_PAD_TOP] = dp.getAsJsonObject().has("top") ? dp.getAsJsonObject().get("top").getAsInt() : 0;
                    } else {
                        row[StructureTables.ST_PAD_BOTTOM] = dp.getAsInt();
                        row[StructureTables.ST_PAD_TOP] = dp.getAsInt();
                    }
                }
                row[StructureTables.ST_LIQUID] = o.has("liquid_settings") && o.get("liquid_settings").getAsString().equals("ignore_waterlogging") ? 1 : 0;
                row[StructureTables.ST_ALIAS_START] = t.aliasData.size();
                if (o.has("pool_aliases")) {
                    JsonArray aliases = o.getAsJsonArray("pool_aliases");
                    row[StructureTables.ST_ALIAS_COUNT] = aliases.size();
                    for (JsonElement a : aliases) compileAlias(a.getAsJsonObject());
                }
                row[StructureTables.ST_FLAGS] = 1;
            }
            default -> {
                // Prozedurale Typen: Zuordnung hier, Erzeugung in wg_struct_proc.cuh (Status siehe docs/STRUCTURES.md)
                int stt = ProceduralTypes.typeOf(type);
                row[StructureTables.ST_TYPE] = stt;
                ProceduralTypes.params(t, baker, tplId -> baker.get(data, id(tplId)), type, o, row);
                boolean ok = ProceduralTypes.implemented(stt);
                row[StructureTables.ST_FLAGS] = ok ? 1 : 0;
                if (!ok) t.unsupported.put(name, "Typ " + type + " noch nicht auf der GPU");
            }
        }
    }

    private void compileAlias(JsonObject a) {
        int at = t.aliasData.size();
        t.aliasData.add(0);   // len
        String type = id(a.get("type").getAsString());
        switch (type) {
            case "minecraft:direct" -> {
                t.aliasData.add(0);
                t.aliasData.add(poolRef(id(a.get("alias").getAsString())));
                t.aliasData.add(poolRef(id(a.get("target").getAsString())));
            }
            case "minecraft:random" -> {
                t.aliasData.add(1);
                t.aliasData.add(poolRef(id(a.get("alias").getAsString())));
                JsonArray targets = a.getAsJsonArray("targets");
                t.aliasData.add(targets.size());
                for (JsonElement e : targets) {
                    JsonObject w = e.getAsJsonObject();
                    t.aliasData.add(poolRef(id(w.get("data").getAsString())));
                    t.aliasData.add(w.get("weight").getAsInt());
                }
            }
            case "minecraft:random_group" -> {
                t.aliasData.add(2);
                JsonArray groups = a.getAsJsonArray("groups");
                t.aliasData.add(groups.size());
                for (JsonElement g : groups) {
                    JsonObject go = g.getAsJsonObject();
                    t.aliasData.add(go.get("weight").getAsInt());
                    JsonArray data = go.getAsJsonArray("data");
                    t.aliasData.add(data.size());
                    for (JsonElement b : data) compileAlias(b.getAsJsonObject());
                }
            }
            default -> throw new IllegalArgumentException("Alias-Typ " + type);
        }
        t.aliasData.set(at, t.aliasData.size() - at);
    }

    private static int step(String s) {
        return switch (s) {
            case "raw_generation" -> 0;
            case "lakes" -> 1;
            case "local_modifications" -> 2;
            case "underground_structures" -> 3;
            case "surface_structures" -> 4;
            case "strongholds" -> 5;
            case "underground_ores" -> 6;
            case "underground_decoration" -> 7;
            case "fluid_springs" -> 8;
            case "vegetal_decoration" -> 9;
            case "top_layer_modification" -> 10;
            default -> 4;
        };
    }

    // ------------------------------------------------------------------ Sets
    private void compileSets() {
        Map<String, JsonElement> all = new TreeMap<>(data.json("worldgen/structure_set"));
        // Sets, die in dieser Dimension kein Biom haben, entfallen (ChunkGeneratorStructureState.hasBiomesForStructureSet)
        Map<String, JsonObject> kept = new LinkedHashMap<>();
        for (var e : all.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            boolean any = false;
            for (JsonElement se : o.getAsJsonArray("structures")) {
                String sn = id(se.getAsJsonObject().get("structure").getAsString());
                Integer si = structIndex.get(sn);
                if (si == null) continue;
                int mb = t.structs.getInt(si * StructureTables.STRUCT_W + StructureTables.ST_BIOME_MASK);
                for (int w = 0; w < Math.max(1, t.biomeWords); w++) if (t.biomeMasks.getInt(mb + w) != 0) any = true;
            }
            if (any) kept.put(e.getKey(), o);
        }
        int i = 0;
        for (String n : kept.keySet()) setIndex.put(n, i++);
        for (var e : kept.entrySet()) {
            JsonObject o = e.getValue();
            JsonObject p = o.getAsJsonObject("placement");
            int[] row = new int[StructureTables.SET_W];
            String type = id(p.get("type").getAsString());
            row[StructureTables.SET_TYPE] = type.equals("minecraft:random_spread") ? 0 : 1;
            row[StructureTables.SET_SALT] = p.has("salt") ? p.get("salt").getAsInt() : 0;
            String fm = p.has("frequency_reduction_method") ? p.get("frequency_reduction_method").getAsString() : "default";
            row[StructureTables.SET_FREQMETHOD] = switch (fm) {
                case "legacy_type_1" -> 1;
                case "legacy_type_2" -> 2;
                case "legacy_type_3" -> 3;
                default -> 0;
            };
            row[StructureTables.SET_FREQ] = Float.floatToRawIntBits(p.has("frequency") ? p.get("frequency").getAsFloat() : 1.0f);
            row[StructureTables.SET_EXCL_SET] = -1;
            if (p.has("exclusion_zone")) {
                JsonObject ez = p.getAsJsonObject("exclusion_zone");
                Integer other = setIndex.get(id(ez.get("other_set").getAsString()));
                row[StructureTables.SET_EXCL_SET] = other == null ? -1 : other;
                row[StructureTables.SET_EXCL_CHUNKS] = ez.get("chunk_count").getAsInt();
            }
            if (row[StructureTables.SET_TYPE] == 0) {
                row[StructureTables.SET_SPACING] = p.get("spacing").getAsInt();
                row[StructureTables.SET_SEPARATION] = p.get("separation").getAsInt();
                row[StructureTables.SET_SPREAD] = p.has("spread_type") && p.get("spread_type").getAsString().equals("triangular") ? 1 : 0;
            } else {
                row[StructureTables.SET_RING_DIST] = p.get("distance").getAsInt();
                row[StructureTables.SET_RING_SPREAD] = p.get("spread").getAsInt();
                row[StructureTables.SET_RING_COUNT] = p.get("count").getAsInt();
                // preferred_biomes als Maske (spaeter fuer die Ring-Suche); Position folgt von RingSolver
                row[StructureTables.SET_RING_POS] = t.ringPos.size() / 2;
                row[StructureTables.SET_RING_MASK] = t.biomeMasks.size();
                for (int w : biomeMask(p.get("preferred_biomes"))) t.biomeMasks.add(w);
                for (int k = 0; k < row[StructureTables.SET_RING_COUNT]; k++) { t.ringPos.add(Integer.MIN_VALUE); t.ringPos.add(Integer.MIN_VALUE); }
            }
            row[StructureTables.SET_ENTRY_START] = t.entries.size() / StructureTables.ENTRY_W;
            int total = 0, count = 0;
            for (JsonElement se : o.getAsJsonArray("structures")) {
                JsonObject so = se.getAsJsonObject();
                Integer si = structIndex.get(id(so.get("structure").getAsString()));
                if (si == null) continue;
                int w = so.get("weight").getAsInt();
                t.entries.add(si);
                t.entries.add(w);
                total += w;
                count++;
            }
            row[StructureTables.SET_ENTRY_COUNT] = count;
            row[StructureTables.SET_TOTAL_WEIGHT] = total;
            envSize(row);
            for (int w : row) t.sets.add(w);
            t.setNames.add(e.getKey());
        }
    }

    /** Jigsaw-Pool-Verweise (Zeichenketten-Ids) auf Pool-Indizes umschreiben */
    private void fixJigsawPools() {
        int n = t.jigsaws.size() / StructureTables.JIG_W;
        for (int j = 0; j < n; j++) {
            int b = j * StructureTables.JIG_W + StructureTables.JG_POOL;
            String name = t.stringList.get(t.jigsaws.getInt(b));
            t.jigsaws.set(b, poolRef(name));
        }
    }

    /** Umgebungs-Kachel je Set: Radius, Flags, Groesse (siehe wg_struct_env_tile.cuh) */
    private void envSize(int[] row) {
        int r = 0, flags = 0;
        boolean any = false;
        int es = row[StructureTables.SET_ENTRY_START], ec = row[StructureTables.SET_ENTRY_COUNT];
        for (int e = 0; e < ec; e++) {
            int si = t.entries.getInt((es + e) * StructureTables.ENTRY_W);
            int b = si * StructureTables.STRUCT_W;
            if ((t.structs.getInt(b + StructureTables.ST_FLAGS) & 1) == 0) continue;   // nicht unterstuetzte Strukturen brauchen keine Umgebung
            any = true;
            int type = t.structs.getInt(b + StructureTables.ST_TYPE);
            if (type == StructureTables.STT_JIGSAW) {
                r = Math.max(r, t.structs.getInt(b + StructureTables.ST_MAXDIST_H) + 24 + (t.structs.getInt(b + StructureTables.ST_ADAPT) != 0 ? 12 : 0));
                int pj = t.structs.getInt(b + StructureTables.ST_PROJECT);
                if (pj == 2 || pj == 4) flags |= 1;
            } else {
                r = Math.max(r, 48);
                if (type == StructureTables.STT_SHIPWRECK || type == StructureTables.STT_OCEAN_RUIN || type == StructureTables.STT_BURIED_TREASURE
                        || type == StructureTables.STT_RUINED_PORTAL) flags |= 1;
            }
        }
        if (!any) {
            row[StructureTables.SET_ENV_INTS] = 0;
            return;
        }
        int hw = 2 * r + 16;
        int surf = (hw * hw + 1) / 2;
        int biome = 12 * 12 * Math.max(1, t.height >> 2) / 4;
        row[StructureTables.SET_ENV_R] = r;
        row[StructureTables.SET_ENV_FLAGS] = flags;
        row[StructureTables.SET_ENV_INTS] = 16 + surf + ((flags & 1) != 0 ? surf : 0) + biome;
    }

    /** Pool-Index zum Namen; nicht in der Registry vorhandene Pools (z. B. Alias-Schluessel) bekommen eine Zeile mit PL_EXISTS = 0 */
    private int poolRef(String name) {
        Integer i = poolIndex.get(name);
        if (i != null) return i;
        int idx = t.numPools();
        poolIndex.put(name, idx);
        t.poolNames.add(name);
        for (int k = 0; k < StructureTables.POOL_W; k++) t.pools.add(0);
        t.pools.set(idx * StructureTables.POOL_W + StructureTables.PL_FALLBACK, -1);
        return idx;
    }

    /** Pool-Index zu Namen (Test/Diagnose) */
    public static Map<String, Integer> structureIndex(StructureTables t) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < t.structNames.size(); i++) m.put(t.structNames.get(i), i);
        return m;
    }
}
