// Aus shaders/glsl/wg_exact_task_body.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#if defined(VF_SCOPE_FILE) && !defined(VF_WG_EXACT_TASK_BODY_FILE)
#define VF_WG_EXACT_TASK_BODY_FILE
#include "wg_exact_common.cuh"
#ifdef WG_TASK_POINT
#include "wg_exact_point.cuh"
#endif
// ---------- Beardifier (Struktur-Anpassung, Beardifier.sampleValueUnchecked, 26.3) ----------
// Je Chunk c: gBeard[c*4] Datenoffset, +1 Teile, +2 Kreuzungen, +3 hat Box; Daten: betroffene Box
// (minX, minY, minZ, maxX, maxY, maxZ), je Teil 8 Worte (Box, Anpassung, groundLevelDelta), je
// Kreuzung 3 Worte (x, groundY, z). Arithmetik float wie Vanilla, fastInvSqrt in double.

#define gBeard ((int*)vf_b[10])
#ifdef WG_TASK_POINT
#endif
#endif
#if defined(VF_SCOPE_MEMBER) && !defined(VF_WG_EXACT_TASK_BODY_MEMBER)
#define VF_WG_EXACT_TASK_BODY_MEMBER
// wg_exact_task_body.glsl – eine Volumen-Aufgabe (ExactPlan.Task) fuer alle Chunks eines Stapels:
// Thread je (Punkt, Chunk). Spiegelt DensitySampler.sampleVolume der Vanilla-Sampler (26.3)
// bitgenau; Eingaben sind fertige Aufgaben-Puffer im Chunk-Scratch.

#include "wg_exact_common.cuh"
#ifdef WG_TASK_POINT
#include "wg_exact_point.cuh"
#endif

    static constexpr int VF_BLOCK = 128;


    static constexpr int OP_CONST = 0, OP_ADD = 1, OP_SUB = 2, OP_MUL = 3, OP_DIV = 4, OP_MIN = 5, OP_MAX = 6,
    OP_CADD = 7, OP_CMUL = 8, OP_CSUB = 9, OP_CDIV = 10, OP_CMIN = 11, OP_CMAX = 12,
    OP_ABS = 13, OP_SQUARE = 14, OP_CUBE = 15, OP_LEAKY = 16, OP_NEGATE = 17, OP_RECIP = 18, OP_SQRT = 19,
    OP_LOG = 20, OP_SIGN = 21, OP_SQUEEZE = 22, OP_CLAMP = 23, OP_RANGE = 24, OP_RANGE_CONST = 25,
    OP_INTERVAL = 26, OP_THRESH1 = 27, OP_LERP = 28, OP_LERP_CF = 29, OP_LERP_CS = 30, OP_GRADIENT = 31,
    OP_NOISE = 32, OP_SHIFTED = 33, OP_INTERP = 34, OP_GATHER = 35, OP_BROADCAST = 36, OP_SPLINE = 37,
    OP_ROUND = 38, OP_FINDTOP = 39, OP_BEARD = 40;


__device__ int T(int task, int w) { return gTask[task * TASK_WORDS + w]; }


__device__ float kf(int bits) { return intBitsToFloat(bits); }

// Fusionierte Gruppe (ExactPlan.groups): Aufgaben [task, task + taskCount) auf gleichem Volumen,
// je Element nacheinander; gruppenintern gelesene Werte liegen in lokalen Slots (Wort 20 Slot,
// 21 Gruppe), nur LIVE_OUT-Aufgaben gehen in den Scratch.

    static constexpr int WG_MAX_SLOTS = 32;


    static constexpr int LIVE_OUT = 1 << 8;


int gGroupStart = -1;


float gLocal[WG_MAX_SLOTS];

// Listen-Eingaben (Spline-Koordinaten, Intervall-Funktionen) liegen immer im Scratch
// (Planer: LIVE_OUT, zaehlen nicht fuer Slot-Lebensdauern)

__device__ float inScratch(int chunkBase, int task, int idx) { return gScratch[chunkBase + T(task, 1) + idx]; }

// Wert einer Eingabe-Aufgabe am gleichen Volumenindex

__device__ float inAt(int chunkBase, int task, int idx)
{
    if (T(task, 21) == gGroupStart)
    {
        int s = T(task, 20);
        if (s >= 0) return gLocal[s];
    }
    return gScratch[chunkBase + T(task, 1) + idx];
}

// Wert einer Aufgabe an Volumenkoordinaten (x, y, z) ihres eigenen Volumens

