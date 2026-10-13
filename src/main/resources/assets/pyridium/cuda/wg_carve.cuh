// wg_carve.cuh - stateless, include-only carvers and ores (Pyridium GPU worldgen).
// No vf_b bindings, no chunk arrays: every function depends only on (params, x, y, z[, groundY]).
// Used by BOTH the near-world full-chunk generator (wg_full_*.cu) and the LOD voxel kernels, so
// both produce the same caves, canyons and ore veins. "Vanilla-like", not bit-exact.
//
// ---------------------------------------------------------------------------------------------
// API (all __host__ __device__ unless noted; units = blocks, y absolute)
//
//   CarveParams p = carveDefaults(seed, minY, maxY, seaLevel, lavaY);   // plain struct, copy to device
//
//   // CAVES (worms: tunnels + rooms, cells of 64x64 blocks, reach <= 64 so 3x3 cells suffice)
//   bool carvedAt(const CarveParams&, int x, int y, int z, int groundY);
//        true if the block is hollowed by a cave worm OR a canyon (groundY = terrain surface y of the
//        column, i.e. highest solid block; caves keep a >=3 block roof under groundY). SLOW per call
//        (scans up to 9 cells x ~8 worms x 12 segments): for bulk use the gather API below.
//   int  carveGather(const CarveParams&, int x0,int y0,int z0, int x1,int y1,int z1 /*inclusive box*/,
//                    WcCap* out, int maxOut);      // capsules of all cave worms touching the box
//   bool carveCapsHit(const WcCap* caps, int n, int x, int y, int z);   // cheap per-voxel test on a gathered list
//        Typical use: one gather per chunk/tile (<= ~256 capsules), then carveCapsHit per voxel.
//
//   // CANYONS (ravines, cells of 128x128, always open to the sky): column level
//   int  carveFloor(const CarveParams&, int x, int z, int groundY);
//        lowest y that is carved in this column (everything from carveFloor up to the terrain surface
//        is air), or WC_NO_CARVE (INT_MAX) when no canyon crosses the column. Clear solid bits >= it.
//        carveFloor is already folded into carvedAt.
//
//   // FLUID inside carved space (caller applies): y <= p.lavaY -> lava, else air. (Vanilla: lava below -54.)
//
//   // ORES (blobs from datapack ore features, 16^3 cells, any number of entries <= WC_MAX_ORE)
//   int  oreAt(const OreParams&, int x, int y, int z, int baseStateIsDeepslate);
//        returns the ore block state id (stone or deepslate variant) or -1. Caller only calls it where the
//        block is replaceable (target rule: stone-ish base). First matching entry wins.
//   OreEntry host builder: oreEntry(minY, maxY, triangle, count, rarityInv, size, discard, stoneId, deepId, salt)
//        count = attempts per chunk, rarityInv = 1/rarity (1 if none), size = vein size, discard = discard_chance_on_air_exposure
//        (applied as a per-voxel ragged-edge discard).
//
// All randomness = splitmix64 of (seed, cell, index): no sequential state, any order, any LOD.
// ---------------------------------------------------------------------------------------------
#pragma once
#include <limits.h>

#ifndef WC_HD
#define WC_HD __host__ __device__ __forceinline__
#endif

typedef unsigned long long wc_u64;
#define WC_NO_CARVE INT_MAX
#define WC_MAX_ORE 64
#define WC_CAVE_CELL 64
#define WC_CANYON_CELL 128
#define WC_WORM_SEGS 12
#define WC_WORM_STEP 4.0f
#define WC_CANYON_SEGS 24
#define WC_MAX_WORMS 32

struct CarveParams {
    wc_u64 seed;
    int minY, maxY, seaLevel, lavaY;
    float caveMean;      // mean worms per 64x64 cell (default 8)
    float roomChance;    // chance per worm of a big room (default 0.15)
    float caveScale;     // radius multiplier (default 1)
    float canyonChance;  // chance per 128x128 cell (default 0.8)
    int flags;           // bit0 caves, bit1 canyons (default 3)
};

