// Aus shaders/glsl/wg_exact_aquifer.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"
#include "wg_exact_random.cuh"
// Parameter (ExactAquifer.encode): 0-3 Seeds, 4 Meeresspiegel, 5 minY, 6 Hoehe, 7-12 Code-Starts
// (barrier, floodedness, spread, lava, exclusion, surfaceLevel), 13 WAY_BELOW_MIN_Y,
// 14 Wurzel-Offset der Dichte im Scratch (floats), 15 Zustandsworte je Chunk.

#define gAq ((int*)vf_b[10])

#define gAqState ((int*)vf_b[11])

#define gAqOut ((uint*)vf_b[12])
// Vorlaeufige Oberflaeche (surfaceLevel) dedupliziert ueber den Stapel: je Chunk 7 x 5 Indizes
// (Chunk-Versatz x -4..2, z -2..2) auf eindeutige Chunk-Bloecke, je Block 4 x 4 Quart-Werte.

#define gSurfIdx ((int*)vf_b[13])

#define gSurfVal ((int*)vf_b[14])

#define gSurfPos ((ivec2*)vf_b[15])


// ---------- Fluessigkeitsstatus ----------
struct Fluid { int level; int type; };

__device__ const ivec2 SURF_OFFSETS[13] = {ivec2(0, 0), ivec2(-2, -1), ivec2(-1, -1), ivec2(0, -1), ivec2(1, -1),
    ivec2(-3, 0), ivec2(-2, 0), ivec2(-1, 0), ivec2(1, 0), ivec2(-2, 1), ivec2(-1, 1), ivec2(0, 1), ivec2(1, 1)};
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_aquifer.glsl – Aquifer.NoiseBasedAquifer (Minecraft 26.3) bitgenau, drei Durchgaenge
// je Stapel (gP.task): 0 Oberflaechentabelle (Quart-Raster), 1 Aquifer-Zellen + Obergrenze,
// 2 computeSubstance je Block (Dichte aus dem Chunk-Scratch). Ergebnis je Block:
// 0 fest (Standardblock), 1 Luft, 2 Wasser, 3 Lava; Bit 7 = shouldScheduleFluidUpdate.
// double nur, wo Vanilla double rechnet (Flutung, Druck/Barriere) – mit korrekt gerundeter Division.

#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"
#include "wg_exact_random.cuh"

    static constexpr int VF_BLOCK = 64;

// Block-Minimum x/z


    static constexpr int SUBST_SOLID = 0, SUBST_AIR = 1, SUBST_WATER = 2, SUBST_LAVA = 3;

// Zustand je Chunk: [0] skipSamplingAboveY, danach je Zelle 5 Worte (ExactAquifer.stateWords)

    static constexpr int SURF_WORDS = 0;


__device__ Xo aquiferRandom(int x, int y, int z)
{
    return xoAt(seed64(gAq[0], gAq[1]), seed64(gAq[2], gAq[3]), x, y, z);
}


__device__ int fluidAt(Fluid f, int y) { return y < f.level ? f.type : SUBST_AIR; }


__device__ Fluid globalFluid(int y)
{
    int sea = gAq[4];
    Fluid f;
    if (y < gl_min(-54, sea)) { f.level = -54; f.type = SUBST_LAVA; }
    else { f.level = sea; f.type = SUBST_WATER; }
    return f;
}


__device__ int gridY(int y) { return jFloorDiv(y, 12); }

// Aquifer-Raster je Chunk (Vanilla-Konstruktor mit Chunk-Volumen)

__device__ ivec3 gridMin(ivec2 cm)
{
    return ivec3((cm.x - 5) >> 4, gridY(gAq[5] + 1) - 1, (cm.y - 5) >> 4);
}


__device__ ivec3 gridSize(ivec2 cm)
{
    ivec3 mn = gridMin(cm);
    int maxX = ((cm.x + 15 - 5) >> 4) + 1;
    int maxY = gridY(gAq[5] + gAq[6] - 1 + 1) + 1;
    int maxZ = ((cm.y + 15 - 5) >> 4) + 1;
    return ivec3(maxX - mn.x + 1, maxY - mn.y + 1, maxZ - mn.z + 1);
}


__device__ int stateBase(int chunk) { return chunk * gAq[15]; }