__device__ float inXYZ(int chunkBase, int task, int x, int y, int z)
{
    int sx = T(task, 3), sy = T(task, 4);
    return gScratch[chunkBase + T(task, 1) + y + (x + z * sx) * sy];
}

// ---------- Rauschen, Volumenpfad (PerlinNoise.addToVolume / SmearedPerlinNoise.addToVolume) ----------

__device__ float noiseLayerVolume(int layer, int chunk, int xe, int ye, int ze)
{
    int cb = chunk * gP.coordEntries;
    int fx = int(gCoord[(cb + xe) * 3]);
    float rx = uintBitsToFloat(gCoord[(cb + xe) * 3 + 1]);
    int fy = int(gCoord[(cb + ye) * 3]);
    float ry = uintBitsToFloat(gCoord[(cb + ye) * 3 + 1]);
    float fudged = uintBitsToFloat(gCoord[(cb + ye) * 3 + 2]);
    int fz = int(gCoord[(cb + ze) * 3]);
    float rz = uintBitsToFloat(gCoord[(cb + ze) * 3 + 1]);
    bool smeared = layerOff(layer, 3) != 0.0;
    float yv = smeared ? fudged : ry;       // y-Anteil der Gradienten (geschmiert: fudgedRelativeY)
    float alphaZ = jSmooth(rz);
    float alphaX = jSmooth(rx);
    float alphaY = jSmooth(ry);             // Smeared: smoothstep((float) relativeY)
    int x0 = permute(layer, fx);
    int x1 = permute(layer, fx + 1);
    int xy00 = permute(layer, x0 + fy);
    int xy01 = permute(layer, x0 + fy + 1);
    int xy10 = permute(layer, x1 + fy);
    int xy11 = permute(layer, x1 + fy + 1);
    ivec3 g000 = GRAD[permute(layer, xy00 + fz) & 15];
    ivec3 g100 = GRAD[permute(layer, xy10 + fz) & 15];
    ivec3 g010 = GRAD[permute(layer, xy01 + fz) & 15];
    ivec3 g110 = GRAD[permute(layer, xy11 + fz) & 15];
    ivec3 g001 = GRAD[permute(layer, xy00 + fz + 1) & 15];
    ivec3 g101 = GRAD[permute(layer, xy10 + fz + 1) & 15];
    ivec3 g011 = GRAD[permute(layer, xy01 + fz + 1) & 15];
    ivec3 g111 = GRAD[permute(layer, xy11 + fz + 1) & 15];
    float rx1 = rx - 1.0f;
    float rz1 = rz - 1.0f;
    float y1 = yv - 1.0f;
    // dotXz: x*gx + z*gz, danach + gy*y (Reihenfolge wie Vanilla)
    float d000 = float(g000.x) * rx + float(g000.z) * rz;
    float d100 = float(g100.x) * rx1 + float(g100.z) * rz;
    float d010 = float(g010.x) * rx + float(g010.z) * rz;
    float d110 = float(g110.x) * rx1 + float(g110.z) * rz;
    float d001 = float(g001.x) * rx + float(g001.z) * rz1;
    float d101 = float(g101.x) * rx1 + float(g101.z) * rz1;
    float d011 = float(g011.x) * rx + float(g011.z) * rz1;
    float d111 = float(g111.x) * rx1 + float(g111.z) * rz1;
    float e000 = d000 + float(g000.y) * yv;
    float e100 = d100 + float(g100.y) * yv;
    float e010 = d010 + float(g010.y) * y1;
    float e110 = d110 + float(g110.y) * y1;
    float e001 = d001 + float(g001.y) * yv;
    float e101 = d101 + float(g101.y) * yv;
    float e011 = d011 + float(g011.y) * y1;
    float e111 = d111 + float(g111.y) * y1;
    return jLerp3(alphaX, alphaY, alphaZ, e000, e100, e010, e110, e001, e101, e011, e111);
}

// ---------- Rauschen, Punktpfad (PerlinNoise.get), Koordinaten in double ----------

__device__ float gradDot(ivec3 g, float x, float y, float z)
{
    float a = float(g.x) * x + float(g.y) * y;   // (gx*x + gy*y) + gz*z
    float r = a + float(g.z) * z;
    return r;
}


