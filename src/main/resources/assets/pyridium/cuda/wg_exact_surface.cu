// Aus shaders/glsl/wg_exact_surface.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"
#include "wg_exact_random.cuh"

#define gSf ((int*)vf_b[10])

#define gSubst ((uint*)vf_b[11])
// Aquifer: 0 fest, 1 Luft, 2 Wasser, 3 Lava

#define gWin ((int*)vf_b[12])
// je Chunk 6 x qh x 6 Quarts (x/z -1..4)
// Zustaende y-major (je Chunk yi * 256 + z + x * 16): Nachbar-Threads (z) lesen benachbarte Worte

#define gState ((uint*)vf_b[13])
// je Chunk 256 * H (Pass 1)

#define gStateTmp ((uint*)vf_b[14])
// Kopie fuer Pass 0

#define gCol ((int*)vf_b[15])
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_surface.glsl – MaterialSystem.buildSurface (Minecraft 26.3) bitgenau: Materialregeln
// (Oberflaeche, Untergrund, Erzadern, Grundgestein), Tonbaender, Badlands-Saeulen und
// Eisberge. Thread je Spalte (x, z), Blockzustaende als globale BlockState-IDs.
// gP.task: 0 = Vorhersage auf Kopie (Gradient 0) -> Endhoehen; 1 = echter Durchlauf mit den
// Gradienten, die Vanilla in x-major-Reihenfolge sieht (fruehere Nachbarn: Endhoehe, spaetere:
// Hoehe nach doFill); 2 = Initialisierung der Zustaende aus der Aquifer-Substanz;
// 3 = Post-Processing-Marken in die Ausgabe: Bit 31 doFill (Aquifer-Fluss-Update), Bit 30
// MaterialSystem (Regel setzte einen Zustand mit Fluessigkeit).

#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"
#include "wg_exact_random.cuh"

    static constexpr int VF_BLOCK = 64;

// je Spalte: Top nach doFill, nach Badlands, End-Top, Pruef-Top

// Parameter (ExactSurface.encode)

    static constexpr int SF_DEFAULT = 0, SF_WATER = 1, SF_LAVA = 2, SF_AIR = 3, SF_SEA = 4, SF_MINY = 5, SF_HEIGHT = 6, SF_MAXY = 7,
    SF_RULES = 8, SF_CONDS = 9, SF_ORES = 10, SF_EXTRA = 11, SF_BIOMES = 12, SF_BANDS = 13, SF_NOISE = 14,
    SF_ROOTSEED = 23, SF_PRELIM = 27, SF_ZOOM = 28, SF_PACKED_ICE = 30, SF_SNOW = 31, SF_TEMP_LAYER = 32,
    SF_INFO_LAYER = 33, SF_FROZEN_LAYERS = 34, SF_FROZEN_AMPS = 37, SF_FROZEN_FREQS = 40, SF_F2 = 46, SF_G2 = 48,
    SF_QH = 50, SF_WAYBELOW = 51, SF_RULES_SOLID = 52, SF_FLUIDS = 53, SF_NFLUIDS = 54;


    static constexpr int R_IF = 1, R_BLOCK = 2, R_BANDS = 3, R_ORE = 4, R_END = 5;


    static constexpr int C_NOT = 1, C_Y = 2, C_WATER = 3, C_STONE = 4, C_BIOME = 5, C_NOISE = 6, C_VGRAD = 7,
    C_STEEP = 8, C_HOLE = 9, C_ABOVE = 10, C_TEMP = 11;


    static constexpr int COND_WORDS = 8, ORE_WORDS = 12, BIOME_WORDS = 4;


    static constexpr int JINT_MIN = -2147483648;


__device__ int H() { return gSf[SF_HEIGHT]; }


__device__ int MINY() { return gSf[SF_MINY]; }

// ---------- Zustaende ----------

bool usingTmp;


int gChunkIdx;


