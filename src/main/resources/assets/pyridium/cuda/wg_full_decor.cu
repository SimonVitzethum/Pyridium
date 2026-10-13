// wg_full_decor.cu - surface decoration of the full-chunk generator: ground cover (grass, flowers, ...) and trees.
// Stateless per chunk. Trees are only placed with trunks in the inner 10x10 columns of a chunk so that their canopy
// never crosses the chunk border (no neighbour terrain needed; documented limitation in docs/FULL_CHUNK_GEN.md).
// Biome table (built from the datapack biome features by FullDecor.java), per biome-pipe index, DECOR_STRIDE ints:
//   [0] tree rate (trees per chunk, 8.8 fixed point)  [1] number of tree kinds (<= 4)
//   [2 + 12 k ...] kind k: trunk id, leaf ids for distance 1..7, base height, random height, foliage radius, shape
//   [50] number of ground cover entries (<= 8)  [51 + 2 j] cover j: state id, rate (columns per chunk, 8.8 fixed)
// Bindings: 0 chunkXZ, 1 states, 2 flags, 3 decor table, 4 biome out, 5 cfg, 6 window slots (centre = c * 9 + 4).
#include "wg_full_common.cuh"

#define DECOR_STRIDE 96
#define CFG_SEED 10
#define CFG_QH 12
#define CFG_QUARTS 13
#define CFG_YS 14
#define gDecor ((const int*)vf_b[3])
#define gBioOut ((const int*)vf_b[4])
#define gSlots ((const int*)vf_b[6])

__device__ __forceinline__ void put_leaf(int* st, int x, int y, int z, int minY, int H, int id)
{
    int yi = y - minY;
    if (x < 0 || x > 15 || z < 0 || z > 15 || yi < 0 || yi >= H) return;
    int* sp = st + (size_t)yi * 256 + z + x * 16;
    atomicCAS(sp, 0, id);      // only into air (state id 0 = air)
}