__device__ float perlinPoint(int layer, double px, double py, double pz)
{
    double x = jWrap(px) + layerOff(layer, 0);
    double y = jWrap(py) + layerOff(layer, 1);
    double z = jWrap(pz) + layerOff(layer, 2);
    double flx = gl_floor(x), fly = gl_floor(y), flz = gl_floor(z);
    int fx = int(flx), fy = int(fly), fz = int(flz);
    double dx = x - flx, dy = y - fly, dz = z - flz;
    float rx = float(dx), ry = float(dy), rz = float(dz);
    int x0 = permute(layer, fx);
    int x1 = permute(layer, fx + 1);
    int xy00 = permute(layer, x0 + fy);
    int xy01 = permute(layer, x0 + fy + 1);
    int xy10 = permute(layer, x1 + fy);
    int xy11 = permute(layer, x1 + fy + 1);
    float rx1 = rx - 1.0f, ry1 = ry - 1.0f, rz1 = rz - 1.0f;
    float d000 = gradDot(GRAD[permute(layer, xy00 + fz) & 15], rx, ry, rz);
    float d100 = gradDot(GRAD[permute(layer, xy10 + fz) & 15], rx1, ry, rz);
    float d010 = gradDot(GRAD[permute(layer, xy01 + fz) & 15], rx, ry1, rz);
    float d110 = gradDot(GRAD[permute(layer, xy11 + fz) & 15], rx1, ry1, rz);
    float d001 = gradDot(GRAD[permute(layer, xy00 + fz + 1) & 15], rx, ry, rz1);
    float d101 = gradDot(GRAD[permute(layer, xy10 + fz + 1) & 15], rx1, ry, rz1);
    float d011 = gradDot(GRAD[permute(layer, xy01 + fz + 1) & 15], rx, ry1, rz1);
    float d111 = gradDot(GRAD[permute(layer, xy11 + fz + 1) & 15], rx1, ry1, rz1);
    return jLerp3(jSmooth(rx), jSmooth(ry), jSmooth(rz), d000, d100, d010, d110, d001, d101, d011, d111);
}

// ---------- Splines (CubicSpline.Multipoint.sample) ----------

__device__ float linearExtend(float inp, float loc, float value, float der)
{
    if (der == 0.0f) return value;
    float d = inp - loc;
    float m = der * d;
    float r = value + m;
    return r;
}


__device__ float splineEval(int chunkBase, int idx, int codeBase, int root, int coordList)
{
    int nodeSt[12];
    int stateSt[12];
    float inputSt[12];
    int startSt[12];
    float y1St[12];
    int sp = 0;
    nodeSt[0] = root;
    stateSt[0] = 0;
    float ret = 0.0f;
    for (int guard = 0; guard < 4096; guard++)
    {
        int node = codeBase + nodeSt[sp];
        int st = stateSt[sp];
        if (gParam[node] == 0)
        {
            ret = kf(gParam[node + 1]);
            sp--;
            if (sp < 0) break;
            continue;
        }
        int ci = gParam[node + 1];
        int n = gParam[node + 2];
        int locs = node + 3, ders = node + 3 + n, kids = node + 3 + 2 * n;
        if (st == 0)
        {
            float inp = inScratch(chunkBase, gParam[coordList + ci], idx);
            // findIntervalStart: binarySearch(0, n, i -> inp < loc[i]) - 1
            int lo = 0, len = n;
            while (len > 0)
            {
                int hlf = len / 2;
                int mid = lo + hlf;
                if (inp < kf(gParam[locs + mid])) len = hlf;
                else { lo = mid + 1; len = len - hlf - 1; }
            }
            int start = lo - 1;
            inputSt[sp] = inp;
            startSt[sp] = start;
            int child;
            if (start < 0) { stateSt[sp] = 10; child = gParam[kids]; }
            else if (start == n - 1) { stateSt[sp] = 11; child = gParam[kids + n - 1]; }
            else { stateSt[sp] = 1; child = gParam[kids + start]; }
            sp++;
            nodeSt[sp] = child;
            stateSt[sp] = 0;
            continue;
        }
        float inp = inputSt[sp];
        int start = startSt[sp];
        if (st == 10 || st == 11)
        {
            int k = st == 10 ? 0 : n - 1;
            ret = linearExtend(inp, kf(gParam[locs + k]), ret, kf(gParam[ders + k]));
            sp--;
            if (sp < 0) break;
            continue;
        }
        if (st == 1)
        {
            y1St[sp] = ret;
            stateSt[sp] = 2;
            sp++;
            nodeSt[sp] = gParam[kids + start + 1];
            stateSt[sp] = 0;
            continue;
        }
        // st == 2: kubischer Abschnitt
        float y1 = y1St[sp], y2 = ret;
        float x1 = kf(gParam[locs + start]), x2 = kf(gParam[locs + start + 1]);
        float d1 = kf(gParam[ders + start]), d2 = kf(gParam[ders + start + 1]);
        float dx = x2 - x1;
        float num = inp - x1;
        float t = jDiv(num, dx);
        float dy = y2 - y1;
        float a = d1 * dx - dy;
        float nd2 = -d2;
        float b = nd2 * dx + dy;
        float omt = 1.0f - t;
        float tt = t * omt;
        float l1 = jLerp(t, y1, y2);
        float l2 = jLerp(t, a, b);
        float m = tt * l2;
        float r = l1 + m;
        ret = r;
        sp--;
        if (sp < 0) break;
    }
    return ret;
}


    static constexpr int ADJ_NONE = 0, ADJ_BURY = 1, ADJ_BEARD_THIN = 2, ADJ_BEARD_BOX = 3, ADJ_ENCAPSULATE = 4;