__device__ int surfaceAt(int chunk, ivec2 cm, int bx, int bz)
{
    int qx = (bx >> 2) << 2, qz = (bz >> 2) << 2;       // QuartPos quantisiert
    int cx = (qx >> 4) - (cm.x >> 4) + 4, cz = (qz >> 4) - (cm.y >> 4) + 2;
    int u = gSurfIdx[chunk * 35 + cz * 7 + cx];
    return gSurfVal[u * 16 + ((qx >> 2) & 3) + ((qz >> 2) & 3) * 4];
}


__device__ float evalFn(int which, ivec3 p) { return runPoint(gAq[7 + which], p); }


__device__ Fluid computeFluid(int chunk, ivec2 cm, int x, int y, int z)
{
    Fluid global = globalFluid(y);
    int lowest = 2147483647;
    int top = y + 12, bottom = y - 12;
    bool centerUnder = false;
    for (int i = 0; i < 13; i++)
    {
        int sx = x + SURF_OFFSETS[i].x * 16, sz = z + SURF_OFFSETS[i].y * 16;
        int surf = surfaceAt(chunk, cm, sx, sz);
        int adj = surf + 8;
        bool start = i == 0;
        if (start && bottom > adj) return global;
        bool poke = top > adj;
        if (poke || start)
        {
            Fluid atSurf = globalFluid(adj);
            if (fluidAt(atSurf, adj) != SUBST_AIR)
            {
                if (start) centerUnder = true;
                if (poke) return atSurf;
            }
        }
        lowest = gl_min(lowest, surf);
    }
    // computeSurfaceLevel
    double partially, fully;
    if (evalFn(4, ivec3(x, y, z)) > 0.0f)
    {
        partially = -1.0;
        fully = -1.0;
    }
    else
    {
        int dist = (lowest + 8) - y;
        double factor = 0.0;
        if (centerUnder)
        {
            double f = double(dist) / 64.0;       // inverseLerp(v, 0, 64): exakt
            factor = f < 0.0 ? 1.0 : (f > 1.0 ? 0.0 : 1.0 + f * (0.0 - 1.0));
        }
        double noise = double(evalFn(1, ivec3(x, y, z)));
        noise = noise < -1.0 ? -1.0 : gl_min(noise, 1.0);
        double t = (factor - 1.0) / (0.0 - 1.0); // exakt (/ -1)
        double fullT = -0.3 + t * (0.8 - -0.3);
        double partT = -0.8 + t * (0.4 - -0.8);
        partially = noise - partT;
        fully = noise - fullT;
    }
    int level;
    if (fully > 0.0) level = global.level;
    else if (partially > 0.0)
    {
        int cx = jFloorDiv(x, 16), cy = jFloorDiv(y, 40), cz = jFloorDiv(z, 16);
        int middle = cy * 40 + 20;
        float sp = evalFn(2, ivec3(cx, cy, cz)) * 10.0f;
        double q = dDiv(double(sp), 3.0);
        int quant = int(gl_floor(q)) * 3;                      // Mth.quantize
        level = gl_min(lowest, middle + quant);
    }
    else level = gAq[13];
    Fluid res;
    res.level = level;
    res.type = global.type;
    if (level <= -10 && level != gAq[13] && global.type != SUBST_LAVA)
    {
        double lv = double(evalFn(3, ivec3(jFloorDiv(x, 64), jFloorDiv(y, 40), jFloorDiv(z, 64))));
        if (gl_abs(lv) > 0.3) res.type = SUBST_LAVA;
    }
    return res;
}

// ---------- Druck zwischen zwei Aquiferen (calculatePressure) ----------

__device__ double pressure(int x, int y, int z, double& barrier, Fluid s1, Fluid s2)
{
    int t1 = fluidAt(s1, y), t2 = fluidAt(s2, y);
    if ((t1 != SUBST_LAVA || t2 != SUBST_WATER) && (t1 != SUBST_WATER || t2 != SUBST_LAVA))
    {
        int diff = gl_abs(s1.level - s2.level);
        if (diff == 0) return 0.0;
        double avg = 0.5 * double(s1.level + s2.level);
        double above = double(y) + 0.5 - avg;
        double base = double(diff) / 2.0;
        double toMiddle = base - gl_abs(above);
        double gradient;
        if (above > 0.0)
        {
            double center = 0.0 + toMiddle;
            gradient = center > 0.0 ? dDiv(center, 1.5) : dDiv(center, 2.5);
        }
        else
        {
            double center = 3.0 + toMiddle;
            gradient = center > 0.0 ? dDiv(center, 3.0) : dDiv(center, 10.0);
        }
        double nv;
        if (!(gradient < -2.0) && !(gradient > 2.0))
        {
            if (gl_isnan(barrier)) barrier = double(evalFn(0, ivec3(x, y, z)));
            nv = barrier;
        }
        else nv = 0.0;
        double s = nv + gradient;
        double r = 2.0 * s;
        return r;
    }
    return 2.0;
}


