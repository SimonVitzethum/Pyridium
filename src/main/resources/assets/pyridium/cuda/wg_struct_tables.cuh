// wg_struct_tables.cuh - Strukturgenerierung auf der GPU: Tabellen-Layout, Konstanten, Zufall.
//
// Alles hier ist zustandslos: eine Struktur ergibt sich nur aus (Seed, Chunk, Registry-Tabellen,
// Umgebung). Die Registry (structure_set / structure / template_pool / processor_list / NBT-Vorlagen)
// wird beim Laden EINMAL von Java zu den flachen Tabellen in StructTables kompiliert
// (simon.pyridium.worldgen.structures.StructureCompiler); zur Laufzeit rechnet nur dieser Code.
//
// Zufall: LegacyRandomSource/WorldgenRandom (java.util.Random-LCG) wie Vanilla, damit Platzierung und
// Jigsaw-Aufbau gegen Vanilla pruefbar sind (Testwerkzeug StructureHarness). Bitgleichheit ist kein
// Laufzeitziel, sie ist die Pruefmethode.
#pragma once
// Eigenstaendig (ohne wg_prelude.cuh): kann in beliebige Kernel-Dateien (z. B. LOD-Voxel-Kernel) eingebunden werden.
typedef unsigned int uint;
typedef long long jlong;
typedef unsigned long long julong;
// Java-Ganzzahlarithmetik mit Ueberlauf (ueber unsigned)
__device__ __forceinline__ int sj_mul32(int a, int b) { return (int)((uint)a * (uint)b); }
__device__ __forceinline__ jlong sj_mul64(jlong a, jlong b) { return (jlong)((julong)a * (julong)b); }
__device__ __forceinline__ jlong sj_add64(jlong a, jlong b) { return (jlong)((julong)a + (julong)b); }

#define SMAXI 2147483647
#define SMINI (-2147483647 - 1)

// ---------------------------------------------------------------------------------------------
// Tabellen (Reihenfolge und Wortbreiten spiegeln StructureTables.java)
// ---------------------------------------------------------------------------------------------
// structure_set
#define SET_W 24
#define SET_TYPE 0        // 0 random_spread, 1 concentric_rings
#define SET_SPACING 1
#define SET_SEPARATION 2
#define SET_SPREAD 3      // 0 linear, 1 triangular
#define SET_SALT 4
#define SET_FREQMETHOD 5  // 0 default, 1 legacy_type_1, 2 legacy_type_2, 3 legacy_type_3
#define SET_FREQ 6        // float-Bits
#define SET_EXCL_SET 7    // -1 keine
#define SET_EXCL_CHUNKS 8
#define SET_RING_DIST 9
#define SET_RING_SPREAD 10
#define SET_RING_COUNT 11
#define SET_ENTRY_START 12
#define SET_ENTRY_COUNT 13
#define SET_TOTAL_WEIGHT 14
#define SET_RING_POS 15   // Index in ringPos (int2-Paare)
#define SET_RING_MASK 16  // Wortindex der preferred_biomes-Maske in biomeMasks
#define SET_ENV_R 17      // Kachel-Radius der Umgebung in Bloecken (Kachel deckt [cx*16 - R, cx*16 + 16 + R))
#define SET_ENV_FLAGS 18  // Bit0: Meeresboden-Hoehen (OCEAN_FLOOR_WG) werden gebraucht
#define SET_ENV_INTS 19   // Groesse einer Kachel in ints (Kopf + Hoehen + Boden + Biome)

#define ENTRY_W 2         // structure, weight