struct WcCap { float x0, y0, z0, x1, y1, z1, r; };

WC_HD CarveParams carveDefaults(wc_u64 seed, int minY, int maxY, int seaLevel, int lavaY)
{
    CarveParams p;
    p.seed = seed; p.minY = minY; p.maxY = maxY; p.seaLevel = seaLevel; p.lavaY = lavaY;
    p.caveMean = 8.0f; p.roomChance = 0.15f; p.caveScale = 1.0f; p.canyonChance = 0.8f; p.flags = 3;
    return p;
}

// ---------- hashing ----------
WC_HD wc_u64 wc_mix(wc_u64 z)
{
    z += 0x9E3779B97F4A7C15ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}
WC_HD wc_u64 wc_h4(wc_u64 seed, int a, int b, int c, int d)
{
    wc_u64 h = wc_mix(seed ^ ((wc_u64)(unsigned)a | ((wc_u64)(unsigned)b << 32)));
    return wc_mix(h ^ ((wc_u64)(unsigned)c | ((wc_u64)(unsigned)d << 32)));
}
// n-th uniform float in [0,1) derived from hash h
WC_HD float wc_f(wc_u64 h, int n)
{
    return (float)(wc_mix(h + (wc_u64)n * 0xD1B54A32D192ED03ULL) >> 40) * (1.0f / 16777216.0f);
}
// smooth lattice noise in [-1,1] along index t (period 'scale' indices), channel ch
WC_HD float wc_smooth(wc_u64 h, float t, int ch)
{
    int i = (int)floorf(t);
    float f = t - (float)i;
    f = f * f * (3.0f - 2.0f * f);
    float a = wc_f(h, ch * 64 + (i & 63)) * 2.0f - 1.0f;
    float b = wc_f(h, ch * 64 + ((i + 1) & 63)) * 2.0f - 1.0f;
    return a + (b - a) * f;
}

// ---------- cave worms ----------
struct WcWorm {
    wc_u64 h;
    float x0, y0, z0, dx, dy, dz, r;
    int roomK; float roomR;
};

WC_HD int wc_worm_count(const CarveParams& p, int cx, int cz)
{
    wc_u64 h = wc_h4(p.seed, cx, cz, 0x51, 7);
    float n = p.caveMean * (0.4f + 1.2f * wc_f(h, 0));
    int k = (int)n + (wc_f(h, 1) < n - (float)(int)n ? 1 : 0);
    return k > WC_MAX_WORMS ? WC_MAX_WORMS : k;
}

WC_HD WcWorm wc_worm(const CarveParams& p, int cx, int cz, int w)
{
    WcWorm o;
    o.h = wc_h4(p.seed, cx, cz, w, 0x77);
    o.x0 = (float)(cx * WC_CAVE_CELL) + wc_f(o.h, 0) * (float)WC_CAVE_CELL;
    o.z0 = (float)(cz * WC_CAVE_CELL) + wc_f(o.h, 1) * (float)WC_CAVE_CELL;
    float u = wc_f(o.h, 2), v = wc_f(o.h, 3);
    float span = (float)(p.maxY - p.minY);
    o.y0 = (float)p.minY + 6.0f + u * v * (span * 0.55f);          // biased low like vanilla
    float yaw = wc_f(o.h, 4) * 6.2831853f;
    float pitch = (wc_f(o.h, 5) - 0.5f) * 0.5f;
    float cp = cosf(pitch);
    o.dx = cosf(yaw) * cp; o.dz = sinf(yaw) * cp; o.dy = sinf(pitch);
    o.r = (1.4f + wc_f(o.h, 6) * 2.8f) * p.caveScale;
    o.roomK = -1; o.roomR = 0.0f;
    if (wc_f(o.h, 7) < p.roomChance) {
        o.roomK = 3 + (int)(wc_f(o.h, 8) * (float)(WC_WORM_SEGS - 5));
        o.roomR = (3.5f + wc_f(o.h, 9) * 4.5f) * p.caveScale;
    }
    return o;
}