__device__ double similarity(int d1, int d2)
{
    double q = dDiv(double(d2 - d1), 25.0);
    double r = 1.0 - q;
    return r;
}


__device__ Fluid cellStatus(int chunk, int index)
{
    int b = stateBase(chunk) + SURF_WORDS + 1 + index * 5;
    Fluid f;
    f.level = gAqState[b + 3];
    f.type = gAqState[b + 4];
    return f;
}


__device__ ivec3 cellLoc(int chunk, int index)
{
    int b = stateBase(chunk) + SURF_WORDS + 1 + index * 5;
    return ivec3(gAqState[b], gAqState[b + 1], gAqState[b + 2]);
}


__device__ void main_()
{
    int pass = gP.task;
    int chunk = int(vf_gid.y);
    int t = int(vf_gid.x);
    if (pass == 0)
    {
        // je eindeutigem Block (gP.pad1 Stueck) 16 Quarts
        int u = t >> 4, q = t & 15;
        if (u >= gP.pad1) return;
        ivec2 bm = gSurfPos[u];
        float v = evalFn(5, ivec3(bm.x + (q & 3) * 4, 0, bm.y + (q >> 2) * 4));
        gSurfVal[t] = jFloorF(v);                                // Mth.floor(sampleValue)
        return;
    }
    if (chunk >= gP.chunkCount) return;
    ivec2 cm = gChunk[chunk];
    ivec3 gmin = gridMin(cm);
    ivec3 gsz = gridSize(cm);
    int cells = gsz.x * gsz.y * gsz.z;
    if (pass == 1)
    {
        if (t > cells) return;
        if (t == cells)
        {
            // skipSamplingAboveY: maxSurfaceLevel ueber [fromGridX(minGridX,0), fromGridX(maxGridX,9)]
            int x0 = gmin.x << 4, x1 = ((gmin.x + gsz.x - 1) << 4) + 9;
            int z0 = gmin.z << 4, z1 = ((gmin.z + gsz.z - 1) << 4) + 9;
            int mx = -2147483648;
            for (int qz = (z0 >> 2); qz <= (z1 >> 2); qz++)
                for (int qx = (x0 >> 2); qx <= (x1 >> 2); qx++)
                    mx = gl_max(mx, surfaceAt(chunk, cm, qx << 2, qz << 2));
            int maxAdj = mx + 8;
            int skipGridY = gridY(maxAdj + 12) - -1;
            gAqState[stateBase(chunk) + SURF_WORDS] = skipGridY * 12 + 11 - 1;
            return;
        }
        int gx = gmin.x + t % gsz.x;
        int gz = gmin.z + (t / gsz.x) % gsz.z;
        int gy = gmin.y + t / (gsz.x * gsz.z);
        Xo r = aquiferRandom(gx, gy, gz);
        int lx = (gx << 4) + xoNextInt(r, 10);
        int ly = gy * 12 + xoNextInt(r, 9);
        int lz = (gz << 4) + xoNextInt(r, 10);
        Fluid f = computeFluid(chunk, cm, lx, ly, lz);
        int b = stateBase(chunk) + SURF_WORDS + 1 + t * 5;
        gAqState[b] = lx;
        gAqState[b + 1] = ly;
        gAqState[b + 2] = lz;
        gAqState[b + 3] = f.level;
        gAqState[b + 4] = f.type;
        return;
    }
    // pass 2: je Block
    int height = gAq[6];
    if (t >= 256 * height) return;
    int yIdx = t % height, xz = t / height;
    int lxz = xz % 16, lzz = xz / 16;
    int x = cm.x + lxz, y = gAq[5] + yIdx, z = cm.y + lzz;
    float density = gScratch[chunk * gP.scratchFloats + gAq[14] + yIdx + (lxz + lzz * 16) * height];
    uint outIdx = uint(chunk * 256 * height + t);
    if (density > 0.0f) { gAqOut[outIdx] = uint(SUBST_SOLID); return; }
    Fluid global = globalFluid(y);
    int skipAbove = gAqState[stateBase(chunk) + SURF_WORDS];
    if (y > skipAbove) { gAqOut[outIdx] = uint(fluidAt(global, y)); return; }
    if (fluidAt(global, y) == SUBST_LAVA) { gAqOut[outIdx] = uint(SUBST_LAVA); return; }
    int xA = (x - 5) >> 4, yA = gridY(y + 1), zA = (z - 5) >> 4;
    int d1 = 2147483647, d2 = 2147483647, d3 = 2147483647, d4 = 2147483647;
    int c1 = 0, c2 = 0, c3 = 0, c4 = 0;
    for (int x1 = 0; x1 <= 1; x1++)
        for (int y1 = -1; y1 <= 1; y1++)
            for (int z1 = 0; z1 <= 1; z1++)
            {
                int gx = xA + x1, gy = yA + y1, gz = zA + z1;
                int index = ((gy - gmin.y) * gsz.z + (gz - gmin.z)) * gsz.x + (gx - gmin.x);
                ivec3 loc = cellLoc(chunk, index);
                int dx = loc.x - x, dy = loc.y - y, dz = loc.z - z;
                int nd = dx * dx + dy * dy + dz * dz;
                if (d1 >= nd) { c4 = c3; c3 = c2; c2 = c1; c1 = index; d4 = d3; d3 = d2; d2 = d1; d1 = nd; }
                else if (d2 >= nd) { c4 = c3; c3 = c2; c2 = index; d4 = d3; d3 = d2; d2 = nd; }
                else if (d3 >= nd) { c4 = c3; c3 = index; d4 = d3; d3 = nd; }
                else if (d4 >= nd) { c4 = index; d4 = nd; }
            }
    Fluid s1 = cellStatus(chunk, c1);
    double sim12 = similarity(d1, d2);
    int fluidState = fluidAt(s1, y);
    double FLOWING = similarity(100, 144); // FLOWING_UPDATE_SIMULARITY, gleich gerechnet wie Vanilla
    if (sim12 <= 0.0)
    {
        bool sched = false;
        if (sim12 >= FLOWING)
        {
            Fluid s2 = cellStatus(chunk, c2);
            sched = !(s1.level == s2.level && s1.type == s2.type);
        }
        gAqOut[outIdx] = uint(fluidState) | (sched ? 128u : 0u);
        return;
    }
    if (fluidState == SUBST_WATER && fluidAt(globalFluid(y - 1), y - 1) == SUBST_LAVA)
    {
        gAqOut[outIdx] = uint(fluidState) | 128u;
        return;
    }
    double barrier = double(uintBitsToFloat(0x7fc00000u));
    Fluid s2 = cellStatus(chunk, c2);
    double b12 = sim12 * pressure(x, y, z, barrier, s1, s2);
    if (double(density) + b12 > 0.0) { gAqOut[outIdx] = uint(SUBST_SOLID); return; }
    Fluid s3 = cellStatus(chunk, c3);
    double sim13 = similarity(d1, d3);
    if (sim13 > 0.0)
    {
        double s1213 = sim12 * sim13;
        double b13 = s1213 * pressure(x, y, z, barrier, s1, s3);
        if (double(density) + b13 > 0.0) { gAqOut[outIdx] = uint(SUBST_SOLID); return; }
    }
    double sim23 = similarity(d2, d3);
    if (sim23 > 0.0)
    {
        double s1223 = sim12 * sim23;
        double b23 = s1223 * pressure(x, y, z, barrier, s2, s3);
        if (double(density) + b23 > 0.0) { gAqOut[outIdx] = uint(SUBST_SOLID); return; }
    }
    bool eq12 = s1.level == s2.level && s1.type == s2.type;
    bool eq23 = s2.level == s3.level && s2.type == s3.type;
    bool eq13 = s1.level == s3.level && s1.type == s3.type;
    bool flow12 = !eq12;
    bool flow23 = sim23 >= FLOWING && !eq23;
    bool flow13 = sim13 >= FLOWING && !eq13;
    bool sched;
    if (!flow12 && !flow23 && !flow13)
    {
        Fluid s4 = cellStatus(chunk, c4);
        bool eq14 = s1.level == s4.level && s1.type == s4.type;
        sched = sim13 >= FLOWING && similarity(d1, d4) >= FLOWING && !eq14;
    }
    else sched = true;
    gAqOut[outIdx] = uint(fluidState) | (sched ? 128u : 0u);
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_aquifer(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
