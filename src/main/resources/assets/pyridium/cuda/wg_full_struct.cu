// wg_full_struct.cu - Strukturen im Vollchunk-Generator (Nahwelt/integrierter Server): echte Bloecke aus der GPU-Strukturarena.
// Stufe nach Ozean/Bewuchs (FullChunkPipeline.addStage, stageMask Bit 4), vor Hoehenkarten/Licht/Packen. Zustandslos: Block je Spalte/y
// aus (Seed, Vorlagentabellen, Arena); die Arena fuellt FullStructures bei Bedarf (Regionen um die Chunks, ohne Nachbarwissen).
//   Bindungen wie die anderen wg_full_*-Kerne (0 chunkXZ, 1 states, 2 flags, 5 cfg), 6 = StructTables-Sicht (StructureGpu.viewBuffer)
//   Push: a0 = Chunkzahl (Pipeline), a1 = Zustands-Id von Erde (Fundament statt Gras/Schnee/Podsol), a2 = Zustands-Id Podsol-aehnlicher Bloecke (0 = keine)
// Je Spalte: Bodenhoehe aus den Zustaenden (hoechster fester, nicht-Laub-Block), struct_columnBegin mit dieser Hoehe (terrain_matching),
// Strukturbloecke setzen (Zustand 0 = Luft raeumt das Gelaende), danach Fundament: Luft/Fluessigkeit unter dem tiefsten Strukturblock bis
// zum echten Boden (hoechstens 48 Bloecke) mit dem Material dieses Blocks (wg_struct_adapt.cuh).
#include "wg_full_common.cuh"
#include "wg_struct_adapt.cuh"

#define gStruct ((const StructTables*)vf_b[6])

extern "C" __global__ void __launch_bounds__(256) wg_full_struct(FullPush p)
{
    int c = blockIdx.y;
    if (c >= p.a[0]) return;
    int tid = blockIdx.x * 256 + threadIdx.x;
    if (tid >= 256) return;
    const int minY = gCfg[CFG_MINY], H = gCfg[CFG_HEIGHT], airId = gCfg[CFG_AIR];
    int x0 = gChunkXZ[c * 2], z0 = gChunkXZ[c * 2 + 1];
    int z = tid & 15, x = tid >> 4;
    int* col = gStates + (size_t)c * H * 256 + z + x * 16;
    const StructTables& T = *gStruct;
    // Bodenhoehe: erste Luft ueber dem hoechsten festen Block (ohne Laub)
    int G = minY;
    for (int yi = H - 1; yi >= 0; yi--) {
        int fl = gFlags[col[yi * 256] & 0x3fffffff];
        if ((fl & F_MOTION) && !(fl & F_LEAVES)) { G = minY + yi + 1; break; }
    }
    StructCol sc;
    struct_columnBegin(T, sc, x0 + x, z0 + z, 1, G);
    if (!sc.n) return;
    for (int yi = 0; yi < H; yi++) {
        unsigned r = struct_columnAt(T, sc, minY + yi);
        if (!r) continue;
        int st = (int)(r & 0x7fffffffu);
        col[yi * 256] = st == 0 ? airId : st;
    }
    int yLow, fs;
    if (struct_columnLow(T, sc, yLow, fs)) {
        int fl = gFlags[fs & 0x3fffffff];
        int fill = (fl & (F_GRASS)) ? p.a[1] : fs;
        if (fill == 0) fill = fs;
        for (int y = yLow - 1; y >= minY && yLow - y <= STRUCT_FOUND_MAX; y--) {
            int yi = y - minY;
            int cur = gFlags[col[yi * 256] & 0x3fffffff];
            if (!(cur & (F_AIR | F_FLUID | F_LEAVES))) break;      // echter Boden
            col[yi * 256] = fill;
        }
    }
}