WC_HD void wc_worm_pt(const CarveParams& p, const WcWorm& o, int k, float& x, float& y, float& z, float& r)
{
    float t = (float)k * WC_WORM_STEP;
    float kk = (float)k * (1.0f / 3.0f);
    x = o.x0 + o.dx * t + 6.0f * wc_smooth(o.h, kk, 1);
    z = o.z0 + o.dz * t + 6.0f * wc_smooth(o.h, kk, 2);
    y = o.y0 + o.dy * t + 3.5f * wc_smooth(o.h, kk, 3);
    float taper = sinf(3.1415927f * ((float)k + 0.5f) / (float)(WC_WORM_SEGS + 1));
    r = o.r * (0.55f + 0.45f * taper) * (0.8f + 0.2f * wc_smooth(o.h, kk, 4));
    if (y < (float)(p.minY + 4)) y = (float)(p.minY + 4);
}

// squared distance from (x,y,z) to capsule, y scaled by 1/0.75 (flattened tunnels)
WC_HD bool wc_cap_hit(float x0, float y0, float z0, float x1, float y1, float z1, float r, float x, float y, float z)
{
    const float ys = 1.0f / 0.75f;
    float ax = x1 - x0, ay = (y1 - y0) * ys, az = z1 - z0;
    float px = x - x0, py = (y - y0) * ys, pz = z - z0;
    float l2 = ax * ax + ay * ay + az * az;
    float t = l2 > 1e-6f ? (px * ax + py * ay + pz * az) / l2 : 0.0f;
    t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
    float qx = px - ax * t, qy = py - ay * t, qz = pz - az * t;
    return qx * qx + qy * qy + qz * qz < r * r;
}

WC_HD int carveGather(const CarveParams& p, int x0, int y0, int z0, int x1, int y1, int z1, WcCap* out, int maxOut)
{
    if (!(p.flags & 1)) return 0;
    int n = 0;
    int c0x = (int)floorf((float)(x0 - WC_CAVE_CELL) / WC_CAVE_CELL), c1x = (int)floorf((float)(x1 + WC_CAVE_CELL) / WC_CAVE_CELL);
    int c0z = (int)floorf((float)(z0 - WC_CAVE_CELL) / WC_CAVE_CELL), c1z = (int)floorf((float)(z1 + WC_CAVE_CELL) / WC_CAVE_CELL);
    // cells whose worms can reach the box (reach <= WC_CAVE_CELL)
    c0x = (int)floorf((float)x0 / WC_CAVE_CELL) - 1; c1x = (int)floorf((float)x1 / WC_CAVE_CELL) + 1;
    c0z = (int)floorf((float)z0 / WC_CAVE_CELL) - 1; c1z = (int)floorf((float)z1 / WC_CAVE_CELL) + 1;
    for (int cz = c0z; cz <= c1z; cz++)
        for (int cx = c0x; cx <= c1x; cx++) {
            int wn = wc_worm_count(p, cx, cz);
            for (int w = 0; w < wn; w++) {
                WcWorm o = wc_worm(p, cx, cz, w);
                float px, py, pz, pr;
                wc_worm_pt(p, o, 0, px, py, pz, pr);
                for (int k = 1; k <= WC_WORM_SEGS; k++) {
                    float qx, qy, qz, qr;
                    wc_worm_pt(p, o, k, qx, qy, qz, qr);
                    float r = fmaxf(pr, qr) + 1.0f;
                    if (qx + r >= (float)x0 && px + r >= (float)x0 || qx - r <= (float)(x1 + 1) || px - r <= (float)(x1 + 1)) {
                        bool inX = fmaxf(px, qx) + r >= (float)x0 && fminf(px, qx) - r <= (float)(x1 + 1);
                        bool inZ = fmaxf(pz, qz) + r >= (float)z0 && fminf(pz, qz) - r <= (float)(z1 + 1);
                        bool inY = fmaxf(py, qy) + r >= (float)y0 && fminf(py, qy) - r <= (float)(y1 + 1);
                        if (inX && inZ && inY && n < maxOut) {
                            WcCap c = {px, py, pz, qx, qy, qz, fmaxf(pr, qr)};
                            out[n++] = c;
                        }
                    }
                    px = qx; py = qy; pz = qz; pr = qr;
                }
                if (o.roomK >= 0) {
                    float rx, ry, rz, rr;
                    wc_worm_pt(p, o, o.roomK, rx, ry, rz, rr);
                    float r = o.roomR + 1.0f;
                    if (rx + r >= (float)x0 && rx - r <= (float)(x1 + 1) && rz + r >= (float)z0 && rz - r <= (float)(z1 + 1)
                        && ry + r >= (float)y0 && ry - r <= (float)(y1 + 1) && n < maxOut) {
                        WcCap c = {rx, ry, rz, rx, ry, rz, o.roomR};
                        out[n++] = c;
                    }
                }
            }
        }
    return n;
}