__device__ float beardBury(float dx, float dy, float dz)
{
    float d2 = dx * dx + dy * dy + dz * dz;
    if (d2 >= 36.0f) return 0.0f;
    float r = 1.0f - jDiv(jSqrt(d2), 6.0f);
    return r;
}

// Mth.fastInvSqrt(double)

__device__ double fastInvSqrtD(double x)
{
    double xhalf = 0.5 * x;
    jlong i = doubleBitsToInt64(x);
    i = 6910469410427058090LL - (i >> 1);
    double y = int64BitsToDouble(i);
    double t = xhalf * y;
    double t2 = t * y;
    double s = 1.5 - t2;
    double r = y * s;
    return r;
}


__device__ float beardContribution(int dx, int dy, int dz, int yToGround, int kernel)
{
    int xi = dx + 12, yi = dy + 12, zi = dz + 12;
    if (xi < 0 || xi >= 24 || yi < 0 || yi >= 24 || zi < 0 || zi >= 24) return 0.0f;
    float dyo = float(yToGround) + 0.5f;
    float fdx = float(dx), fdz = float(dz);
    float d2 = fdx * fdx + dyo * dyo + fdz * fdz;
    float hd2 = d2 * 0.5f;                          // distanceSqr / 2.0F (exakt)
    float inv = float(fastInvSqrtD(double(hd2)));
    float v1 = -dyo * inv;
    float v = v1 * 0.5f;                              // / 2.0F
    float r = v * kf(gParam[kernel + zi * 576 + xi * 24 + yi]);
    return r;
}


__device__ float beardValue(int o, int np, int nj, int bx, int by, int bz, int kernel)
{
    float noise = 0.0f;
    int b = o + 6;
    for (int i = 0; i < np; i++, b += 8)
    {
        int mnX = gBeard[b], mnY = gBeard[b + 1], mnZ = gBeard[b + 2], mxX = gBeard[b + 3], mxY = gBeard[b + 4], mxZ = gBeard[b + 5];
        int adj = gBeard[b + 6];
        int dx = gl_max(0, gl_max(mnX - bx, bx - mxX));
        int dz = gl_max(0, gl_max(mnZ - bz, bz - mxZ));
        int groundY = mnY + gBeard[b + 7];
        int dyToGround = by - groundY;
        int dy = adj == ADJ_NONE ? 0 : (adj == ADJ_BURY || adj == ADJ_BEARD_THIN) ? dyToGround
            : adj == ADJ_BEARD_BOX ? gl_max(0, gl_max(groundY - by, by - mxY)) : gl_max(0, gl_max(mnY - by, by - mxY));
        float c = 0.0f;
        if (adj == ADJ_BURY) c = beardBury(float(dx), float(dy) * 0.5f, float(dz));
        else if (adj == ADJ_BEARD_THIN || adj == ADJ_BEARD_BOX) { float g = beardContribution(dx, dy, dz, dyToGround, kernel); c = g * 0.8f; }
        else if (adj == ADJ_ENCAPSULATE) { float g = beardBury(float(dx) * 0.5f, float(dy) * 0.5f, float(dz) * 0.5f); c = g * 0.8f; }
        noise = noise + c;
    }
    for (int i = 0; i < nj; i++, b += 3)
    {
        int dy = by - gBeard[b + 1];
        float g = beardContribution(bx - gBeard[b], dy, bz - gBeard[b + 2], dy, kernel);
        float c = g * 0.4f;
        noise = noise + c;
    }
    return noise;
}