// structure
#define STRUCT_W 32
#define ST_TYPE 0         // StructType
#define ST_STEP 1
#define ST_ADAPT 2        // 0 none, 1 bury, 2 beard_thin, 3 beard_box, 4 encapsulate
#define ST_BIOME_MASK 3   // Wortindex in biomeMasks
#define ST_FLAGS 4        // Bit0: unterstuetzt
#define ST_START_POOL 5
#define ST_START_JIGSAW 6 // String-Id oder -1
#define ST_MAX_DEPTH 7
#define ST_HP_KIND 8      // 0 konstant, 1 uniform, 2 biased_to_bottom, 3 very_biased_to_bottom, 4 trapezoid
#define ST_HP_MIN_KIND 9  // 0 absolute, 1 above_bottom, 2 below_top
#define ST_HP_MIN_VAL 10
#define ST_HP_MAX_KIND 11
#define ST_HP_MAX_VAL 12
#define ST_HP_EXTRA 13    // inner (biased) / plateau (trapezoid)
#define ST_EXPANSION 14
#define ST_PROJECT 15     // 0 keine, sonst Heightmap-Typ (HM_*)
#define ST_MAXDIST_H 16
#define ST_MAXDIST_V 17
#define ST_ALIAS_START 18 // Wortindex in aliasData
#define ST_ALIAS_COUNT 19
#define ST_PAD_TOP 20
#define ST_PAD_BOTTOM 21
#define ST_LIQUID 22      // 0 apply_waterlogging, 1 ignore_waterlogging
#define ST_P0 24          // typabhaengig
#define ST_P1 25
#define ST_P2 26
#define ST_P3 27
#define ST_P4 28
#define ST_P5 29

// Struktur-Typen (StructType.java)
#define STT_JIGSAW 0
#define STT_SHIPWRECK 1
#define STT_RUINED_PORTAL 2
#define STT_DESERT_PYRAMID 3
#define STT_JUNGLE_TEMPLE 4
#define STT_SWAMP_HUT 5
#define STT_IGLOO 6
#define STT_OCEAN_RUIN 7
#define STT_BURIED_TREASURE 8
#define STT_MINESHAFT 9
#define STT_STRONGHOLD 10
#define STT_FORTRESS 11
#define STT_END_CITY 12
#define STT_MONUMENT 13
#define STT_MANSION 14
#define STT_NETHER_FOSSIL 15
#define STT_UNSUPPORTED 255

// Heightmap-Typen (wie Heightmap.Types, nur die Worldgen-relevanten)
#define HM_NONE 0
#define HM_WORLD_SURFACE_WG 1
#define HM_OCEAN_FLOOR_WG 2
#define HM_WORLD_SURFACE 3
#define HM_OCEAN_FLOOR 4
#define HM_MOTION_BLOCKING 5
#define HM_MOTION_BLOCKING_NO_LEAVES 6

// template_pool
#define POOL_W 6
#define PL_START 0        // Start in poolElems (aufgeweitet: Gewicht = Anzahl Kopien)
#define PL_COUNT 1
#define PL_FALLBACK 2     // Pool-Index (immer gueltig, "empty" zeigt auf sich)
#define PL_MAXSIZE 3      // getMaxSize (Y-Spanne), vorberechnet
#define PL_IS_EMPTYKEY 4  // ist der Pool "minecraft:empty"
#define PL_EXISTS 5       // 0: unbekannter Pool (Vanilla: "Empty or non-existent")

// StructurePoolElement
#define ELEM_W 8
#define EL_KIND 0         // 0 leer, 1 einzeln, 2 Liste, 3 Feature
#define EL_PROJ 1         // 0 terrain_matching, 1 rigid
#define EL_TEMPLATE 2
#define EL_PROCS 3        // processor_list-Index oder -1
#define EL_SUB_START 4
#define EL_SUB_COUNT 5
#define EL_FEATURE 6
#define EL_FLAGS 7        // Bit0 legacy (Luft der Vorlage wird nicht gesetzt), Bit1 ignoreWaterlogging

// Vorlagen
#define TPL_W 10
#define TP_SX 0
#define TP_SY 1
#define TP_SZ 2
#define TP_VOX 3          // Start in voxels (ushort-Einheiten), x + sx * (z + sz * y)
#define TP_PAL 4          // Start in palette (Blockzustands-Ids)
#define TP_PALCOUNT 5
#define TP_JIG 6
#define TP_JIGCOUNT 7
#define TP_MIP 8          // Start der Mip-Kette in mips (-1 keine)
#define TP_FLAGS 9

#define JIG_W 12
#define JG_X 0
#define JG_Y 1
#define JG_Z 2
#define JG_FRONT 3        // Richtung 0..5 (Vanilla-Reihenfolge: DOWN, UP, NORTH, SOUTH, WEST, EAST)
#define JG_TOP 4
#define JG_JOINT 5        // 0 rollable, 1 aligned
#define JG_NAME 6         // String-Id, -1 = null (passt auf alles)
#define JG_POOL 7         // Pool-Index oder -1
#define JG_TARGET 8
#define JG_PPRIO 9
#define JG_SPRIO 10
#define JG_FINAL 11       // Blockzustands-Id des final_state (0 = Luft; -1 = structure_void/ignorieren)