__device__ uint stateAt(int lx, int y, int lz)
{
    int yi = y - MINY();
    if (yi < 0 || yi >= H()) return uint(gSf[SF_AIR]);     // ausserhalb: VOID_AIR (isAir)
    int i = gChunkIdx * 256 * H() + yi * 256 + lz + lx * 16;
    return usingTmp ? gStateTmp[i] : gState[i];
}


__device__ void setState(int lx, int y, int lz, uint s)
{
    int yi = y - MINY();
    if (yi < 0 || yi >= H()) return;
    int i = gChunkIdx * 256 * H() + yi * 256 + lz + lx * 16;
    if (usingTmp) gStateTmp[i] = s; else gState[i] = s;
}


__device__ bool isAirS(uint s) { return int(s) == gSf[SF_AIR]; }


__device__ bool isFluidS(uint s) { return int(s) == gSf[SF_WATER] || int(s) == gSf[SF_LAVA]; }


__device__ bool isStoneS(uint s) { return !isAirS(s) && !isFluidS(s); }

// ---------- Rauschen (Punktpfad, NoiseStack.get) ----------

__device__ float noise3(int which, double x, double y, double z) { return stackPoint(gSf[SF_NOISE + which], x, y, z); }

// ---------- SimplexNoise 2D (double wie Vanilla) ----------

__device__ double sfD(int at) { return loadD(uint(gSf[at]), uint(gSf[at + 1])); }


__device__ double simplexCorner(int gi, double x, double y)
{
    double t0 = 0.5 - x * x - y * y - 0.0 * 0.0;
    if (t0 < 0.0) return 0.0;
    t0 = t0 * t0;
    ivec3 g = GRAD[gi];
    double dot = double(g.x) * x + double(g.y) * y + double(g.z) * 0.0;
    double r = t0 * t0 * dot;
    return r;
}


__device__ float simplex2(int layer, double xinRaw, double yinRaw)
{
    double F2 = sfD(SF_F2), G2 = sfD(SF_G2);
    double xin = xinRaw + layerOff(layer, 0);
    double yin = yinRaw + layerOff(layer, 1);
    double s = (xin + yin) * F2;
    int i = int(gl_floor(xin + s));
    int j = int(gl_floor(yin + s));
    double t = double(i + j) * G2;
    double X0 = double(i) - t;
    double Y0 = double(j) - t;
    double x0 = xin - X0;
    double y0 = yin - Y0;
    int i1 = x0 > y0 ? 1 : 0, j1 = x0 > y0 ? 0 : 1;
    double x1 = x0 - double(i1) + G2;
    double y1 = y0 - double(j1) + G2;
    double g2x2 = 2.0 * G2;
    double x2 = x0 - 1.0 + g2x2;
    double y2 = y0 - 1.0 + g2x2;
    int ii = i & 255, jj = j & 255;
    int gi0 = permute(layer, ii + permute(layer, jj)) % 12;
    int gi1 = permute(layer, ii + i1 + permute(layer, jj + j1)) % 12;
    int gi2 = permute(layer, ii + 1 + permute(layer, jj + 1)) % 12;
    double n = simplexCorner(gi0, x0, y0) + simplexCorner(gi1, x1, y1) + simplexCorner(gi2, x2, y2);
    double v = 70.0 * n;
    return float(v);
}

// ---------- Biome ----------

__device__ jlong lcgNext(jlong rval, jlong x)
{
    return jadd64(jmul64(rval, jadd64(jmul64(rval, 6364136223846793005LL), 1442695040888963407LL)), x);
}


__device__ double fiddle(jlong rval)
{
    int k = int((rval >> 24) & 1023LL);
    double u = double(k) / 1024.0;
    double r = (u - 0.5) * 0.9;
    return r;
}