// OP_BEARD: Beardifier.sampleVolume (Bereich per floorDiv aus der betroffenen Box, sonst 0)

__device__ float op40(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int d = chunk * 4;
    if (gBeard[d + 3] == 0) return 0.0f;
    int o = gBeard[d];
    int absMask = T(task, 12);
    int sx = T(task, 3), sy = T(task, 4), sz = T(task, 5), stx = T(task, 9), sty = T(task, 10), stz = T(task, 11);
    int vx = T(task, 6) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x), vy = T(task, 7) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0), vz = T(task, 8) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    int mnX = gBeard[o], mnY = gBeard[o + 1], mnZ = gBeard[o + 2], mxX = gBeard[o + 3], mxY = gBeard[o + 4], mxZ = gBeard[o + 5];
    if (!(mxX >= vx && mnX <= vx + sx * stx - 1 && mxZ >= vz && mnZ <= vz + sz * stz - 1 && mxY >= vy && mnY <= vy + sy * sty - 1)) return 0.0f;
    int x0 = jFloorDiv(gl_max(0, mnX - vx), stx), y0 = jFloorDiv(gl_max(0, mnY - vy), sty), z0 = jFloorDiv(gl_max(0, mnZ - vz), stz);
    int x1 = gl_min(sx - 1, jFloorDiv(mxX - vx, stx)), y1 = gl_min(sy - 1, jFloorDiv(mxY - vy, sty)), z1 = gl_min(sz - 1, jFloorDiv(mxZ - vz, stz));
    if (ix < x0 || ix > x1 || iy < y0 || iy > y1 || iz < z0 || iz > z1) return 0.0f;
    return beardValue(o, gBeard[d + 1], gBeard[d + 2], vx + ix * stx, vy + iy * sty, vz + iz * stz, T(task, 16));
}

// Je Opcode eine Funktion (Interpreter: Switch unten; Codegen ruft sie direkt, gleiche Arithmetik).
// a, b, c: Werte der Eingaenge in0..in2 am Element; fuer INTERP/GATHER/BROADCAST ungenutzt.
// OP_CONST

__device__ float op0(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = kf(p0); break;
    } while (false);
    return r;
}

// OP_ADD

__device__ float op1(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a + b; break;
    } while (false);
    return r;
}

// OP_SUB

__device__ float op2(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    { float nb = -b; r = a + nb; break; }
    } while (false);
    return r;
}

// OP_MUL

__device__ float op3(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a * b; break;
    } while (false);
    return r;
}

// OP_DIV

__device__ float op4(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jDiv(a, b); break;
    } while (false);
    return r;
}

// OP_MIN

__device__ float op5(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = (b < a) ? b : a; break;
    } while (false);
    return r;
}

// OP_MAX

__device__ float op6(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = (b > a) ? b : a; break;
    } while (false);
    return r;
}

// OP_CADD

__device__ float op7(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a + kf(p0); break;
    } while (false);
    return r;
}

// OP_CMUL

__device__ float op8(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a * kf(p0); break;
    } while (false);
    return r;
}

// OP_CSUB

__device__ float op9(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = kf(p0) - a; break;
    } while (false);
    return r;
}

// OP_CDIV

__device__ float op10(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jDiv(kf(p0), a); break;
    } while (false);
    return r;
}

// OP_CMIN

__device__ float op11(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = (kf(p0) < a) ? kf(p0) : a; break;
    } while (false);
    return r;
}

// OP_CMAX

__device__ float op12(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = (kf(p0) > a) ? kf(p0) : a; break;
    } while (false);
    return r;
}

// OP_ABS

__device__ float op13(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jAbs(a); break;
    } while (false);
    return r;
}

// OP_SQUARE

__device__ float op14(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a * a; break;
    } while (false);
    return r;
}

// OP_CUBE

__device__ float op15(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    { float s = a * a; r = s * a; break; }
    } while (false);
    return r;
}

// OP_LEAKY

__device__ float op16(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a > 0.0f ? a : a * kf(p0); break;
    } while (false);
    return r;
}

// OP_NEGATE

__device__ float op17(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = -a; break;
    } while (false);
    return r;
}

// OP_RECIP

__device__ float op18(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jDiv(1.0f, a); break;
    } while (false);
    return r;
}

// OP_SQRT

__device__ float op19(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jSqrt(a); break;
    } while (false);
    return r;
}

// OP_LOG