WC_HD bool carveCapsHit(const WcCap* caps, int n, int x, int y, int z)
{
    float fx = (float)x + 0.5f, fy = (float)y + 0.5f, fz = (float)z + 0.5f;
    for (int i = 0; i < n; i++) {
        const WcCap& c = caps[i];
        float r = c.r;
        if (fx < fminf(c.x0, c.x1) - r || fx > fmaxf(c.x0, c.x1) + r) continue;
        if (fz < fminf(c.z0, c.z1) - r || fz > fmaxf(c.z0, c.z1) + r) continue;
        if (wc_cap_hit(c.x0, c.y0, c.z0, c.x1, c.y1, c.z1, r, fx, fy, fz)) return true;
    }
    return false;
}

// ---------- canyons ----------
// path point k of the canyon of cell (cx,cz): centre (x,z), floor-centre y, half width, half height
struct WcCanyon { wc_u64 h; float x0, z0, dx, dz, yc, w, hh; int present; };

WC_HD WcCanyon wc_canyon(const CarveParams& p, int cx, int cz)
{
    WcCanyon o;
    o.h = wc_h4(p.seed, cx, cz, 0xCA, 0x11);
    o.present = (p.flags & 2) && wc_f(o.h, 0) < p.canyonChance;
    o.x0 = (float)(cx * WC_CANYON_CELL) + wc_f(o.h, 1) * (float)WC_CANYON_CELL;
    o.z0 = (float)(cz * WC_CANYON_CELL) + wc_f(o.h, 2) * (float)WC_CANYON_CELL;
    float yaw = wc_f(o.h, 3) * 6.2831853f;
    o.dx = cosf(yaw); o.dz = sinf(yaw);
    o.yc = (float)p.seaLevel - 20.0f + wc_f(o.h, 4) * 50.0f;     // vertical centre
    o.w = 2.5f + wc_f(o.h, 5) * 3.0f;
    o.hh = 14.0f + wc_f(o.h, 6) * 14.0f;
    return o;
}