__device__ int biomeAtQuart(int qx, int qy, int qz)
{
    ivec2 cm = gChunk[gChunkIdx];
    int qh = gSf[SF_QH];
    int minQ = MINY() >> 2;
    int cy = gl_clamp(qy, minQ, minQ + qh - 1) - minQ;         // ChunkAccess.getNoiseBiome klemmt y
    int ix = qx - ((cm.x >> 2) - 1), iz = qz - ((cm.y >> 2) - 1);
    return gWin[gChunkIdx * 36 * qh + cy + (ix + iz * 6) * qh];
}

// BiomeManager.getBiome(x, y, z) (Zoom mit Seed-Hash)

__device__ int biomeAt(int x, int y, int z)
{
    jlong seed = jlong(seed64(gSf[SF_ZOOM], gSf[SF_ZOOM + 1]));
    int absX = x - 2, absY = y - 2, absZ = z - 2;
    int px = absX >> 2, py = absY >> 2, pz = absZ >> 2;
    double fx = double(absX & 3) / 4.0, fy = double(absY & 3) / 4.0, fz = double(absZ & 3) / 4.0;
    int minI = 0;
    double minD = uintBitsToFloat(0x7f800000u);
    for (int i = 0; i < 8; i++)
    {
        bool xe = (i & 4) == 0, ye = (i & 2) == 0, ze = (i & 1) == 0;
        int cx = xe ? px : px + 1, cy = ye ? py : py + 1, cz = ze ? pz : pz + 1;
        double dx = xe ? fx : fx - 1.0, dy = ye ? fy : fy - 1.0, dz = ze ? fz : fz - 1.0;
        jlong r = seed;
        r = lcgNext(r, jlong(cx));
        r = lcgNext(r, jlong(cy));
        r = lcgNext(r, jlong(cz));
        r = lcgNext(r, jlong(cx));
        r = lcgNext(r, jlong(cy));
        r = lcgNext(r, jlong(cz));
        double fX = fiddle(r);
        r = lcgNext(r, seed);
        double fY = fiddle(r);
        r = lcgNext(r, seed);
        double fZ = fiddle(r);
        double a = dz + fZ, b = dy + fY, c = dx + fX;
        double d = a * a + b * b + c * c;
        if (minD > d) { minI = i; minD = d; }
    }
    int bx = (minI & 4) == 0 ? px : px + 1;
    int by = (minI & 2) == 0 ? py : py + 1;
    int bz = (minI & 1) == 0 ? pz : pz + 1;
    return biomeAtQuart(bx, by, bz);
}


__device__ float biomeBase(int b) { return intBitsToFloat(gSf[gSf[SF_BIOMES] + b * BIOME_WORDS]); }


__device__ int biomeFlags(int b) { return gSf[gSf[SF_BIOMES] + b * BIOME_WORDS + 1]; }

// Biome.getTemperature (Hoehenanpassung, Modifikator FROZEN)

__device__ float biomeTemperature(int b, int x, int y, int z)
{
    float t = biomeBase(b);
    if ((biomeFlags(b) & 1) != 0)
    {
        // FROZEN_TEMPERATURE_NOISE.get(x * 0.05, z * 0.05) * 7.0F (NoiseStack aus Simplex-Lagen)
        float large = 0.0f;
        for (int l = 0; l < 3; l++)
        {
            double f = sfD(SF_FROZEN_FREQS + l * 2);
            double ax = double(x) * 0.05, az = double(z) * 0.05;
            double lx = ax * f, lz = az * f;
            float m = intBitsToFloat(gSf[SF_FROZEN_AMPS + l]) * simplex2(gSf[SF_FROZEN_LAYERS + l], lx, lz);
            large = large + m;
        }
        float large7 = large * 7.0f;
        double edge = double(simplex2(gSf[SF_INFO_LAYER], double(x) * 0.2, double(z) * 0.2));
        double ice = double(large7) + edge;
        if (ice < 0.3)
        {
            double small = double(simplex2(gSf[SF_INFO_LAYER], double(x) * 0.09, double(z) * 0.09));
            if (small < 0.8) t = 0.2f;
        }
    }
    int snowLevel = gSf[SF_SEA] + 17;
    if (y > snowLevel)
    {
        float qx = float(x) / 8.0f, qz = float(z) / 8.0f;
        float v = simplex2(gSf[SF_TEMP_LAYER], double(qx), double(qz)) * 8.0f;
        float a = v + float(y);
        float b2 = a - float(snowLevel);
        float c = b2 * 0.05f;
        float d = jDiv(c, 40.0f);
        float r = t - d;
        return r;
    }
    return t;
}

