// wg_struct_place.cuh - StructurePlacement auf der GPU (random_spread, concentric_rings,
// frequency_reduction_method, exclusion_zone). Reine Funktion von (Seed, Tabellen, Chunk).
#pragma once
#include "wg_struct_common.cuh"

__device__ __forceinline__ const int* SETP(const StructTables& T, int set) { return T.sets + set * SET_W; }


// RandomSpreadStructurePlacement.getPotentialStructureChunk
__device__ __forceinline__ void place_potentialChunk(const StructTables& T, const int* S, int sx, int sz, int& px, int& pz) {
    int spacing = S[SET_SPACING], separation = S[SET_SEPARATION];
    int gx = floorDivI(sx, spacing), gz = floorDivI(sz, spacing);
    LRand r;
    lr_largeFeatureSalt(r, T.seed, gx, gz, S[SET_SALT]);
    int limit = spacing - separation;
    int spreadX, spreadZ;
    if (S[SET_SPREAD] == 0) {
        spreadX = lr_nextInt(r, limit);
        spreadZ = lr_nextInt(r, limit);
    } else {
        spreadX = (lr_nextInt(r, limit) + lr_nextInt(r, limit)) / 2;
        spreadZ = (lr_nextInt(r, limit) + lr_nextInt(r, limit)) / 2;
    }
    px = gx * spacing + spreadX;
    pz = gz * spacing + spreadZ;
}

// AbstractSpreadingStructurePlacement.FrequencyReductionMethod
__device__ bool place_frequencyOk(const StructTables& T, const int* S, int sx, int sz) {
    float freq = __int_as_float(S[SET_FREQ]);
    if (!(freq < 1.0f)) return true;
    int salt = S[SET_SALT];
    LRand r;
    switch (S[SET_FREQMETHOD]) {
        case 0:   // probabilityReducer: setLargeFeatureWithSalt(seed, salt, sourceX, sourceZ) (Vanilla-Argumentreihenfolge)
            lr_largeFeatureSalt(r, T.seed, salt, sx, sz);
            return lr_nextFloat(r) < freq;
        case 2:  // legacyArbitrarySaltProbabilityReducer
            lr_largeFeatureSalt(r, T.seed, sx, sz, 10387320);
            return lr_nextFloat(r) < freq;
        default:  // legacyProbabilityReducerWithDouble
            lr_largeFeatureSeed(r, T.seed, sx, sz);
            return lr_nextDouble(r) < (double)freq;
    }
}

// Pillager-Aussenposten-Reduzierer exakt: random.setSeed(cx ^ cz << 4 ^ seed); random.nextInt(); random.nextInt((int)(1.0F / p)) == 0
__device__ bool place_legacy1(const StructTables& T, const int* S, int sx, int sz) {
    float freq = __int_as_float(S[SET_FREQ]);
    int cx = sx >> 4, cz = sz >> 4;
    LRand r;
    lr_setSeed(r, (jlong)(cx ^ (cz << 4)) ^ T.seed);
    lr_next(r, 32);   // nextInt()
    int bound = (int)(1.0f / freq);
    return lr_nextInt(r, bound) == 0;
}

__device__ bool place_frequency(const StructTables& T, const int* S, int sx, int sz) {
    float freq = __int_as_float(S[SET_FREQ]);
    if (!(freq < 1.0f)) return true;
    if (S[SET_FREQMETHOD] == 1) return place_legacy1(T, S, sx, sz);
    return place_frequencyOk(T, S, sx, sz);
}

__device__ bool place_isPlacementChunk(const StructTables& T, int set, int sx, int sz) {
    const int* S = SETP(T, set);
    if (S[SET_TYPE] == 0) {
        int px, pz;
        place_potentialChunk(T, S, sx, sz, px, pz);
        return px == sx && pz == sz;
    }
    int n = S[SET_RING_COUNT], base = S[SET_RING_POS];
    for (int i = 0; i < n; i++) {
        if (T.ringPos[(base + i) * 2] == sx && T.ringPos[(base + i) * 2 + 1] == sz) return true;
    }
    return false;
}

// Platzierung + Frequenz ohne Exclusion Zone
__device__ bool place_baseChunk(const StructTables& T, int set, int sx, int sz) {
    const int* S = SETP(T, set);
    return place_isPlacementChunk(T, set, sx, sz) && place_frequency(T, S, sx, sz);
}

// AbstractSpreadingStructurePlacement.isStructureChunk (Exclusion Zones bis Tiefe 2, ohne Rekursion: kleiner GPU-Stack)
__device__ bool place_isStructureChunk(const StructTables& T, int set, int sx, int sz, int depth) {
    if (!place_baseChunk(T, set, sx, sz)) return false;
    const int* S = SETP(T, set);
    int other = S[SET_EXCL_SET];
    if (other < 0) return true;
    int range = S[SET_EXCL_CHUNKS];
    const int* O = SETP(T, other);
    int other2 = O[SET_EXCL_SET];
    for (int tx = sx - range; tx <= sx + range; tx++)
        for (int tz = sz - range; tz <= sz + range; tz++) {
            if (!place_baseChunk(T, other, tx, tz)) continue;
            if (other2 < 0) return false;
            // andere Menge hat selbst eine Exclusion Zone: deren Treffer ausschliessen (eine Ebene tiefer, Basispruefung)
            bool excluded = false;
            int r2 = O[SET_EXCL_CHUNKS];
            for (int ux = tx - r2; ux <= tx + r2 && !excluded; ux++)
                for (int uz = tz - r2; uz <= tz + r2; uz++)
                    if (place_baseChunk(T, other2, ux, uz)) { excluded = true; break; }
            if (!excluded) return false;
        }
    return true;
}