__device__ float op20(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = uintBitsToFloat(0x7fc00000u); break; // nicht unterstuetzt (Plan meldet es)
    } while (false);
    return r;
}

// OP_SIGN

__device__ float op21(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jSignum(a); break;
    } while (false);
    return r;
}

// OP_SQUEEZE

__device__ float op22(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        float cc = jClamp(a, -1.0f, 1.0f);
        float h = cc * 0.5f;           // c / 2.0F (exakt)
        float c3 = cc * cc;
        float c33 = c3 * cc;
        float q = jDiv(c33, 24.0f);
        r = h - q;
        break;
    }
    } while (false);
    return r;
}

// OP_CLAMP

__device__ float op23(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = jClamp(a, kf(p0), kf(p1)); break;
    } while (false);
    return r;
}

// OP_RANGE

__device__ float op24(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        float inRange = b, outRange = c;
        r = (!(a >= kf(p0)) || !(a < kf(p1))) ? outRange : inRange;
        break;
    }
    } while (false);
    return r;
}

// OP_RANGE_CONST

__device__ float op25(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = (a >= kf(p0) && a < kf(p1)) ? kf(p2) : kf(p3); break;
    } while (false);
    return r;
}

// OP_THRESH1

__device__ float op27(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a < kf(p0) ? b : c; break;
    } while (false);
    return r;
}

// OP_INTERVAL

__device__ float op26(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        int nt = p1, sel = nt;
        for (int i = 0; i < nt; i++) if (a < kf(gParam[p0 + i])) { sel = i; break; }
        r = inScratch(chunkBase, gParam[p0 + nt + sel], idx);
        break;
    }
    } while (false);
    return r;
}

// OP_LERP

__device__ float op28(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        float first = b, second = c;
        r = a == 0.0f ? first : (a == 1.0f ? second : jLerp(a, first, second));
        break;
    }
    } while (false);
    return r;
}

// OP_LERP_CF

__device__ float op29(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a == 0.0f ? kf(p0) : (a == 1.0f ? b : jLerp(a, kf(p0), b)); break;
    } while (false);
    return r;
}

// OP_LERP_CS

__device__ float op30(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = a == 0.0f ? b : (a == 1.0f ? kf(p0) : jLerp(a, b, kf(p0))); break;
    } while (false);
    return r;
}

// OP_GRADIENT

__device__ float op31(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        int ax = p0 & 15, mode = p0 >> 4;
        int coord = ax == 0 ? bx : (ax == 1 ? by : bz);
        int from = gParam[p1];
        float fromValue = kf(gParam[p1 + 3]), factor = kf(gParam[p1 + 4]);
        if (mode == 0)
        {
            int c = gl_clamp(coord, gParam[p1 + 1], gParam[p1 + 2]) - from;
            float m = float(c) * factor;
            r = fromValue + m;
        }
        else if (mode == 1)
        {
            float m = float(jFloorMod(coord - from, gParam[p1 + 1])) * factor;
            r = fromValue + m;
        }
        else
        {
            int range = gParam[p1 + 1];
            int rel = coord - from;
            int tile = jFloorDiv(rel, range);
            int local = rel - tile * range;
            float m = float((tile & 1) == 0 ? local : range - local) * factor;
            r = fromValue + m;
        }
        break;
    }
    } while (false);
    return r;
}

// OP_NOISE

__device__ float op32(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        int stackOff = p0;
        // Stapel p0: Lagen in gParam ab p2 (je 5: Lage, x-/y-/z-Tabelle, Amplitude)
        int nl = gStack[gP.stackOffsetBase + stackOff];
        bool transposed = p3 != 0;
        int vx = transposed ? iz : ix, vy = transposed ? ix : iy, vz = transposed ? 0 : iz;
        float v = 0.0f;
        for (int l = 0; l < nl; l++)
        {
            int pb = p2 + l * 5;
            int layer = gParam[pb];
            float amp = kf(gParam[pb + 4]);
            float s = noiseLayerVolume(layer, chunk, gParam[pb + 1] + vx, gParam[pb + 2] + vy, gParam[pb + 3] + vz);
            float m = amp * s;
            v = v + m;
        }
        r = v;
        break;
    }
    } while (false);
    return r;
}

// OP_SHIFTED