// ---------- Kontext je Spalte / Block ----------

int cX, cZ, cLX, cLZ, cGradX, cGradZ, cSurfaceDepth, cMinSurface;


bool cSecondaryValid;


double cSecondary;


int cY, cStoneAbove, cStoneBelow, cWaterHeight, cBiome;


__device__ int surfaceDepthAt(int x, int z)
{
    double nv = double(noise3(0, double(x), 0.0, double(z)));
    Xo r = xoAt(seed64(gSf[SF_ROOTSEED], gSf[SF_ROOTSEED + 1]), seed64(gSf[SF_ROOTSEED + 2], gSf[SF_ROOTSEED + 3]), x, 0, z);
    double rd = xoNextDouble(r);
    double v = nv * 2.75 + 3.0 + rd * 0.25;
    return int(v);                                    // (int) double: abschneiden (kleine Werte)
}


__device__ double secondary()
{
    if (!cSecondaryValid)
    {
        cSecondary = double(noise3(1, double(cX), 0.0, double(cZ)));
        cSecondaryValid = true;
    }
    return cSecondary;
}


__device__ int biomeHere()
{
    if (cBiome < 0) cBiome = biomeAt(cX, cY, cZ);
    return cBiome;
}

// Java Math.round(float): naechste ganze Zahl, Haelfte nach oben

__device__ int jRound(float a)
{
    if (gl_isnan(a)) return 0;
    float f = gl_floor(a);
    float d = a - f;
    int r = int(f);
    if (d >= 0.5f) r++;
    return r;
}


__device__ bool evalCond(int c)
{
    bool neg = false;
    for (int guard = 0; guard < 16; guard++)
    {
        int b = gSf[SF_CONDS] + c * COND_WORDS;
        int type = gSf[b];
        if (type == C_NOT) { neg = !neg; c = gSf[b + 1]; continue; }
        bool res = false;
        switch (type)
        {
        case C_Y:
            res = cY + (gSf[b + 3] != 0 ? cStoneAbove : 0) >= gSf[b + 1] + cSurfaceDepth * gSf[b + 2];
            break;
        case C_WATER:
            res = cWaterHeight == JINT_MIN
                || cY + (gSf[b + 3] != 0 ? cStoneAbove : 0) >= cWaterHeight + gSf[b + 1] + cSurfaceDepth * gSf[b + 2];
            break;
        case C_STONE:
        {
            int stoneDepth = gSf[b + 4] != 0 ? cStoneBelow : cStoneAbove;
            int sd = gSf[b + 2] != 0 ? cSurfaceDepth : 0;
            int range = gSf[b + 3];
            int sec = 0;
            if (range != 0)
            {
                // (int) Mth.map(secondary, -1, 1, 0, range) = (int)(0 + ((s + 1) / 2) * (range - 0))
                double t = (secondary() - -1.0) / (1.0 - -1.0);
                double m = 0.0 + t * (double(range) - 0.0);
                sec = int(m);
            }
            res = stoneDepth <= 1 + gSf[b + 1] + sd + sec;
            break;
        }
        case C_BIOME:
        {
            int bi = biomeHere();
            res = bi >= 0 && bi < gSf[b + 2] * 32 && (gSf[gSf[SF_EXTRA] + gSf[b + 1] + (bi >> 5)] & (1 << (bi & 31))) != 0;
            break;
        }
        case C_NOISE:
        {
            int so = gSf[b + 1];
            double v = double(stackPoint(so, double(cX), gSf[b + 2] != 0 ? double(cY) : 0.0, double(cZ)));
            double mn = loadD(uint(gSf[b + 3]), uint(gSf[b + 4])), mx = loadD(uint(gSf[b + 5]), uint(gSf[b + 6]));
            res = v >= mn && v <= mx;
            break;
        }
        case C_VGRAD:
        {
            int t = gSf[b + 1], f = gSf[b + 2];
            if (cY <= t) res = true;
            else if (cY >= f) res = false;
            else
            {
                // Mth.map(y, t, f, 1, 0) = 1 + ((y - t) / (f - t)) * (0 - 1)
                double p = dDiv(double(cY) - double(t), double(f) - double(t));
                double prob = 1.0 + p * (0.0 - 1.0);
                Xo r = xoAt(seed64(gSf[b + 3], gSf[b + 4]), seed64(gSf[b + 5], gSf[b + 6]), cX, cY, cZ);
                res = double(xoNextFloat(r)) < prob;
            }
            break;
        }
        case C_STEEP: res = cGradX <= -4 || cGradZ >= 4; break;
        case C_HOLE: res = cSurfaceDepth <= 0; break;
        case C_ABOVE: res = cY >= cMinSurface; break;
        case C_TEMP: res = !(biomeTemperature(biomeHere(), cX, cY, cZ) >= 0.15f); break;
        default: res = false; break;
        }
        return neg ? !res : res;
    }
    return false;
}