WC_HD int carveFloor(const CarveParams& p, int x, int z, int groundY)
{
    if (!(p.flags & 2)) return WC_NO_CARVE;
    float fx = (float)x + 0.5f, fz = (float)z + 0.5f;
    int ccx = (int)floorf((float)x / WC_CANYON_CELL), ccz = (int)floorf((float)z / WC_CANYON_CELL);
    float best = 1e9f;
    for (int cz = ccz - 1; cz <= ccz + 1; cz++)
        for (int cx = ccx - 1; cx <= ccx + 1; cx++) {
            WcCanyon o = wc_canyon(p, cx, cz);
            if (!o.present) continue;
            float px = o.x0, pz = o.z0, py = o.yc;
            for (int k = 1; k <= WC_CANYON_SEGS; k++) {
                float kk = (float)k * 0.25f;
                float t = (float)k * 4.0f;
                float qx = o.x0 + o.dx * t + 9.0f * wc_smooth(o.h, kk, 1);
                float qz = o.z0 + o.dz * t + 9.0f * wc_smooth(o.h, kk, 2);
                float qy = o.yc + 8.0f * wc_smooth(o.h, kk, 3);
                float ax = qx - px, az = qz - pz;
                float l2 = ax * ax + az * az;
                float s = l2 > 1e-6f ? ((fx - px) * ax + (fz - pz) * az) / l2 : 0.0f;
                s = s < 0.0f ? 0.0f : (s > 1.0f ? 1.0f : s);
                float dxp = fx - (px + ax * s), dzp = fz - (pz + az * s);
                float d = sqrtf(dxp * dxp + dzp * dzp);
                float taper = sinf(3.1415927f * ((float)(k - 1) + s) / (float)WC_CANYON_SEGS);
                float w = o.w * (0.35f + 0.65f * taper) * (0.85f + 0.15f * wc_smooth(o.h, kk, 4));
                if (d < w) {
                    float yc = py + (qy - py) * s;
                    float depth = o.hh * (0.4f + 0.6f * taper) * sqrtf(1.0f - (d / w) * (d / w));
                    float fl = yc - depth;
                    if (fl < best) best = fl;
                }
                px = qx; pz = qz; py = qy;
            }
        }
    if (best > 1e8f) return WC_NO_CARVE;
    int f = (int)floorf(best);
    if (f < p.minY + 6) f = p.minY + 6;
    return f >= groundY ? WC_NO_CARVE : f;
}

WC_HD bool carvedAt(const CarveParams& p, int x, int y, int z, int groundY)
{
    if (y <= p.minY + 1) return false;
    int f = carveFloor(p, x, z, groundY);
    if (f != WC_NO_CARVE && y >= f) return true;
    if (y > groundY - 3 || !(p.flags & 1)) return false;
    WcCap caps[96];
    int n = carveGather(p, x, y, z, x, y, z, caps, 96);
    return carveCapsHit(caps, n, x, y, z);
}

// ---------- ores ----------
struct OreEntry {
    int minY, maxY, triangle;
    float lambda;    // blobs per 16^3 cell at peak density
    float radius;    // blob radius (blocks)
    float discard;   // per-voxel discard chance
    int stateStone, stateDeep, salt, pad;
};
struct OreParams {
    wc_u64 seed;
    int count, pad;
    OreEntry e[WC_MAX_ORE];
};

// count = placement attempts per chunk, rarityInv = 1/rarity (1 when no rarity filter), size = vein size
WC_HD OreEntry oreEntry(int minY, int maxY, int triangle, float count, float rarityInv, int size, float discard,
                        int stoneId, int deepId, int salt)
{
    OreEntry o;
    o.minY = minY; o.maxY = maxY; o.triangle = triangle;
    float vol = 256.0f * (float)(maxY - minY + 1);
    float peak = triangle ? 2.0f : 1.0f;
    o.lambda = count * rarityInv * peak * 4096.0f / vol * (float)(size < 1 ? 1 : size) / (float)(size < 1 ? 1 : size);
    o.radius = cbrtf(0.75f * (float)(size < 1 ? 1 : size) / 3.1415927f);
    if (o.radius < 0.9f) o.radius = 0.9f;
    o.discard = discard; o.stateStone = stoneId; o.stateDeep = deepId; o.salt = salt; o.pad = 0;
    return o;
}

// One blob of entry e in 16^3 cell (cx,cy,cz): centre, radii; false = rejected (height/triangle shape)
struct WcBlob { float x, y, z, rx, ry, rz; int entry; };

