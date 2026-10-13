// part of the wg_full_* family (full-chunk GPU generator), see docs/FULL_CHUNK_GEN.md
#include "wg_full_common.cuh"

// heightmaps + sky light. One block per chunk, one thread per column.
// bindings: 1 states, 2 flags, 3 heightOut (4 maps x HSTRIDE longs per chunk, as int pairs), 4 lightOut, 5 cfg
// maps: 0 WORLD_SURFACE (not air), 1 OCEAN_FLOOR (blocks motion), 2 MOTION_BLOCKING (motion or fluid),
//       3 MOTION_BLOCKING_NO_LEAVES. Value = highest matching y - minY + 1 (0 = none).
// sky light: nibble per block, per chunk H*128 bytes ordered like sections ((y << 8) | (z << 4) | x), vertical only.
#define HSTRIDE 64
extern "C" __global__ void __launch_bounds__(256) wg_full_height(FullPush p)
{
    int c = blockIdx.x;
    if (c >= p.a[0]) return;
    int tid = threadIdx.x;
    const int H = gCfg[CFG_HEIGHT];
    int z = tid & 15, x = tid >> 4;
    const int* col = gStates + (size_t)c * H * 256 + z + x * 16;
    unsigned char* light = p.a[2] ? (unsigned char*)vf_b[4] + (size_t)c * H * 128 : nullptr;
    int h0 = 0, h1 = 0, h2 = 0, h3 = 0;
    int L = 15;
    unsigned char* lcol = light;
    for (int yi = H - 1; yi >= 0; yi--) {
        int fl = gFlags[col[yi * 256] & 0x3fffffff];
        int y1 = yi + 1;
        if (!(fl & F_AIR) && !h0) h0 = y1;
        if ((fl & F_MOTION) && !h1) h1 = y1;
        if ((fl & F_HM_MB) && !h2) h2 = y1;
        if ((fl & F_HM_NL) && !h3) h3 = y1;
        if (lcol) {
            if (fl & F_OPAQUE) L = 0;
            else if ((fl & F_DAMP) && L > 0) L--;
            int off = (yi << 8) | (z << 4) | x;
            unsigned char* b = lcol + (off >> 1);
            // each nibble pair belongs to x = 2k, 2k+1 (other thread): use atomicOr on the containing word
            unsigned int* wp = (unsigned int*)((size_t)b & ~(size_t)3);
            int sh = (int)(((size_t)b & 3) * 8) + ((off & 1) ? 4 : 0);
            atomicOr(wp, (unsigned int)L << sh);
        }
    }
    __shared__ unsigned short hs[4][256];
    hs[0][tid] = h0; hs[1][tid] = h1; hs[2][tid] = h2; hs[3][tid] = h3;
    __syncthreads();
    int bits = gCfg[CFG_HBITS];
    int vpl = 64 / bits;
    int nl = (256 + vpl - 1) / vpl;
    int* ho = (int*)vf_b[3] + (size_t)c * 4 * HSTRIDE * 2;
    for (int m = 0; m < 4; m++) {
        if (tid < nl) {
            unsigned long long w = 0;
            for (int k = 0; k < vpl; k++) {
                int i = tid * vpl + k;
                // Heightmap index is x + z * 16 (Heightmap.getIndex(x, z)); our thread index is z + x * 16
                if (i < 256) w |= (unsigned long long)hs[m][((i & 15) << 4) | (i >> 4)] << (k * bits);
            }
            ho[(m * HSTRIDE + tid) * 2] = (int)(unsigned)w;
            ho[(m * HSTRIDE + tid) * 2 + 1] = (int)(unsigned)(w >> 32);
        }
    }
}