// OreVeinRule

__device__ int oreVein(int k)
{
    int b = gSf[SF_ORES] + k * ORE_WORDS;
    int idx = (cY - MINY()) + (cLX + cLZ * 16) * H();
    float density = gScratch[gChunkIdx * gP.scratchFloats + gSf[b + 4] + idx];
    if (density <= 0.0f) return -1;
    Xo r = xoAt(seed64(gSf[b + 7], gSf[b + 8]), seed64(gSf[b + 9], gSf[b + 10]), cX, cY, cZ);
    if (xoNextFloat(r) > density) return -1;
    float richness = gScratch[gChunkIdx * gP.scratchFloats + gSf[b + 5] + idx];
    if (xoNextFloat(r) < richness && runPoint(gSf[b + 6], ivec3(cX, cY, cZ)) < 0.0f)
        return xoNextFloat(r) < intBitsToFloat(gSf[b + 3]) ? gSf[b + 1] : gSf[b];
    return gSf[b + 2];
}


__device__ int bandAt(int x, int y, int z)
{
    float n = noise3(2, double(x), 0.0, double(z)) * 4.0f;
    int offset = jRound(n);
    return gSf[gSf[SF_BANDS] + (y + offset + 192) % 192];
}

// Regelprogramm: erstes Nicht-null gewinnt; -1 = null

__device__ int runRules()
{
    int pc = gSf[SF_RULES];
    for (int guard = 0; guard < 100000; guard++)
    {
        int op = gSf[pc];
        if (op == R_END) return -1;
        if (op == R_IF)
        {
            if (!evalCond(gSf[pc + 1])) pc = gSf[SF_RULES] + gSf[pc + 2];
            else pc += 3;
            continue;
        }
        if (op == R_BLOCK) return gSf[pc + 1];
        if (op == R_BANDS) return bandAt(cX, cY, cZ);
        if (op == R_ORE)
        {
            int s = oreVein(gSf[pc + 1]);
            if (s >= 0) return s;
            pc += 2;
            continue;
        }
        return -1;
    }
    return -1;
}

// ---------- Erweiterungen ----------

