// wg_struct_tilesynth.cu - Teststand des Kachelpfads: wie wg_struct_tile.cu, die Kacheln fuellt ein Referenz-Anbieter
// (wg_struct_envfill_synth) aus der analytischen Umgebung von wg_struct_synth.cu. Ergebnis muss mit dem direkten
// Analytik-Pfad identisch sein (Hoehenschritt 1).
#include "wg_struct_common.cuh"
#include "wg_struct_env_tile.cuh"
#include "wg_struct_gen.cuh"

__device__ __forceinline__ int tsynth_hash(int a, int b, int s) {
    uint h = (uint)a * 0x9E3779B1u ^ (uint)b * 0x85EBCA77u ^ (uint)s * 0xC2B2AE3Du;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return (int)h;
}
__device__ __forceinline__ int tsynth_terrain(int x, int z) {
    return 64 + (tsynth_hash(x >> 3, z >> 3, 1) & 7) + (((x >> 2) + (z >> 2)) & 3) - 4;
}

// Referenz-Anbieter: fuellt die Kachel jedes Kandidaten. Bindungen: wie gen (1 Kandidaten, 10 Env-Puffer). Ein Block je Kandidat.
extern "C" __global__ void __launch_bounds__(256) wg_struct_envfill_synth(StructPush p) {
    const StructTables& T = *structTables();
    const int* cands = (const int*)vf_b[1];
    int n = min(cands[0], p.capCand);
    for (int i = blockIdx.x; i < n; i += gridDim.x) {
        int off = cands[CAND_HDR + i * 4 + 3];
        if (off < 0) continue;
        int* t = env_tileAt(off);
        int hw = t[3];
        short* surf = (short*)(t + t[5]);
        short* flr = t[6] >= 0 ? (short*)(t + t[6]) : nullptr;
        for (int k = threadIdx.x; k < hw * hw; k += blockDim.x) {
            int x = t[1] + k % hw, z = t[2] + k / hw;
            int h = tsynth_terrain(x, z);
            surf[k] = (short)max(h, 63);
            if (flr) flr[k] = (short)h;
        }
        unsigned char* bio = (unsigned char*)(t + t[12]);
        int bw = t[9], bqh = t[11], nb = (int)T.resv[2];
        for (int k = threadIdx.x; k < bw * bw * bqh; k += blockDim.x) {
            int qx = t[7] + k % bw, qz = t[8] + (k / bw) % bw;
            bio[k] = (unsigned char)(((uint)tsynth_hash(qx >> 6, qz >> 6, 7)) % (uint)nb);
        }
        __syncthreads();
        if (threadIdx.x == 0) t[0] = 1;
    }
}

// Ring-Ebenen: je Ring 57 x 57 Quarts bei y = 0 (Ursprung ncx - 28, ncz - 28 aus den Ausgangspositionen in T.ringPos)
extern "C" __global__ void __launch_bounds__(256) wg_struct_ringfill_synth(StructPush p) {
    const StructTables& T = *structTables();
    unsigned char* planes = (unsigned char*)vf_b[12];
    int nb = (int)T.resv[2];
    for (int set = 0; set < (int)T.numSets; set++) {
        const int* S = SETP(T, set);
        if (S[SET_TYPE] != 1) continue;
        for (int ring = blockIdx.x; ring < S[SET_RING_COUNT]; ring += gridDim.x) {
            int idx = S[SET_RING_POS] + ring;
            int ix = T.ringPos[idx * 2], iz = T.ringPos[idx * 2 + 1];
            int ncx = ((ix << 4) + 8) >> 2, ncz = ((iz << 4) + 8) >> 2;
            for (int k = threadIdx.x; k < 57 * 57; k += blockDim.x) {
                int qx = ncx - 28 + k % 57, qz = ncz - 28 + k / 57;
                planes[(size_t)idx * 3249 + k] = (unsigned char)(((uint)tsynth_hash(qx >> 6, qz >> 6, 7)) % (uint)nb);
            }
        }
    }
}