// Alias-Bindungen (aliasData): Befehlsstrom, siehe StructureCompiler.compileAliases
// Stueck-Typen
#define PK_POOL 0         // Jigsaw-Teil (Pool-Element)
#define PK_PROC 1         // prozedurales Teil (je Strukturtyp, ST_*)

// Teil-Record (Ausgabe)
#define PIECE_W 24
#define PC_START 0
#define PC_KIND 1
#define PC_ELEM 2
#define PC_TPL 3
#define PC_X 4
#define PC_Y 5
#define PC_Z 6
#define PC_ROT 7
#define PC_MIRROR 8
#define PC_PROJ 9
#define PC_GROUND 10
#define PC_MINX 11
#define PC_MINY 12
#define PC_MINZ 13
#define PC_MAXX 14
#define PC_MAXY 15
#define PC_MAXZ 16
#define PC_DEPTH 17
#define PC_FLAGS 18       // Bit0 legacy-Luft-ignorieren, Bit1 Terrain-matching
#define PC_STRUCT 19
#define PC_PROCS 20
#define PC_P0 21          // typabhaengig
#define PC_P1 22
#define PC_P2 23

// Start-Record (Ausgabe)
#define START_W 16
#define SR_SET 0
#define SR_STRUCT 1
#define SR_CX 2
#define SR_CZ 3
#define SR_PIECE0 4
#define SR_PIECES 5
#define SR_MINX 6
#define SR_MINY 7
#define SR_MINZ 8
#define SR_MAXX 9
#define SR_MAXY 10
#define SR_MAXZ 11
#define SR_STATE 12       // 1 gueltig
#define SR_POSX 13        // GenerationStub.position
#define SR_POSY 14
#define SR_POSZ 15

// Tabellen-Kopf im Geraetespeicher (alles 8-Byte-Felder; Java: StructureTables.writeView)
struct StructTables {
    const int* sets;          jlong numSets;
    const int* entries;       jlong numEntries;
    const int* structs;       jlong numStructs;
    const int* biomeMasks;    jlong biomeWords;
    const int* pools;         jlong numPools;
    const int* poolElems;
    const int* elems;
    const int* subElems;
    const int* templates;     jlong numTemplates;
    const int* jigsaws;
    const unsigned short* voxels;
    const int* palette;
    const int* ringPos;       jlong numRings;
    const int* aliasData;
    const int* mips;
    const int* attr;          jlong numAttr;     // Blockzustands-Id -> Pyrit-Attribut (optional)
    jlong seed;
    jlong minY;
    jlong height;
    jlong seaLevel;
    jlong dimension;          // 0 Overworld, 1 Nether, 2 End
    jlong resv[6];            // resv[0] Pool "empty", resv[1] String-Id "minecraft:empty", resv[2] Zahl der Biome, resv[3] procData
    // erzeugte Strukturen (wachsender Arena) und ihr Raumindex
    const int* starts;        // Kopf OUT_HDR ints, danach START_W je Start
    const int* pieces;        // PIECE_W je Teil
    const unsigned long long* cellKeys;   // Hash-Zellen (64 x 64 Bloecke): Schluessel, 0 = leer
    const int* cellHeads;     // je Zelle: Knotenindex (0 = leer)
    const int* nodes;         // 2 ints je Knoten: Teil, naechster Knoten; Knoten 0 ungenutzt
    jlong hashMask;
    const float* biomeTemp;   // Basistemperatur je Biom (optional)
    // Prozessorlisten (siehe ProcessorCompiler.java)
    const int* procCode;      // je Liste: [nOps, op...]
    const int* procOff;       // Liste -> Offset in procCode
    const int* stateBlock;    // Blockzustands-Id -> Block-Id
    const int* tagBits;       // Tag-Index * tagWords: Bitmenge ueber Block-Ids
    jlong tagWords;
};

// ---------------------------------------------------------------------------------------------
// Zufall: LegacyRandomSource wie Vanilla (java.util.Random-LCG; WorldgenRandom.next delegiert)
// ---------------------------------------------------------------------------------------------
struct LRand { julong s; };