__device__ void erodedBadlands(int height)
{
    double a = gl_abs(double(noise3(5, double(cX), 0.0, double(cZ))) * 8.25);
    float pn = noise3(3, double(cX) * 0.2, 0.0, double(cZ) * 0.2) * 15.0f;
    double pillarBuffer = gl_min(a, double(pn));
    if (pillarBuffer <= 0.0) return;
    double pillarFloor = gl_abs(double(noise3(4, double(cX) * 0.75, 0.0, double(cZ) * 0.75)) * 1.5);
    double sq = pillarBuffer * pillarBuffer * 2.5;
    double roof = gl_ceil(pillarFloor * 50.0) + 24.0;
    double top = 64.0 + gl_min(sq, roof);
    int startY = int(gl_floor(top));
    if (height > startY) return;
    for (int y = startY; y >= MINY(); y--)
    {
        uint s = stateAt(cLX, y, cLZ);
        if (int(s) == gSf[SF_DEFAULT]) break;
        if (int(s) == gSf[SF_WATER]) return;
    }
    for (int y = startY; y >= MINY() && isAirS(stateAt(cLX, y, cLZ)); y--) setState(cLX, y, cLZ, uint(gSf[SF_DEFAULT]));
}


__device__ void frozenOcean(int surfaceBiome, int height)
{
    double a = gl_abs(double(noise3(8, double(cX), 0.0, double(cZ))) * 8.25);
    float pn = noise3(6, double(cX) * 1.28, 0.0, double(cZ) * 1.28) * 15.0f;
    double iceberg = gl_min(a, double(pn));
    if (iceberg <= 1.8) return;
    double roofN = gl_abs(double(noise3(7, double(cX) * 1.17, 0.0, double(cZ) * 1.17)) * 1.5);
    double sqr = iceberg * iceberg * 1.2;
    double roofTop = gl_ceil(roofN * 40.0) + 14.0;
    double top = gl_min(sqr, roofTop);
    int sea = gSf[SF_SEA];
    if (biomeTemperature(surfaceBiome, cX, sea, cZ) > 0.1f) top -= 2.0;
    if (top <= 2.0) return;
    double extBottom = double(sea) - top - 7.0;
    top += double(sea);
    double extTop = top;
    Xo r = xoAt(seed64(gSf[SF_ROOTSEED], gSf[SF_ROOTSEED + 1]), seed64(gSf[SF_ROOTSEED + 2], gSf[SF_ROOTSEED + 3]), cX, 0, cZ);
    int maxSnowDepth = 2 + xoNextInt(r, 4);
    int minSnowHeight = sea + 18 + xoNextInt(r, 10);
    int snowDepth = 0;
    int iTop = int(extTop), iBottom = int(extBottom);
    for (int y = gl_max(height, iTop + 1); y >= cMinSurface; y--)
    {
        uint s = stateAt(cLX, y, cLZ);
        bool hit = false;
        if (isAirS(s) && y < iTop && xoNextDouble(r) > 0.01) hit = true;
        else
        {
            uint s2 = stateAt(cLX, y, cLZ);
            if (int(s2) == gSf[SF_WATER] && y > iBottom && y < sea && xoNextDouble(r) > 0.15) hit = true;
        }
        if (hit)
        {
            if (snowDepth <= maxSnowDepth && y > minSnowHeight) { setState(cLX, y, cLZ, uint(gSf[SF_SNOW])); snowDepth++; }
            else setState(cLX, y, cLZ, uint(gSf[SF_PACKED_ICE]));
        }
    }
}


__device__ int columnTop(int lx, int lz)
{
    for (int y = MINY() + H() - 1; y >= MINY(); y--) if (!isAirS(stateAt(lx, y, lz))) return y;
    return MINY() - 1;
}


    static constexpr int BF_RULE_AIR = 256, BF_RULE_FLUID = 512;


    static constexpr uint SURFACE_MARK = 0xFFFFFFFFu;


__device__ bool stateHasFluid(int st)
{
    for (int i = 0; i < gSf[SF_NFLUIDS]; i++) if (gSf[gSf[SF_FLUIDS] + i] == st) return true;
    return false;
}

// Fiddle-Kandidaten eines Blocks (x, z): Quarts (x-2)>>2 und +1 je Achse, alle Hoehen

