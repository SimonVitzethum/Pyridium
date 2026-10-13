// part of the wg_full_* family (full-chunk GPU generator), see docs/FULL_CHUNK_GEN.md
#include "wg_full_common.cuh"

// section packer: one block (256 threads) per (section, chunk). Record of SEC_STRIDE ints per section:
//   [0] palette size, [1] bits per entry (0 = single value), [2] overflow flag, [3] non-empty blocks, [4] fluid blocks,
//   [5] randomly ticking blocks, [6] randomly ticking fluids, [7] unused, [8 .. 8+256) palette (state ids), [264 .. 264+1024) packed longs as (lo, hi) int pairs.
// bindings: 1 states, 2 packOut, 5 cfg. push: a0 chunks, a1 sections per chunk
#define SEC_STRIDE 1288
extern "C" __global__ void __launch_bounds__(256) wg_full_pack(FullPush p)
{
    __shared__ int tkey[512];
    __shared__ unsigned char idx[4096];
    __shared__ short rank[512];
    __shared__ int wsum[8];
    __shared__ int sOverflow;
    __shared__ int sCnt[4];
    int sec = blockIdx.x, c = blockIdx.y;
    if (c >= p.a[0]) return;
    int tid = threadIdx.x;
    const int H = gCfg[CFG_HEIGHT];
    const int* st = gStates + ((size_t)c * H + sec * 16) * 256;
    int* out = (int*)vf_b[2] + ((size_t)c * p.a[1] + sec) * SEC_STRIDE;
    for (int i = tid; i < 512; i += 256) tkey[i] = -1;
    if (tid == 0) { sOverflow = 0; sCnt[0] = sCnt[1] = sCnt[2] = sCnt[3] = 0; }
    __syncthreads();
    int z = tid & 15, x = tid >> 4;
    int mine[16];
    int c0 = 0, c1 = 0, c2 = 0, c3 = 0;
    for (int ly = 0; ly < 16; ly++) {
        int key = st[ly * 256 + z + x * 16] & 0x3fffffff;
        mine[ly] = key;
        { int fl = ((const int*)vf_b[3])[key]; c0 += (fl & F_AIR) ? 0 : 1; c1 += (fl & F_HASFLUID) ? 1 : 0; c2 += (fl & F_RTICK) ? 1 : 0; c3 += (fl & F_FTICK) ? 1 : 0; }
        unsigned h = ((unsigned)key * 2654435761u) >> 23;      // 9 bits
        int tries = 0;
        for (;;) {
            int old = atomicCAS(&tkey[h], -1, key);
            if (old == -1 || old == key) break;
            h = (h + 1) & 511;
            if (++tries > 512) { sOverflow = 1; break; }
        }
    }
    atomicAdd(&sCnt[0], c0); atomicAdd(&sCnt[1], c1); atomicAdd(&sCnt[2], c2); atomicAdd(&sCnt[3], c3);
    __syncthreads();
    // rank = prefix count of used slots (2 slots per thread)
    int f0 = tkey[tid * 2] >= 0, f1 = tkey[tid * 2 + 1] >= 0;
    int v = f0 + f1, incl = v;
    int lane = tid & 31, warp = tid >> 5;
    for (int o = 1; o < 32; o <<= 1) {
        int n = __shfl_up_sync(0xffffffffu, incl, o);
        if (lane >= o) incl += n;
    }
    if (lane == 31) wsum[warp] = incl;
    __syncthreads();
    int base = 0;
    for (int w = 0; w < warp; w++) base += wsum[w];
    int excl = base + incl - v;
    rank[tid * 2] = (short)excl;
    rank[tid * 2 + 1] = (short)(excl + f0);
    int count = 0;
    for (int w = 0; w < 8; w++) count += wsum[w];
    if (f0 && excl < 256) out[8 + excl] = tkey[tid * 2];
    if (f1 && excl + f0 < 256) out[8 + excl + f0] = tkey[tid * 2 + 1];
    __syncthreads();
    for (int ly = 0; ly < 16; ly++) {
        int key = mine[ly];
        unsigned h = ((unsigned)key * 2654435761u) >> 23;
        while (tkey[h] != key) h = (h + 1) & 511;
        int r = rank[h];
        idx[(ly << 8) | (z << 4) | x] = (unsigned char)(r > 255 ? 255 : r);
    }
    __syncthreads();
    int pc = count > 256 ? 256 : count;
    int bits = gCfg[CFG_BITS_TABLE + pc];
    if (tid == 0) {
        out[0] = pc; out[1] = bits; out[2] = (count > 256 || sOverflow) ? 1 : 0; out[3] = sCnt[0]; out[4] = sCnt[1]; out[5] = sCnt[2]; out[6] = sCnt[3]; out[7] = 0;
    }
    if (bits > 0) {
        int vpl = 64 / bits;
        int nl = (4096 + vpl - 1) / vpl;
        for (int t = tid; t < nl; t += 256) {
            unsigned long long w = 0;
            for (int k = 0; k < vpl; k++) {
                int i = t * vpl + k;
                if (i < 4096) w |= (unsigned long long)idx[i] << (k * bits);
            }
            out[264 + t * 2] = (int)(unsigned)w;
            out[264 + t * 2 + 1] = (int)(unsigned)(w >> 32);
        }
    }
}

// ---------------------------------------------------------------------------------------------
