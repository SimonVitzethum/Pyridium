// wg_struct_env_tile.cuh - Produktions-Umgebung der Strukturgenerierung: Hoehen und Biome aus Kacheln je Kandidat.
//
// Warum Kacheln: der Jigsaw-Loeser fragt die Gelaendehoehe an beliebigen, vom bisherigen Verlauf abhaengigen
// Stellen ab (Strassen/terrain_matching, Hoehenprojektion), das Biom an der Startposition und die Biomsaeule der
// Chunkmitte. Der Geraete-Code der exakten Dichte ist (je Welt per NVRTC erzeugt) nicht aufrufbar; stattdessen
// liefert ein Anbieter (der Haken der LOD-/Terrain-Pipeline, siehe docs/STRUCTURES.md) je Kandidat eine Kachel,
// nachdem wg_struct_place die Kacheln angelegt hat (Geometrie im Kopf, Daten noch leer).
//
// Kachel (int-Worte, ab ENV_BASE + Offset im Env-Puffer vf_b[10]):
//   [0] gueltig (der Anbieter setzt 1 nach dem Befuellen)   [1] hx0   [2] hz0   (Block-Ursprung des Hoehengitters)
//   [3] hw (Spalten je Seite)   [4] hstep (1)   [5] Offset Oberflaechenhoehen (int16, Spalte hz*hw+hx, erster freier y,
//   wie ChunkGenerator.getBaseHeight(WORLD_SURFACE_WG))   [6] Offset Meeresboden-Hoehen (OCEAN_FLOOR_WG) oder -1
//   [7] bx0 [8] bz0 (Quart-Ursprung des Biomvolumens)  [9] bw (12 Quarts)  [10] bqy0  [11] bqh (Quart-Hoehe)
//   [12] Offset Biomvolumen (uint8 je Quart, Index (qy*12 + qz)*12 + qx, Wert = Biom-Index im Universum, 255 = ungueltig)
// Env-Puffer: int[0] = belegte ints (atomar), int[1] = Kapazitaet, int[2] = Ueberlauf-Flag, ab ENV_BASE die Kacheln.
// vf_b[11]: je gen-Thread der Offset der aktuellen Kachel (vom gen-Kernel gesetzt).
#pragma once
#include "wg_struct_common.cuh"

#define ENV_BUF ((int*)vf_b[10])
#define ENV_CUR ((int*)vf_b[11])
#define ENV_BASE 4
#define ENV_RINGPLANES ((const unsigned char*)vf_b[12])

__device__ __forceinline__ int* env_tileAt(int off) { return ENV_BUF + ENV_BASE + off; }

__device__ int penv_initCandidate(const StructTables& T, const int* S, int cx, int cz) {
    int ints = S[SET_ENV_INTS];
    if (ints <= 0) return -1;
    int* env = ENV_BUF;
    int off = atomicAdd(&env[0], ints);
    if (off + ints > env[1]) { atomicOr(&env[2], 1); return -1; }
    int* t = env + ENV_BASE + off;
    int R = S[SET_ENV_R], hw = 2 * R + 16;
    int surf = (hw * hw + 1) / 2;
    bool floorGrid = (S[SET_ENV_FLAGS] & 1) != 0;
    t[0] = 0;
    t[1] = cx * 16 - R; t[2] = cz * 16 - R; t[3] = hw; t[4] = 1;
    t[5] = 16;
    t[6] = floorGrid ? 16 + surf : -1;
    t[7] = (cx * 16 - 16) >> 2; t[8] = (cz * 16 - 16) >> 2; t[9] = 12;
    t[10] = (int)T.minY >> 2; t[11] = (int)T.height >> 2;
    t[12] = 16 + surf + (floorGrid ? surf : 0);
    return off;
}

__device__ void penv_selectCandidate(int tid, int tileOffset) { ENV_CUR[tid] = tileOffset; }

// ein Warp je Slot (wg_struct_gen): alle Lanes teilen die aktuelle Kachel des Slots (= blockIdx.x)
__device__ __forceinline__ int env_curTile() { return ENV_CUR[blockIdx.x]; }

__device__ int penv_height(const StructTables& T, int x, int z, int hm) {
    int off = env_curTile();
    if (off < 0) return (int)T.seaLevel;
    const int* t = env_tileAt(off);
    if (!t[0]) return (int)T.seaLevel;
    int hw = t[3];
    bool floor = (hm == HM_OCEAN_FLOOR_WG || hm == HM_OCEAN_FLOOR) && t[6] >= 0;
    const short* grid = (const short*)(t + (floor ? t[6] : t[5]));
    int ix = min(max(x - t[1], 0), hw - 1), iz = min(max(z - t[2], 0), hw - 1);
    return grid[iz * hw + ix];
}

// Basisspalte aus den Hoehen: unter dem Meeresboden fest, darueber bis zur Oberflaeche Fluessigkeit, sonst Luft
__device__ int penv_column(const StructTables& T, int x, int y, int z) {
    int surface = penv_height(T, x, z, HM_WORLD_SURFACE_WG);
    int floor = penv_height(T, x, z, HM_OCEAN_FLOOR_WG);
    if (y < floor) return 2;
    return y < surface ? 1 : 0;
}

__device__ int penv_biome(const StructTables& T, int qx, int qy, int qz) {
    int off = env_curTile();
    if (off < 0) return -1;
    const int* t = env_tileAt(off);
    if (!t[0]) return -1;
    int bw = t[9], bqh = t[11];
    int ix = min(max(qx - t[7], 0), bw - 1), iz = min(max(qz - t[8], 0), bw - 1), iy = min(max(qy - t[10], 0), bqh - 1);
    unsigned int v = ((const unsigned char*)(t + t[12]))[(iy * bw + iz) * bw + ix];
    return v == 255u ? -1 : (int)v;
}

// Ring-Suche (Strongholds): Biom bei y = 0 aus der Ebene des Rings (57 x 57 Quarts um das Zentrum, Ursprung ncx - 28, ncz - 28)
__device__ int penv_ringBiome(const StructTables& T, int set, int ring, int qx, int qz, int ncx, int ncz) {
    const int* S = T.sets + set * SET_W;
    int idx = S[SET_RING_POS] + ring;
    int ix = qx - (ncx - 28), iz = qz - (ncz - 28);
    unsigned int v = ENV_RINGPLANES[(size_t)idx * 3249 + iz * 57 + ix];
    return v == 255u ? -1 : (int)v;
}