__device__ bool rulesMayChangeColumn(bool iceberg)
{
    int solid = gSf[SF_RULES_SOLID];
    if ((solid & 1) != 0 && ((solid & 2) != 0 || !iceberg)) return false;
    int mask = BF_RULE_AIR | (iceberg ? BF_RULE_FLUID : 0);
    int px = (cX - 2) >> 2, pz = (cZ - 2) >> 2, minQ = MINY() >> 2;
    for (int d = 0; d < 4; d++)
        for (int y = 0; y < gSf[SF_QH]; y++)
            if ((biomeFlags(biomeAtQuart(px + (d & 1), minQ + y, pz + (d >> 1))) & mask) != 0) return true;
    return false;
}

// Ganze Spalte wie buildSurface (Gradienten vorgegeben)

__device__ void processColumn(int gradX, int gradZ, bool haveGrad)
{
    int cb = (gChunkIdx * 256 + cLX + cLZ * 16) * 4;
    int startingHeight = gCol[cb] + 1;
    int surfaceBiome = biomeAt(cX, startingHeight, cZ);
    if ((biomeFlags(surfaceBiome) & 2) != 0) erodedBadlands(startingHeight);
    int height = columnTop(cLX, cLZ) + 1;
    if (!haveGrad) gCol[cb + 1] = height - 1;      // Pass 0: Hoehe nach Badlands (gradientenunabhaengig)
    cGradX = haveGrad ? gradX : 0;
    cGradZ = haveGrad ? gradZ : 0;
    cSurfaceDepth = surfaceDepthAt(cX, cZ);
    cSecondaryValid = false;
    // getMinSurfaceLevel: gl_floor(chunkSurfaceLevel) + surfaceDepth - 8
    float prelim = gScratch[gChunkIdx * gP.scratchFloats + gSf[SF_PRELIM] + (cLX + cLZ * 16)];
    cMinSurface = jFloorF(prelim) + cSurfaceDepth - 8;
    int stoneAbove = 0, waterHeight = JINT_MIN, nextCeiling = 2147483647;
    // Vorhersage (Pass 0): Regeln, die Luft setzen koennen, aendern die Spaltenhoehe; solche mit
    // Wasser/Lava das Muster, das der Eisberg liest. Kann keines der Biome, die der BiomeManager
    // fuer Bloecke dieser Spalte waehlt, das (ExactMaterials BF_RULE_*), entfaellt die Regelschleife.
    bool skipRules = !haveGrad && !rulesMayChangeColumn((biomeFlags(surfaceBiome) & 4) != 0);
    int yEnd = skipRules ? height + 1 : MINY();
    for (int y = height; y >= yEnd; y--)
    {
        uint old = stateAt(cLX, y, cLZ);
        if (isAirS(old)) { stoneAbove = 0; waterHeight = JINT_MIN; }
        else if (isFluidS(old)) { if (waterHeight == JINT_MIN) waterHeight = y + 1; }
        else
        {
            if (nextCeiling >= y)
            {
                nextCeiling = gSf[SF_WAYBELOW];     // DimensionType.WAY_BELOW_MIN_Y
                for (int la = y - 1; la >= MINY() - 1; la--)
                {
                    if (!isStoneS(stateAt(cLX, la, cLZ))) { nextCeiling = la + 1; break; }
                }
            }
            stoneAbove++;
            cY = y;
            cStoneAbove = stoneAbove;
            cStoneBelow = y - nextCeiling + 1;
            cWaterHeight = waterHeight;
            cBiome = -1;
            if (y >= MINY() && y <= gSf[SF_MAXY])
            {
                int st = runRules();
                if (st >= 0)
                {
                    setState(cLX, y, cLZ, uint(st));
                    // MaterialSystem markiert gesetzte Fluessigkeits-Zustaende fuer Post-Processing;
                    // die Vorhersage-Kopie wird in Pass 1 nicht mehr gebraucht und traegt die Marke.
                    if (haveGrad && stateHasFluid(st))
                        gStateTmp[gChunkIdx * 256 * H() + (y - MINY()) * 256 + cLZ + cLX * 16] = SURFACE_MARK;
                }
            }
        }
    }
    if ((biomeFlags(surfaceBiome) & 4) != 0) frozenOcean(surfaceBiome, startingHeight);
}