WC_HD wc_u64 wc_ore_cell_hash(const OreParams& op, int e, int cx, int cy, int cz)
{
    return wc_h4(op.seed ^ (wc_u64)op.e[e].salt * 0x9E3779B97F4A7C15ULL, cx, cy, cz, e + 1);
}
WC_HD int wc_ore_cell_count(const OreEntry& o, wc_u64 h)
{
    return (int)o.lambda + (wc_f(h, 0) < o.lambda - (float)(int)o.lambda ? 1 : 0);
}
WC_HD bool wc_ore_blob(const OreParams& op, int e, wc_u64 h, int j, int cx, int cy, int cz, WcBlob& b)
{
    const OreEntry& o = op.e[e];
    float bx = (float)(cx * 16) + wc_f(h, 4 + j * 8) * 16.0f;
    float by = (float)(cy * 16) + wc_f(h, 5 + j * 8) * 16.0f;
    float bz = (float)(cz * 16) + wc_f(h, 6 + j * 8) * 16.0f;
    if (by < (float)o.minY || by > (float)(o.maxY + 1)) return false;
    if (o.triangle) {
        float mid = 0.5f * (float)(o.minY + o.maxY + 1), half = 0.5f * (float)(o.maxY + 1 - o.minY);
        float s = 1.0f - fabsf(by - mid) / half;
        if (wc_f(h, 7 + j * 8) > s) return false;
    }
    float ex = 0.8f + 0.5f * wc_f(h, 8 + j * 8), ey = 0.8f + 0.5f * wc_f(h, 9 + j * 8);
    float ez = 1.0f / (ex * ey);
    b.x = bx; b.y = by; b.z = bz; b.rx = o.radius * ex; b.ry = o.radius * ey; b.rz = o.radius * ez; b.entry = e;
    return true;
}
// does the blob cover voxel (x,y,z)? (ragged edge and discard chance included)
WC_HD bool wc_ore_voxel(const OreParams& op, const WcBlob& b, int x, int y, int z)
{
    float qx = ((float)x + 0.5f - b.x) / b.rx, qy = ((float)y + 0.5f - b.y) / b.ry, qz = ((float)z + 0.5f - b.z) / b.rz;
    float d2 = qx * qx + qy * qy + qz * qz;
    if (d2 > 1.0f) return false;
    const OreEntry& o = op.e[b.entry];
    wc_u64 hv = wc_h4(op.seed, x, y, z, o.salt);
    if (d2 > 0.6f && wc_f(hv, 0) < 0.45f) return false;
    if (o.discard > 0.0f && wc_f(hv, 1) < o.discard) return false;
    return true;
}

WC_HD int oreAt(const OreParams& op, int x, int y, int z, int deep)
{
    int ccx = x >> 4, ccy = y >> 4, ccz = z >> 4;
    int lx = x & 15, ly = y & 15, lz = z & 15;
    for (int e = 0; e < op.count; e++) {
        const OreEntry& o = op.e[e];
        float rm = o.radius * 1.3f + 1.0f;
        if (y < o.minY - (int)rm || y > o.maxY + (int)rm) continue;
        int ox0 = (float)lx < rm ? -1 : 0, ox1 = (float)lx > 16.0f - rm ? 1 : 0;
        int oy0 = (float)ly < rm ? -1 : 0, oy1 = (float)ly > 16.0f - rm ? 1 : 0;
        int oz0 = (float)lz < rm ? -1 : 0, oz1 = (float)lz > 16.0f - rm ? 1 : 0;
        for (int dz = oz0; dz <= oz1; dz++)
            for (int dy = oy0; dy <= oy1; dy++)
                for (int dx = ox0; dx <= ox1; dx++) {
                    int cx = ccx + dx, cy = ccy + dy, cz = ccz + dz;
                    wc_u64 h = wc_ore_cell_hash(op, e, cx, cy, cz);
                    int n = wc_ore_cell_count(o, h);
                    for (int j = 0; j < n; j++) {
                        WcBlob b;
                        if (wc_ore_blob(op, e, h, j, cx, cy, cz, b) && wc_ore_voxel(op, b, x, y, z))
                            return deep ? o.stateDeep : o.stateStone;
                    }
                }
    }
    return -1;
}