__device__ __forceinline__ void lr_setSeed(LRand& r, jlong seed) {
    r.s = ((julong)seed ^ 0x5DEECE66DULL) & 0xFFFFFFFFFFFFULL;
}
__device__ __forceinline__ int lr_next(LRand& r, int bits) {
    r.s = (r.s * 0x5DEECE66DULL + 0xBULL) & 0xFFFFFFFFFFFFULL;
    return (int)((jlong)r.s >> (48 - bits));
}
__device__ __forceinline__ int lr_nextInt(LRand& r, int bound) {
    if ((bound & (bound - 1)) == 0) return (int)(((jlong)bound * (jlong)lr_next(r, 31)) >> 31);
    int sample, modulo;
    do {
        sample = lr_next(r, 31);
        modulo = sample % bound;
    } while ((int)((uint)sample - (uint)modulo + (uint)(bound - 1)) < 0);
    return modulo;
}
__device__ __forceinline__ jlong lr_nextLong(LRand& r) {
    int upper = lr_next(r, 32);
    int lower = lr_next(r, 32);
    return sj_add64((jlong)upper << 32, (jlong)lower);
}
__device__ __forceinline__ bool lr_nextBoolean(LRand& r) { return lr_next(r, 1) != 0; }
__device__ __forceinline__ float lr_nextFloat(LRand& r) { return (float)lr_next(r, 24) * 5.9604645E-8f; }
__device__ __forceinline__ double lr_nextDouble(LRand& r) {
    int upper = lr_next(r, 26);
    int lower = lr_next(r, 27);
    jlong combined = ((jlong)upper << 27) + (jlong)lower;
    return (double)combined * (double)1.110223E-16f;
}
// nextIntBetweenInclusive
__device__ __forceinline__ int lr_between(LRand& r, int lo, int hi) { return lr_nextInt(r, hi - lo + 1) + lo; }

// WorldgenRandom.setLargeFeatureSeed / setLargeFeatureWithSalt
__device__ __forceinline__ void lr_largeFeatureSeed(LRand& r, jlong seed, int cx, int cz) {
    lr_setSeed(r, seed);
    jlong xs = lr_nextLong(r);
    jlong zs = lr_nextLong(r);
    jlong result = sj_mul64((jlong)cx, xs) ^ sj_mul64((jlong)cz, zs) ^ seed;
    lr_setSeed(r, result);
}
__device__ __forceinline__ void lr_largeFeatureSalt(LRand& r, jlong seed, int x, int z, int salt) {
    jlong result = sj_add64(sj_add64(sj_mul64((jlong)x, 341873128712LL), sj_mul64((jlong)z, 132897987541LL)), sj_add64(seed, (jlong)salt));
    lr_setSeed(r, result);
}

// Mth.getSeed(x, y, z)
__device__ __forceinline__ jlong st_mthSeed(int x, int y, int z) {
    jlong seed = (jlong)sj_mul32(x, 3129871) ^ sj_mul64((jlong)z, 116129781LL) ^ (jlong)y;
    seed = sj_add64(sj_mul64(sj_mul64(seed, seed), 42317861LL), sj_mul64(seed, 11LL));
    return seed >> 16;
}

// java Math.floorDiv
__device__ __forceinline__ int floorDivI(int a, int b) {
    int q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) q--;
    return q;
}

// StructureTemplate.transform (Pivot 0, kein Spiegeln)
__device__ __forceinline__ void rotXZ(int x, int z, int rot, int& ox, int& oz) {
    switch (rot) {
        case 1: ox = -z; oz = x; break;       // CLOCKWISE_90
        case 2: ox = -x; oz = -z; break;      // CLOCKWISE_180
        case 3: ox = z; oz = -x; break;       // COUNTERCLOCKWISE_90
        default: ox = x; oz = z; break;
    }
}
// Umkehrung: Welt-Offset (relativ zum Teil-Ursprung) -> Vorlagen-lokal
__device__ __forceinline__ void unrotXZ(int x, int z, int rot, int& ox, int& oz) {
    switch (rot) {
        case 1: ox = z; oz = -x; break;
        case 2: ox = -x; oz = -z; break;
        case 3: ox = -z; oz = x; break;
        default: ox = x; oz = z; break;
    }
}