__device__ void main_()
{
    int col = int(vf_gid.x);
    int chunk = int(vf_gid.y);
    if (col >= 256 || chunk >= gP.chunkCount) return;
    gChunkIdx = chunk;
    cLX = col >> 4;                                   // x-major: Spalte = x * 16 + z
    cLZ = col & 15;
    ivec2 cm = gChunk[chunk];
    cX = cm.x + cLX;
    cZ = cm.y + cLZ;
    int cb = (chunk * 256 + cLX + cLZ * 16) * 4;
    int pass = gP.task;
    if (pass == 2)
    {
        // Zustaende aus der Aquifer-Substanz (beide Puffer), Top nach doFill
        for (int yi = 0; yi < H(); yi++)
        {
            int i = chunk * 256 * H() + yi * 256 + cLZ + cLX * 16;
            uint su = gSubst[chunk * 256 * H() + yi + (cLX + cLZ * 16) * H()] & 127u;
            uint s = su == 0u ? uint(gSf[SF_DEFAULT]) : su == 1u ? uint(gSf[SF_AIR]) : su == 2u ? uint(gSf[SF_WATER]) : uint(gSf[SF_LAVA]);
            gState[i] = s;
            gStateTmp[i] = s;
        }
        usingTmp = false;
        gCol[cb] = columnTop(cLX, cLZ);
        return;
    }
    if (pass == 3)
    {
        // Ausgabe: Bit 31 = markPosForPostProcessing (doFill: Fluessigkeit mit Aquifer-Fluss-Update)
        for (int yi = 0; yi < H(); yi++)
        {
            int i = chunk * 256 * H() + yi * 256 + cLZ + cLX * 16;
            uint su = gSubst[chunk * 256 * H() + yi + (cLX + cLZ * 16) * H()];
            if ((su & 128u) != 0u && ((su & 127u) == 2u || (su & 127u) == 3u)) gState[i] |= 0x80000000u;
            if (gStateTmp[i] == SURFACE_MARK) gState[i] |= 0x40000000u;
        }
        return;
    }
    if (pass == 0)
    {
        usingTmp = true;
        // Kopie komplett verarbeiten (Gradient 0): Hoehe nach Badlands und End-Top (Regeln, Eisberg).
        // Nur solide->solide-Regeln haengen vom Hang ab; Heightmap-Aenderungen nicht.
        processColumn(0, 0, false);
        gCol[cb + 2] = columnTop(cLX, cLZ);
        return;
    }
    // Pass 1: Gradienten wie Vanilla sie in x-major-Reihenfolge sieht
    usingTmp = false;
    int hx1 = gl_min(cLX + 1, 15), hx0 = gl_max(cLX - 1, 0), hz1 = gl_min(cLZ + 1, 15), hz0 = gl_max(cLZ - 1, 0);
    #define TOP(nx, nz) ((nx) == cLX && (nz) == cLZ ? gCol[(chunk * 256 + (nx) + (nz) * 16) * 4 + 1] \
        : (((nx) < cLX || ((nx) == cLX && (nz) < cLZ)) ? gCol[(chunk * 256 + (nx) + (nz) * 16) * 4 + 2] \
        : gCol[(chunk * 256 + (nx) + (nz) * 16) * 4]))
    int gx = TOP(hx1, cLZ) - TOP(hx0, cLZ);
    int gz = TOP(cLX, hz1) - TOP(cLX, hz0);
    #undef TOP
    processColumn(gx, gz, true);
    gCol[cb + 3] = columnTop(cLX, cLZ);
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_surface(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