__device__ float op33(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        double xzs = loadD(uint(gParam[p1]), uint(gParam[p1 + 1]));
        double ys = loadD(uint(gParam[p1 + 2]), uint(gParam[p1 + 3]));
        double nx = double(bx) * xzs + double(a);                  // baseNoiseX + shiftX
        double nz = double(bz) * xzs + double(b);
        double ny = double(by) * ys;
        if (in2 >= 0) ny = ny + double(c);
        int so = gP.stackOffsetBase + p0;
        int nl = gStack[so];
        float v = 0.0f;
        for (int l = 0; l < nl; l++)
        {
            int e = so + 1 + l * 4;
            int layer = gStack[e];
            float amp = kf(gStack[e + 1]);
            double f = loadD(uint(gStack[e + 2]), uint(gStack[e + 3]));
            double lx = nx * f, ly = ny * f, lz = nz * f;
            float s = perlinPoint(layer, lx, ly, lz);
            float m = amp * s;
            v = v + m;
        }
        r = v;
        break;
    }
    } while (false);
    return r;
}

// OP_INTERP

__device__ float op34(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        int cxz = p0 & 0xffff, cy = p0 >> 16;
        float invXz = kf(p1), invY = kf(p2);
        int cx0 = T(in0, 6), cy0 = T(in0, 7), cz0 = T(in0, 8);
        int csx = T(in0, 3), csy = T(in0, 4), csz = T(in0, 5);
        // relative/absolute wie Zellvolumen; Pyridium: auch mit Schrittweite (LOD-Volumen)
        int rx = T(task, 6) + ix * T(task, 9), ry = T(task, 7) + iy * T(task, 10), rz = T(task, 8) + iz * T(task, 11);
        int cellX = jFloorDiv(rx, cxz) - jFloorDiv(cx0, cxz);
        int cellY = jFloorDiv(ry, cy) - jFloorDiv(cy0, cy);
        int cellZ = jFloorDiv(rz, cxz) - jFloorDiv(cz0, cxz);
        int ncx = gl_min(cellX + 1, csx - 1), ncy = gl_min(cellY + 1, csy - 1), ncz = gl_min(cellZ + 1, csz - 1);
        float v000 = inXYZ(chunkBase, in0, cellX, cellY, cellZ);
        float v100 = inXYZ(chunkBase, in0, ncx, cellY, cellZ);
        float v001 = inXYZ(chunkBase, in0, cellX, cellY, ncz);
        float v101 = inXYZ(chunkBase, in0, ncx, cellY, ncz);
        float v010 = inXYZ(chunkBase, in0, cellX, ncy, cellZ);
        float v110 = inXYZ(chunkBase, in0, ncx, ncy, cellZ);
        float v011 = inXYZ(chunkBase, in0, cellX, ncy, ncz);
        float v111 = inXYZ(chunkBase, in0, ncx, ncy, ncz);
        int xIn = rx - (cx0 + cellX * cxz), yIn = ry - (cy0 + cellY * cy), zIn = rz - (cz0 + cellZ * cxz);
        float alphaZ = float(zIn) * invXz;
        float v00 = jLerp(alphaZ, v000, v001);
        float v01 = jLerp(alphaZ, v010, v011);
        float v10 = jLerp(alphaZ, v100, v101);
        float v11 = jLerp(alphaZ, v110, v111);
        float alphaX = float(xIn) * invXz;
        float w0 = jLerp(alphaX, v00, v10);
        float w1 = jLerp(alphaX, v01, v11);
        float dw = w1 - w0;
        float step = dw * invY;
        int cellOutY = cy0 + cellY * cy - T(task, 7);
        int y0 = gl_max(0, -cellOutY);
        float m = step * float(y0);
        float val = w0 + m;
        for (int k = y0; k < yIn; k++) val = val + step;
        r = val;
        break;
    }
    } while (false);
    return r;
}

// OP_GATHER

__device__ float op35(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = inXYZ(chunkBase, in0, ix * T(task, 9), iy * T(task, 10), iz * T(task, 11));
        break;
    } while (false);
    return r;
}

// OP_BROADCAST

__device__ float op36(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = inXYZ(chunkBase, in0, (p0 & 1) != 0 ? 0 : ix, (p0 & 2) != 0 ? 0 : iy, (p0 & 4) != 0 ? 0 : iz);
        break;
    } while (false);
    return r;
}

// OP_SPLINE

__device__ float op37(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    r = splineEval(chunkBase, idx, p2, p3, p0);
        break;
    } while (false);
    return r;
}
#ifdef WG_TASK_POINT

// OP_FINDTOP

