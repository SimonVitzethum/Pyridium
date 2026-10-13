package simon.pyridium.worldgen.structures;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die kompilierte Strukturregistry als flache Tabellen (Ausgabe von {@link StructureCompiler}, Eingabe
 * der GPU). Wortbreiten und Feldindizes spiegeln wg_struct_common.cuh.
 */
public final class StructureTables {
    // --- Wortbreiten (wg_struct_common.cuh) ---
    public static final int SET_W = 24, ENTRY_W = 2, STRUCT_W = 32, POOL_W = 6, ELEM_W = 8, TPL_W = 10, JIG_W = 12;
    public static final int PIECE_W = 24, START_W = 16;
    // set
    public static final int SET_TYPE = 0, SET_SPACING = 1, SET_SEPARATION = 2, SET_SPREAD = 3, SET_SALT = 4, SET_FREQMETHOD = 5, SET_FREQ = 6,
            SET_EXCL_SET = 7, SET_EXCL_CHUNKS = 8, SET_RING_DIST = 9, SET_RING_SPREAD = 10, SET_RING_COUNT = 11, SET_ENTRY_START = 12,
            SET_ENTRY_COUNT = 13, SET_TOTAL_WEIGHT = 14, SET_RING_POS = 15, SET_RING_MASK = 16, SET_ENV_R = 17, SET_ENV_FLAGS = 18, SET_ENV_INTS = 19;
    // structure
    public static final int ST_TYPE = 0, ST_STEP = 1, ST_ADAPT = 2, ST_BIOME_MASK = 3, ST_FLAGS = 4, ST_START_POOL = 5, ST_START_JIGSAW = 6,
            ST_MAX_DEPTH = 7, ST_HP_KIND = 8, ST_HP_MIN_KIND = 9, ST_HP_MIN_VAL = 10, ST_HP_MAX_KIND = 11, ST_HP_MAX_VAL = 12, ST_HP_EXTRA = 13,
            ST_EXPANSION = 14, ST_PROJECT = 15, ST_MAXDIST_H = 16, ST_MAXDIST_V = 17, ST_ALIAS_START = 18, ST_ALIAS_COUNT = 19, ST_PAD_TOP = 20,
            ST_PAD_BOTTOM = 21, ST_LIQUID = 22, ST_P0 = 24, ST_P1 = 25, ST_P2 = 26, ST_P3 = 27;
    // struct types
    public static final int STT_JIGSAW = 0, STT_SHIPWRECK = 1, STT_RUINED_PORTAL = 2, STT_DESERT_PYRAMID = 3, STT_JUNGLE_TEMPLE = 4, STT_SWAMP_HUT = 5,
            STT_IGLOO = 6, STT_OCEAN_RUIN = 7, STT_BURIED_TREASURE = 8, STT_MINESHAFT = 9, STT_STRONGHOLD = 10, STT_FORTRESS = 11, STT_END_CITY = 12,
            STT_MONUMENT = 13, STT_MANSION = 14, STT_NETHER_FOSSIL = 15, STT_UNSUPPORTED = 255;
    // pool
    public static final int PL_START = 0, PL_COUNT = 1, PL_FALLBACK = 2, PL_MAXSIZE = 3, PL_IS_EMPTYKEY = 4, PL_EXISTS = 5;
    // element
    public static final int EL_KIND = 0, EL_PROJ = 1, EL_TEMPLATE = 2, EL_PROCS = 3, EL_SUB_START = 4, EL_SUB_COUNT = 5, EL_FEATURE = 6, EL_FLAGS = 7;
    // template
    public static final int TP_SX = 0, TP_SY = 1, TP_SZ = 2, TP_VOX = 3, TP_PAL = 4, TP_PALCOUNT = 5, TP_JIG = 6, TP_JIGCOUNT = 7, TP_MIP = 8, TP_FLAGS = 9;
    // jigsaw
    public static final int JG_X = 0, JG_Y = 1, JG_Z = 2, JG_FRONT = 3, JG_TOP = 4, JG_JOINT = 5, JG_NAME = 6, JG_POOL = 7, JG_TARGET = 8,
            JG_PPRIO = 9, JG_SPRIO = 10, JG_FINAL = 11;
    // piece / start records
    public static final int PC_START = 0, PC_KIND = 1, PC_ELEM = 2, PC_TPL = 3, PC_X = 4, PC_Y = 5, PC_Z = 6, PC_ROT = 7, PC_MIRROR = 8, PC_PROJ = 9,
            PC_GROUND = 10, PC_MINX = 11, PC_MINY = 12, PC_MINZ = 13, PC_MAXX = 14, PC_MAXY = 15, PC_MAXZ = 16, PC_DEPTH = 17, PC_FLAGS = 18,
            PC_STRUCT = 19, PC_PROCS = 20, PC_P0 = 21;
    public static final int SR_SET = 0, SR_STRUCT = 1, SR_CX = 2, SR_CZ = 3, SR_PIECE0 = 4, SR_PIECES = 5, SR_MINX = 6, SR_MINY = 7, SR_MINZ = 8,
            SR_MAXX = 9, SR_MAXY = 10, SR_MAXZ = 11, SR_STATE = 12, SR_POSX = 13, SR_POSY = 14, SR_POSZ = 15;

