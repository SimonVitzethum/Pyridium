// wg_full_carve.cu - cave worms + canyons on the exact terrain states (wg_carve.cuh), one block per chunk,
// one thread per column. Stateless: needs only the chunk position (worms of neighbouring cells reach in).
// Bindings: 0 chunkXZ, 1 states, 2 flags, 3 CarveParams, 5 cfg.
#include "wg_full_common.cuh"

#define MAXCAP 1024

extern "C" __global__ void __launch_bounds__(256) wg_full_carve(FullPush p)
{
    __shared__ WcCap caps[MAXCAP];
    __shared__ int ncap;
    int c = blockIdx.x;
    if (c >= p.a[0]) return;
    int tid = threadIdx.x;
    const CarveParams cp = *gCarveP;
    const int minY = gCfg[CFG_MINY], H = gCfg[CFG_HEIGHT];
    const int sea = cp.seaLevel;
    int x0 = gChunkXZ[c * 2], z0 = gChunkXZ[c * 2 + 1];
    if (tid == 0) ncap = 0;
    __syncthreads();
    if (cp.flags & 1) {
        int cx0 = fdivn(x0, WC_CAVE_CELL) - 1, cx1 = fdivn(x0 + 15, WC_CAVE_CELL) + 1;
        int cz0 = fdivn(z0, WC_CAVE_CELL) - 1, cz1 = fdivn(z0 + 15, WC_CAVE_CELL) + 1;
        int ncx = cx1 - cx0 + 1, ncz = cz1 - cz0 + 1;
        int total = ncx * ncz * WC_MAX_WORMS;
        for (int item = tid; item < total; item += 256) {
            int w = item % WC_MAX_WORMS;
            int cell = item / WC_MAX_WORMS;
            int cx = cx0 + cell % ncx, cz = cz0 + cell / ncx;
            if (w >= wc_worm_count(cp, cx, cz)) continue;
            WcWorm o = wc_worm(cp, cx, cz, w);
            float px, py, pz, pr;
            wc_worm_pt(cp, o, 0, px, py, pz, pr);
            for (int k = 1; k <= WC_WORM_SEGS; k++) {
                float qx, qy, qz, qr;
                wc_worm_pt(cp, o, k, qx, qy, qz, qr);
                float r = fmaxf(pr, qr) + 1.0f;
                if (fmaxf(px, qx) + r >= (float)x0 && fminf(px, qx) - r <= (float)(x0 + 16) &&
                    fmaxf(pz, qz) + r >= (float)z0 && fminf(pz, qz) - r <= (float)(z0 + 16)) {
                    int i = atomicAdd(&ncap, 1);
                    if (i < MAXCAP) { WcCap cc = {px, py, pz, qx, qy, qz, fmaxf(pr, qr)}; caps[i] = cc; }
                }
                px = qx; py = qy; pz = qz; pr = qr;
            }
            if (o.roomK >= 0) {
                float rx, ry, rz, rr;
                wc_worm_pt(cp, o, o.roomK, rx, ry, rz, rr);
                float r = o.roomR + 1.0f;
                if (rx + r >= (float)x0 && rx - r <= (float)(x0 + 16) && rz + r >= (float)z0 && rz - r <= (float)(z0 + 16)) {
                    int i = atomicAdd(&ncap, 1);
                    if (i < MAXCAP) { WcCap cc = {rx, ry, rz, rx, ry, rz, o.roomR}; caps[i] = cc; }
                }
            }
        }
    }
    __syncthreads();
    int n = ncap < MAXCAP ? ncap : MAXCAP;
    int z = tid & 15, x = tid >> 4;
    int gx = x0 + x, gz = z0 + z;
    int* col = gStates + (size_t)c * H * 256 + z + x * 16;
    // ground = highest solid; ocean = the topmost non-air block is fluid
    int ground = -1;
    bool ocean = false;
    for (int yi = H - 1; yi >= 0; yi--) {
        int fl = gFlags[col[yi * 256] & 0x3fffffff];
        if (fl & F_AIR) continue;
        if (fl & F_FLUID) { ocean = ocean || ground < 0; continue; }
        ground = yi;
        break;
    }
    if (ground < 0) return;
    int groundY = minY + ground;
    int f = carveFloor(cp, gx, gz, groundY);
    uint bits[16];
    #pragma unroll
    for (int i = 0; i < 16; i++) bits[i] = 0;
    if (H <= 512) {
        float fx = (float)gx + 0.5f, fz = (float)gz + 0.5f;
        for (int i = 0; i < n; i++) {
            WcCap cap = caps[i];
            float r = cap.r;
            if (fx < fminf(cap.x0, cap.x1) - r || fx > fmaxf(cap.x0, cap.x1) + r) continue;
            if (fz < fminf(cap.z0, cap.z1) - r || fz > fmaxf(cap.z0, cap.z1) + r) continue;
            int ylo = (int)floorf(fminf(cap.y0, cap.y1) - r * 0.75f - 1.0f);
            int yhi = (int)ceilf(fmaxf(cap.y0, cap.y1) + r * 0.75f + 1.0f);
            if (ylo < minY + 2) ylo = minY + 2;
            if (yhi > groundY - 3) yhi = groundY - 3;
            for (int y = ylo; y <= yhi; y++) {
                float fy = (float)y + 0.5f;
                if (wc_cap_hit(cap.x0, cap.y0, cap.z0, cap.x1, cap.y1, cap.z1, r, fx, fy, fz)) {
                    int yi = y - minY;
                    bits[yi >> 5] |= 1u << (yi & 31);
                }
            }
        }
    }
    int yStart = (f != WC_NO_CARVE ? f - minY : ground - 2);
    if (yStart < 1) yStart = 1;
    int air = gCfg[CFG_AIR], water = gCfg[CFG_WATER], lava = gCfg[CFG_LAVA];
    for (int yi = 1; yi <= ground; yi++) {
        int y = minY + yi;
        bool canyon = f != WC_NO_CARVE && y >= f;
        bool cave = (bits[yi >> 5] >> (yi & 31)) & 1u;
        if (!canyon && !cave) continue;
        int s = col[yi * 256];
        int fl = gFlags[s & 0x3fffffff];
        if (!(fl & F_CARVABLE) || (fl & F_BEDROCK)) continue;
        int ns = y <= cp.lavaY ? lava : (canyon && ocean && y <= sea ? water : air);
        col[yi * 256] = ns;
    }
    (void)yStart;
}