__device__ float op39(int task, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    int absMask = T(task, 12);
    int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
    int p0 = T(task, 16), p1 = T(task, 17), p2 = T(task, 18), p3 = T(task, 19);
    // Blockkoordinaten (x/z relativ zum Chunk, ausser abs-Bit)
    int bx = T(task, 6) + ix * T(task, 9) + ((absMask & 1) != 0 ? 0 : gChunk[chunk].x);
    int by = T(task, 7) + iy * T(task, 10) + ((absMask & 2) != 0 ? gChunkY[chunk] : 0);
    int bz = T(task, 8) + iz * T(task, 11) + ((absMask & 4) != 0 ? 0 : gChunk[chunk].y);
    float r = 0.0f;
    do {
    {
        // FindTopSurfaceFunction.findSurfaceFrom(x, z, upperBound): Proben der Dichte (Punktpfad)
        int lower = p1, h = p2;
        int topY = jFloorF(jDiv(a, float(h))) * h;
        int found = lower;
        if (topY > lower)
        {
            for (int probe = topY; probe >= lower; probe -= h)
                if (runPoint(p0, ivec3(bx, probe, bz)) > 0.0f) { found = probe; break; }
        }
        r = float(found);
        break;
    }
    } while (false);
    return r;
}
#endif


__device__ float evalTask(int task, int op, float a, float b, float c, int chunk, int chunkBase, int idx, int ix, int iy, int iz)
{
    switch (op)
    {
    case OP_BEARD: return op40(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CONST: return op0(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_ADD: return op1(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SUB: return op2(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_MUL: return op3(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_DIV: return op4(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_MIN: return op5(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_MAX: return op6(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CADD: return op7(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CMUL: return op8(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CSUB: return op9(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CDIV: return op10(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CMIN: return op11(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CMAX: return op12(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_ABS: return op13(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SQUARE: return op14(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CUBE: return op15(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_LEAKY: return op16(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_NEGATE: return op17(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_RECIP: return op18(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SQRT: return op19(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_LOG: return op20(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SIGN: return op21(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SQUEEZE: return op22(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_CLAMP: return op23(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_RANGE: return op24(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_RANGE_CONST: return op25(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_THRESH1: return op27(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_INTERVAL: return op26(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_LERP: return op28(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_LERP_CF: return op29(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_LERP_CS: return op30(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_GRADIENT: return op31(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_NOISE: return op32(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SHIFTED: return op33(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_INTERP: return op34(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_GATHER: return op35(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_BROADCAST: return op36(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
    case OP_SPLINE: return op37(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
#ifdef WG_TASK_POINT
    case OP_FINDTOP: return op39(task, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
#endif
    }
    return uintBitsToFloat(0x7fc00000u);
}


__device__ void main_()
{
#ifdef WG_CODEGEN
    const int start = CG_START;
#else
    int start = gP.task;
#endif
    int count = gl_max(gP.taskCount, 1);
    int n = T(start, 2);
    int idx = int(vf_gid.x);
    int chunk = int(vf_gid.y);
    if (idx >= n || chunk >= gP.chunkCount) return;
    if (gP.pad1 == 1 && gChunkY[chunk] == (-2147483647 - 1)) return;  // 3D-Auftrag vom GPU-Band abgewaehlt
    int chunkBase = chunk * gP.scratchFloats;
    int sx = T(start, 3), sy = T(start, 4);
    int iy = idx % sy;
    int rest = idx / sy;
    int ix = rest % sx;
    int iz = rest / sx;
#ifdef WG_CODEGEN
    CG_BODY
#else
    gGroupStart = gP.taskCount > 0 ? start : -1;
    for (int k = 0; k < count; k++)
    {
        int task = start + k;
        int op = T(task, 0);
        bool pos = op == OP_INTERP || op == OP_GATHER || op == OP_BROADCAST;
        int in0 = T(task, 13), in1 = T(task, 14), in2 = T(task, 15);
        float a = (in0 >= 0 && !pos) ? inAt(chunkBase, in0, idx) : 0.0f;
        float b = (in1 >= 0 && !pos) ? inAt(chunkBase, in1, idx) : 0.0f;
        float c = (in2 >= 0 && !pos) ? inAt(chunkBase, in2, idx) : 0.0f;
        float r = evalTask(task, op, a, b, c, chunk, chunkBase, idx, ix, iy, iz);
        int s = T(task, 20);
        if (gGroupStart >= 0 && s >= 0) gLocal[s] = r;
        if (gGroupStart < 0 || (T(task, 12) & LIVE_OUT) != 0) gScratch[chunkBase + T(task, 1) + idx] = r;
    }
#endif
}
#endif
