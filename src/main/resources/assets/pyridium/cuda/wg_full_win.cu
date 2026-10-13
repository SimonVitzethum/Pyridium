// part of the wg_full_* family (full-chunk GPU generator), see docs/FULL_CHUNK_GEN.md
#include "wg_full_common.cuh"

// window gather: biome indices of the 6x6 quart window (x/z quart -1..4 around the chunk) for the terrain kernels.
// bindings: 0 bioOut (slot * quarts + qy + (qx + qz * 4) * qh), 1 winSlots (c * 9 + (dz+1) * 3 + dx+1 -> slot),
//           2 bioMap (biome pipe index -> terrain pipe index), 3 window out (c * 36 * qh + y + (ix + iz * 6) * qh)
// push: a0 chunks, a1 qh (terrain window rows), a2 quarts per chunk (biome output), a3 vertical subsampling ys, a4 qh of the biome output
extern "C" __global__ void __launch_bounds__(128) wg_full_win(FullPush p)
{
    int c = blockIdx.y;
    int qh = p.a[1];
    int t = blockIdx.x * blockDim.x + threadIdx.x;
    if (c >= p.a[0] || t >= 36 * qh) return;
    const int* bio = (const int*)vf_b[0];
    const int* slots = (const int*)vf_b[1];
    const int* map = (const int*)vf_b[2];
    int* win = (int*)vf_b[3];
    int y = t % qh, cell = t / qh;
    int ix = cell % 6, iz = cell / 6;
    int qx = ix - 1, qz = iz - 1;
    int dxc = qx < 0 ? -1 : (qx > 3 ? 1 : 0), dzc = qz < 0 ? -1 : (qz > 3 ? 1 : 0);
    int lx = qx - 4 * dxc, lz = qz - 4 * dzc;
    int slot = slots[c * 9 + (dzc + 1) * 3 + dxc + 1];
    int b = bio[(size_t)slot * p.a[2] + y / p.a[3] + (lx + lz * 4) * p.a[4]];
    win[(size_t)c * 36 * qh + t] = map[b];
}

// ---------------------------------------------------------------------------------------------