extern "C" __global__ void __launch_bounds__(256) wg_full_decor(FullPush p)
{
    __shared__ short sGround[256];
    __shared__ unsigned sCand[256];
    int c = blockIdx.x;
    if (c >= p.a[0]) return;
    int tid = threadIdx.x;
    const int minY = gCfg[CFG_MINY], H = gCfg[CFG_HEIGHT], sea = gCfg[CFG_SEA];
    const int qh = gCfg[CFG_QH], quarts = gCfg[CFG_QUARTS];
    wc_u64 seed = ((wc_u64)(unsigned)gCfg[CFG_SEED + 1] << 32) | (unsigned)gCfg[CFG_SEED];
    int x0 = gChunkXZ[c * 2], z0 = gChunkXZ[c * 2 + 1];
    int z = tid & 15, x = tid >> 4;
    int* st = gStates + (size_t)c * H * 256;
    int* col = st + z + x * 16;
    int ground = -1;
    int gfl = 0;
    for (int yi = H - 1; yi >= 0; yi--) {
        int fl = gFlags[col[yi * 256] & 0x3fffffff];
        if (fl & F_AIR) continue;
        if (fl & F_FLUID) break;               // underwater: no land decoration
        ground = yi;
        gfl = fl;
        break;
    }
    bool open = ground >= 0 && ground + 1 < H && (gFlags[col[(ground + 1) * 256] & 0x3fffffff] & F_AIR) && (minY + ground >= sea - 1);
    sGround[tid] = (short)(open ? ground : -1);
    sCand[tid] = 0xffffffffu;
    const int* rec = nullptr;
    if (open) {
        int slot = gSlots[c * 9 + 4];
        int b = gBioOut[(size_t)slot * quarts + ((ground >> 2) / gCfg[CFG_YS]) + ((x >> 2) + (z >> 2) * 4) * qh];
        rec = gDecor + b * DECOR_STRIDE;
        wc_u64 h = wc_h4(seed, x0 + x, z0 + z, 0xDEC0, 1);
        // ground cover on the surface block
        if (gfl & (F_GRASS | F_SOLID_GROUND)) {
            int nc = rec[50];
            float f = wc_f(h, 0);
            float acc = 0.0f;
            for (int j = 0; j < nc && j < 8; j++) {
                acc += (float)rec[52 + 2 * j] * (1.0f / 256.0f / 256.0f);
                if (f < acc) {
                    col[(ground + 1) * 256] = rec[51 + 2 * j];
                    break;
                }
            }
        }
        // tree candidate
        if ((gfl & (F_GRASS | F_SOLID_GROUND)) && rec[1] > 0) {
            wc_u64 h2 = wc_h4(seed, x0 + x, z0 + z, 0xDEC0, 2);
            int nk = rec[1];
            int k = (int)(wc_f(h2, 0) * (float)nk);
            if (k >= nk) k = nk - 1;
            const int* kd = rec + 2 + 12 * k;
            int m = kd[10] + (kd[11] >= 1 && kd[11] <= 3 ? 1 : 0);
            if (m < 2) m = 2;
            if (m > 6) m = 6;
            int side = 16 - 2 * m;
            if (x >= m && x <= 15 - m && z >= m && z <= 15 - m) {
                float p1 = (float)rec[0] * (1.0f / 256.0f) / (float)(side * side);
                if (wc_f(h, 1) < p1) sCand[tid] = (unsigned)(wc_mix(h) >> 32);
            }
        }
    }
    __syncthreads();
    if (sCand[tid] == 0xffffffffu || rec == nullptr) return;
    // spacing: a lower-hash candidate within 2 columns wins
    for (int dx = -2; dx <= 2; dx++)
        for (int dz = -2; dz <= 2; dz++) {
            if (!dx && !dz) continue;
            int nx = x + dx, nz = z + dz;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            unsigned o = sCand[nz + nx * 16];
            if (o < sCand[tid] || (o == sCand[tid] && (nz + nx * 16) < tid)) return;
        }
    wc_u64 h = wc_h4(seed, x0 + x, z0 + z, 0xDEC0, 2);
    int nk = rec[1];
    int k = (int)(wc_f(h, 0) * (float)nk);
    if (k >= nk) k = nk - 1;
    const int* kd = rec + 2 + 12 * k;
    int trunk = kd[0];
    int baseH = kd[8], randH = kd[9], rad = kd[10], shape = kd[11];
    int th = baseH + (int)(wc_f(h, 1) * (float)(randH + 1));
    if (th < 2) th = 2;
    int gy = minY + sGround[tid];
    int top = gy + th;
    // canopy first (only into air), trunk afterwards (overwrites leaves)
    int ytop = shape == 1 || shape == 2 ? top + 1 : top + 1;
    if (shape == 0 || shape == 4) {                 // blob / bushy
        for (int dy = -2; dy <= 1; dy++) {
            int r = (dy >= 0) ? rad - 1 : rad;
            if (shape == 4 && dy < 0) r = rad;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 1 && abs(dx) == r && abs(dz) == r && (dy >= 0 || wc_f(h, 2 + ((dx + 8) * 17 + dz + 8) % 40) < 0.5f)) continue;
                    int d = 1 + abs(dx) + abs(dz) + (dy < 0 ? 0 : 0);
                    d = d > 7 ? 7 : d;
                    put_leaf(st, x + dx, top + dy, z + dz, minY, H, kd[1 + d - 1]);
                }
        }
    } else if (shape == 1 || shape == 2) {          // spruce / pine cone
        int layers = th - (shape == 1 ? 1 : 2);
        if (layers < 3) layers = 3;
        for (int i = 0; i <= layers; i++) {
            int y = top + 1 - i;
            int r = shape == 1 ? (i == 0 ? 0 : 1 + (i - 1) / 2 % (rad + 1)) : (i == 0 ? 0 : (i % 2 ? 1 : 2) * (rad > 1 ? 1 : 1));
            if (shape == 1 && i > 0) r = (i + 1) / 2;
            if (r > rad + 1) r = rad + 1;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (abs(dx) + abs(dz) > r + (r > 1 ? 1 : 0)) continue;
                    int d = 1 + (abs(dx) + abs(dz) + 1) / 2;
                    d = d > 7 ? 7 : d;
                    put_leaf(st, x + dx, y, z + dz, minY, H, kd[1 + d - 1]);
                }
        }
    } else {                                        // acacia / flat canopy
        for (int dy = 0; dy <= 1; dy++) {
            int r = dy == 0 ? rad + 1 : rad;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (abs(dx) == r && abs(dz) == r) continue;
                    int d = 1 + (abs(dx) + abs(dz)) / 2;
                    d = d > 7 ? 7 : d;
                    put_leaf(st, x + dx, top + dy, z + dz, minY, H, kd[1 + d - 1]);
                }
        }
    }
    (void)ytop;
    for (int i = 1; i <= th; i++) {
        int yi = gy + i - minY;
        if (yi >= H) break;
        int* sp = st + (size_t)yi * 256 + z + x * 16;
        int old = *sp;
        int fl = gFlags[old & 0x3fffffff];
        if ((fl & (F_AIR | F_LEAVES)) || !(fl & F_MOTION)) *sp = trunk;
    }
}