    // --- Tabellen ---
    public final IntArrayList sets = new IntArrayList();
    public final IntArrayList entries = new IntArrayList();
    public final IntArrayList structs = new IntArrayList();
    public final IntArrayList biomeMasks = new IntArrayList();
    public final IntArrayList pools = new IntArrayList();
    public final IntArrayList poolElems = new IntArrayList();
    public final IntArrayList elems = new IntArrayList();
    public final IntArrayList subElems = new IntArrayList();
    public final IntArrayList templates = new IntArrayList();
    public final IntArrayList jigsaws = new IntArrayList();
    public final ShortArrayList voxels = new ShortArrayList();
    public final IntArrayList palette = new IntArrayList();
    public final IntArrayList ringPos = new IntArrayList();
    public final IntArrayList aliasData = new IntArrayList();
    public final IntArrayList mips = new IntArrayList();
    /** Vorlagenlisten und Parameter der nicht-Jigsaw-Strukturtypen (ProceduralTypes) */
    public final IntArrayList procData = new IntArrayList();
    /** Prozessorlisten (ProcessorCompiler): Code, Offset je Liste, Tag-Bitmengen (Block-Ids), Zustand -> Block */
    public final IntArrayList procCode = new IntArrayList();
    public final IntArrayList procOff = new IntArrayList();
    public final IntArrayList tagBits = new IntArrayList();
    public int[] stateBlock = new int[0];
    public long tagWords;

    // --- Namen (nur Diagnose/Ausgabe) ---
    public final List<String> setNames = new ArrayList<>();
    public final List<String> structNames = new ArrayList<>();
    public final List<String> poolNames = new ArrayList<>();
    public final List<String> templateNames = new ArrayList<>();
    public final Map<String, Integer> strings = new HashMap<>();
    public final List<String> stringList = new ArrayList<>();
    /** Strukturen, die der Compiler nicht (vollstaendig) uebersetzen konnte: Name -&gt; Grund */
    public final Map<String, String> unsupported = new HashMap<>();
    /** Strukturen, deren Definition durch die mitgelieferte Jigsaw-Fassung ersetzt wurde (nicht mehr 1:1 mit Vanilla vergleichbar) */
    public final java.util.Set<String> converted = new java.util.HashSet<>();
    /** Biom-Universum: Index = Bit in den Masken (= Biom-Index der GPU-Biomsuche) */
    public List<String> biomeUniverse = List.of();
    /** preferred_biomes der concentric_rings-Sets (Maske je Set-Name), fuer RingSolver */
    public final Map<String, int[]> ringBiomes = new HashMap<>();

    public long seed;
    public int minY = -64, height = 384, seaLevel = 63, dimension;
    public int emptyPool = -1, emptyString = -1;
    public int biomeWords;

    public int intern(String s) {
        Integer i = strings.get(s);
        if (i != null) return i;
        int id = stringList.size();
        strings.put(s, id);
        stringList.add(s);
        return id;
    }

    public int numSets() {
        return sets.size() / SET_W;
    }

    public int numStructs() {
        return structs.size() / STRUCT_W;
    }

    public int numTemplates() {
        return templates.size() / TPL_W;
    }

    public int numPools() {
        return pools.size() / POOL_W;
    }

    public int numElems() {
        return elems.size() / ELEM_W;
    }
}
