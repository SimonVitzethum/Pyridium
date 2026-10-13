// wg_struct_synth.cu - Teststand der Strukturgenerierung mit analytischer Umgebung (Hoehe und Biom als
// reine Integer-Funktionen, in StructureHarness bitgleich fuer Vanilla nachgebaut). Prueft Platzierung und
// Jigsaw-Loeser gegen Vanilla unabhaengig vom Gelaende. Produktion: wg_struct_tile.cu.
#include "wg_struct_common.cuh"

__device__ __forceinline__ int synth_hash(int a, int b, int s) {
    uint h = (uint)a * 0x9E3779B1u ^ (uint)b * 0x85EBCA77u ^ (uint)s * 0xC2B2AE3Du;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return (int)h;
}

// Gelaendehoehe h: Fels unterhalb h, Wasser von h bis zum Meeresspiegel (63)
__device__ __forceinline__ int synth_terrain(int x, int z) {
    return 64 + (synth_hash(x >> 3, z >> 3, 1) & 7) + (((x >> 2) + (z >> 2)) & 3) - 4;
}

// erste freie Hoehe: Meeresboden-Heightmaps = Fels, Oberflaechen-Heightmaps = max(Fels, Meeresspiegel)
__device__ int penv_height(const StructTables& T, int x, int z, int hm) {
    int h = synth_terrain(x, z);
    if (hm == HM_OCEAN_FLOOR_WG || hm == HM_OCEAN_FLOOR) return h;
    return max(h, 63);
}

// Basisspalte: 0 Luft, 1 Wasser, 2 Fels
__device__ int penv_column(const StructTables& T, int x, int y, int z) {
    int h = synth_terrain(x, z);
    if (y < h) return 2;
    return y < 63 ? 1 : 0;
}

// Biom je Quart: Zellen von 64 x 64 Quarts (256 Bloecke) mit Hash-Biom
__device__ int penv_biome(const StructTables& T, int qx, int qy, int qz) {
    int n = (int)T.resv[2];
    return (int)(((uint)synth_hash(qx >> 6, qz >> 6, 7)) % (uint)n);
}

// Haken fuer Umgebungs-Kacheln: die analytische Umgebung braucht keine
__device__ int penv_initCandidate(const StructTables& T, const int* S, int cx, int cz) { return -1; }
__device__ void penv_selectCandidate(int tid, int tileOffset) {}
__device__ int penv_ringBiome(const StructTables& T, int set, int ring, int qx, int qz, int ncx, int ncz) { return penv_biome(T, qx, 0, qz); }

#include "wg_struct_gen.cuh"
