// wg_full_ore.cu - ore blobs (wg_carve.cuh, same blobs as oreAt) rasterised into the chunk states.
// One block per chunk: gather all blobs touching the chunk into shared memory, then thread-per-blob raster.
// Bindings: 0 chunkXZ, 1 states, 2 flags, 4 OreParams, 5 cfg.
#include "wg_full_common.cuh"

#define MAXBLOB 1400

extern "C" __global__ void __launch_bounds__(256) wg_full_ore(FullPush p)
{
    __shared__ WcBlob blobs[MAXBLOB];
    __shared__ int nblob;
    int c = blockIdx.x;
    if (c >= p.a[0]) return;
    int tid = threadIdx.x;
    const OreParams& op = *gOreP;
    const int minY = gCfg[CFG_MINY], H = gCfg[CFG_HEIGHT];
    int x0 = gChunkXZ[c * 2], z0 = gChunkXZ[c * 2 + 1];
    int ccx = x0 >> 4, ccz = z0 >> 4;
    if (tid == 0) nblob = 0;
    __syncthreads();
    int cyLo = (minY >> 4) - 1, cyHi = ((minY + H - 1) >> 4) + 1;
    for (int e = 0; e < op.count; e++) {
        const OreEntry& o = op.e[e];
        int reach = (int)(o.radius * 1.3f + 1.0f) + 1;
        int ylo = (o.minY - reach) >> 4, yhi = (o.maxY + reach) >> 4;
        if (ylo < cyLo) ylo = cyLo;
        if (yhi > cyHi) yhi = cyHi;
        int ny = yhi - ylo + 1;
        if (ny <= 0) continue;
        int total = 9 * ny;
        for (int t = tid; t < total; t += 256) {
            int dx = t % 3 - 1, dz = (t / 3) % 3 - 1, cy = ylo + t / 9;
            int cx = ccx + dx, cz = ccz + dz;
            wc_u64 h = wc_ore_cell_hash(op, e, cx, cy, cz);
            int n = wc_ore_cell_count(o, h);
            for (int j = 0; j < n; j++) {
                WcBlob b;
                if (!wc_ore_blob(op, e, h, j, cx, cy, cz, b)) continue;
                float mx = fmaxf(b.rx, fmaxf(b.ry, b.rz));
                if (b.x + mx < (float)x0 || b.x - mx > (float)(x0 + 16) || b.z + mx < (float)z0 || b.z - mx > (float)(z0 + 16)) continue;
                int i = atomicAdd(&nblob, 1);
                if (i < MAXBLOB) blobs[i] = b;
            }
        }
    }
    __syncthreads();
    int nb = nblob < MAXBLOB ? nblob : MAXBLOB;
    int* st = gStates + (size_t)c * H * 256;
    for (int i = tid; i < nb; i += 256) {
        WcBlob b = blobs[i];
        const OreEntry& o = op.e[b.entry];
        int xa = max(x0, (int)floorf(b.x - b.rx - 1.0f)), xb = min(x0 + 15, (int)ceilf(b.x + b.rx + 1.0f));
        int za = max(z0, (int)floorf(b.z - b.rz - 1.0f)), zb = min(z0 + 15, (int)ceilf(b.z + b.rz + 1.0f));
        int ya = max(minY, (int)floorf(b.y - b.ry - 1.0f)), yb = min(minY + H - 1, (int)ceilf(b.y + b.ry + 1.0f));
        for (int y = ya; y <= yb; y++)
            for (int z = za; z <= zb; z++)
                for (int x = xa; x <= xb; x++) {
                    if (!wc_ore_voxel(op, b, x, y, z)) continue;
                    int* sp = st + (size_t)(y - minY) * 256 + (z - z0) + (x - x0) * 16;
                    int old = *sp;
                    int fl = gFlags[old & 0x3fffffff];
                    int target = (fl & F_ORE_STONE) ? o.stateStone : ((fl & F_ORE_DEEP) ? o.stateDeep : -1);
                    if (target < 0) continue;
                    atomicCAS(sp, old, target | (old & (int)0xC0000000));
                }
    }
}
