// Aus shaders/glsl/wg_exact_point.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#if defined(VF_SCOPE_FILE) && !defined(VF_WG_EXACT_POINT_FILE)
#define VF_WG_EXACT_POINT_FILE
#ifndef WG_EXACT_POINT
#define WG_EXACT_POINT

#define gPCode ((int*)vf_b[8])

#define gPParam ((int*)vf_b[9])
#ifndef PMAX_REGS
#define PMAX_REGS 384
#endif
#endif
#endif
#if defined(VF_SCOPE_MEMBER) && !defined(VF_WG_EXACT_POINT_MEMBER)
#define VF_WG_EXACT_POINT_MEMBER
// wg_exact_point.glsl – Punkt-Interpreter (ExactPointProgram.java): Vanillas sampleValue-
// Semantik bitgenau (Kurzschluesse von mul/div/min/max, Rauschkoordinaten (b*scale)*f in
// double, Slices, find_top_surface als Schleife ueber ein Unterprogramm).
// Braucht wg_exact_common.glsl; bindet Code (8) und Parameter (9).

#ifndef WG_EXACT_POINT_MEMBER
#define WG_EXACT_POINT_MEMBER
#ifndef PMAX_REGS
#define PMAX_REGS 384
#endif


    static constexpr int P_END = 0, P_CONST = 1, P_ADD = 2, P_SUB = 3, P_MUL = 4, P_DIV = 5, P_MIN = 6, P_MAX = 7,
    P_CADD = 8, P_CMUL = 9, P_CSUB = 10, P_CDIV = 11, P_CMIN = 12, P_CMAX = 13, P_ABS = 14, P_SQUARE = 15,
    P_CUBE = 16, P_LEAKY = 17, P_NEGATE = 18, P_RECIP = 19, P_SQRT = 20, P_SIGN = 21, P_SQUEEZE = 22,
    P_CLAMP = 23, P_RANGE = 24, P_RANGE_CONST = 25, P_THRESH1 = 26, P_INTERVAL = 27, P_LERP = 28,
    P_LERP_CF = 29, P_LERP_CS = 30, P_GRADIENT = 31, P_NOISE = 32, P_SHIFTED = 33, P_SHIFTB = 34,
    P_SPLINE = 35, P_SETC = 36, P_RESTC = 37, P_FINDTOP = 38, P_RET = 39;


float pReg[PMAX_REGS];


__device__ float pk(int bits) { return intBitsToFloat(bits); }

// (int) Math.floor(float) mit Java-Saettigung

__device__ int jFloorF(float v)
{
    if (gl_isnan(v)) return 0;
    if (v >= 2147483647.0f) return 2147483647;
    if (v <= -2147483648.0f) return -2147483648;
    return int(gl_floor(v));
}

// PerlinNoise.get / SmearedPerlinNoise.get (Punktpfad): Eingabe = x*frequency usw.

__device__ float gradDotP(ivec3 g, float x, float y, float z)
{
    float a = float(g.x) * x + float(g.y) * y;
    float r = a + float(g.z) * z;
    return r;
}


__device__ float layerPoint(int layer, double px, double py, double pz)
{
    double x = jWrap(px) + layerOff(layer, 0);
    double y = jWrap(py) + layerOff(layer, 1);
    double z = jWrap(pz) + layerOff(layer, 2);
    double flx = gl_floor(x), fly = gl_floor(y), flz = gl_floor(z);
    int fx = int(flx), fy = int(fly), fz = int(flz);
    double dx = x - flx, dyy = y - fly, dz = z - flz;
    float rx = float(dx), rz = float(dz);
    float ryAlpha = float(dyy);
    float ry = ryAlpha;
    double fudgeScale = layerOff(layer, 3);
    if (fudgeScale != 0.0)
    {
        // SmearedPerlinNoise.computeFudgeY(_y, relativeY)
        double limit = (py >= 0.0 && py < dyy) ? py : dyy;
        double q = limit / fudgeScale;
        double r0 = gl_fma(-q, fudgeScale, limit);
        jlong qb = doubleBitsToInt64(q);
        double qUp = int64BitsToDouble(qb + 1LL);
        double qDn = int64BitsToDouble(qb - 1LL);
        double rUp = gl_fma(-qUp, fudgeScale, limit);
        double rDn = gl_fma(-qDn, fudgeScale, limit);
        if (gl_abs(rUp) < gl_abs(r0)) { q = qUp; r0 = rUp; }
        if (gl_abs(rDn) < gl_abs(r0)) { q = qDn; }
        double s = q + double(1.0E-7f);
        int fq = int(gl_floor(s));
        double fudge = double(fq) * fudgeScale;
        double diff = dyy - fudge;
        ry = float(diff);
    }
    int x0 = permute(layer, fx);
    int x1 = permute(layer, fx + 1);
    int xy00 = permute(layer, x0 + fy);
    int xy01 = permute(layer, x0 + fy + 1);
    int xy10 = permute(layer, x1 + fy);
    int xy11 = permute(layer, x1 + fy + 1);
    float rx1 = rx - 1.0f, ry1 = ry - 1.0f, rz1 = rz - 1.0f;
    float d000 = gradDotP(GRAD[permute(layer, xy00 + fz) & 15], rx, ry, rz);
    float d100 = gradDotP(GRAD[permute(layer, xy10 + fz) & 15], rx1, ry, rz);
    float d010 = gradDotP(GRAD[permute(layer, xy01 + fz) & 15], rx, ry1, rz);
    float d110 = gradDotP(GRAD[permute(layer, xy11 + fz) & 15], rx1, ry1, rz);
    float d001 = gradDotP(GRAD[permute(layer, xy00 + fz + 1) & 15], rx, ry, rz1);
    float d101 = gradDotP(GRAD[permute(layer, xy10 + fz + 1) & 15], rx1, ry, rz1);
    float d011 = gradDotP(GRAD[permute(layer, xy01 + fz + 1) & 15], rx, ry1, rz1);
    float d111 = gradDotP(GRAD[permute(layer, xy11 + fz + 1) & 15], rx1, ry1, rz1);
    return jLerp3(jSmooth(rx), jSmooth(ryAlpha), jSmooth(rz), d000, d100, d010, d110, d001, d101, d011, d111);
}

// NoiseStack.get(x, y, z): value += amplitude * layer.get(x*f, y*f, z*f)

__device__ float stackPoint(int stackOff, double x, double y, double z)
{
    int so = gP.stackOffsetBase + stackOff;
    int nl = gStack[so];
    float v = 0.0f;
    for (int l = 0; l < nl; l++)
    {
        int e = so + 1 + l * 4;
        int layer = gStack[e];
        float amp = pk(gStack[e + 1]);
        double f = loadD(uint(gStack[e + 2]), uint(gStack[e + 3]));
        double lx = x * f, ly = y * f, lz = z * f;
        float s = layerPoint(layer, lx, ly, lz);
        float m = amp * s;
        v = v + m;
    }
    return v;
}


__device__ double pD(int at) { return loadD(uint(gPParam[at]), uint(gPParam[at + 1])); }


__device__ float pSpline(int regList, int codeBase, int root)
{
    int nodeSt[12];
    int stateSt[12];
    float inSt[12];
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
        if (gPParam[node] == 0)
        {
            ret = pk(gPParam[node + 1]);
            sp--;
            if (sp < 0) break;
            continue;
        }
        int ci = gPParam[node + 1];
        int n = gPParam[node + 2];
        int locs = node + 3, ders = node + 3 + n, kids = node + 3 + 2 * n;
        if (st == 0)
        {
            float inp = pReg[gPParam[regList + ci]];
            int lo = 0, len = n;
            while (len > 0)
            {
                int hlf = len / 2;
                int mid = lo + hlf;
                if (inp < pk(gPParam[locs + mid])) len = hlf;
                else { lo = mid + 1; len = len - hlf - 1; }
            }
            int start = lo - 1;
            inSt[sp] = inp;
            startSt[sp] = start;
            int child;
            if (start < 0) { stateSt[sp] = 10; child = gPParam[kids]; }
            else if (start == n - 1) { stateSt[sp] = 11; child = gPParam[kids + n - 1]; }
            else { stateSt[sp] = 1; child = gPParam[kids + start]; }
            sp++;
            nodeSt[sp] = child;
            stateSt[sp] = 0;
            continue;
        }
        float inp = inSt[sp];
        int start = startSt[sp];
        if (st == 10 || st == 11)
        {
            int k = st == 10 ? 0 : n - 1;
            float der = pk(gPParam[ders + k]);
            if (der != 0.0f)
            {
                float dd = inp - pk(gPParam[locs + k]);
                float mm = der * dd;
                ret = ret + mm;
            }
            sp--;
            if (sp < 0) break;
            continue;
        }
        if (st == 1)
        {
            y1St[sp] = ret;
            stateSt[sp] = 2;
            sp++;
            nodeSt[sp] = gPParam[kids + start + 1];
            stateSt[sp] = 0;
            continue;
        }
        float y1 = y1St[sp], y2 = ret;
        float x1 = pk(gPParam[locs + start]), x2 = pk(gPParam[locs + start + 1]);
        float d1 = pk(gPParam[ders + start]), d2 = pk(gPParam[ders + start + 1]);
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


__device__ float pGradient(int modeAx, int p, ivec3 c)
{
    int ax = modeAx & 15, mode = modeAx >> 4;
    int coord = ax == 0 ? c.x : (ax == 1 ? c.y : c.z);
    int from = gPParam[p];
    float fromValue = pk(gPParam[p + 3]), factor = pk(gPParam[p + 4]);
    float m;
    if (mode == 0) m = float(gl_clamp(coord, gPParam[p + 1], gPParam[p + 2]) - from) * factor;
    else if (mode == 1) m = float(jFloorMod(coord - from, gPParam[p + 1])) * factor;
    else
    {
        int range = gPParam[p + 1];
        int rel = coord - from;
        int tile = jFloorDiv(rel, range);
        int local = rel - tile * range;
        m = float((tile & 1) == 0 ? local : range - local) * factor;
    }
    float r = fromValue + m;
    return r;
}

/** Fuehrt den Code-Bereich ab start aus (bis P_END) an Block c; Ergebnis = Register von P_END. */

__device__ float runPoint(int start, ivec3 c0)
{
    ivec3 c = c0;
    ivec3 cStack[8];
    int csp = 0;
    int ftPc[4];
    int ftProbe[4];
    int ftSavedY[4];
    int ftDepth = 0;
    int pc = start;
    for (int guard = 0; guard < 200000; guard++)
    {
        int b = pc * 8;
        int op = gPCode[b];
        int dst = gPCode[b + 1];
        int ra = gPCode[b + 2], rb = gPCode[b + 3], rc = gPCode[b + 4];
        int i0 = gPCode[b + 5], i1 = gPCode[b + 6], i2 = gPCode[b + 7];
        bool regs = op != P_SETC && op != P_RESTC;   // SETC: a/b/c sind Koordinaten, keine Register
        float a = (regs && ra >= 0) ? pReg[ra] : 0.0f;
        float bb = (regs && rb >= 0) ? pReg[rb] : 0.0f;
        float r = 0.0f;
        pc++;
        switch (op)
        {
        case P_END: return a;
        case P_CONST: r = pk(i0); break;
        case P_ADD: r = a + bb; break;
        case P_SUB: r = a - bb; break;
        case P_MUL: r = a == 0.0f ? 0.0f : a * bb; break;
        case P_DIV: r = a == 0.0f ? 0.0f : jDiv(a, bb); break;
        case P_MIN: r = a <= pk(i0) ? a : jMin(a, bb); break;
        case P_MAX: r = a >= pk(i0) ? a : jMax(a, bb); break;
        case P_CADD: r = a + pk(i0); break;
        case P_CMUL: r = a * pk(i0); break;
        case P_CSUB: r = pk(i0) - a; break;
        case P_CDIV: r = jDiv(pk(i0), a); break;
        case P_CMIN: r = jMin(a, pk(i0)); break;
        case P_CMAX: r = jMax(a, pk(i0)); break;
        case P_ABS: r = jAbs(a); break;
        case P_SQUARE: r = a * a; break;
        case P_CUBE: { float s = a * a; r = s * a; break; }
        case P_LEAKY: r = a > 0.0f ? a : a * pk(i0); break;
        case P_NEGATE: r = -a; break;
        case P_RECIP: r = jDiv(1.0f, a); break;
        case P_SQRT: r = jSqrt(a); break;
        case P_SIGN: r = jSignum(a); break;
        case P_SQUEEZE:
        {
            float cl = jClamp(a, -1.0f, 1.0f);
            float h = cl * 0.5f;
            float c3 = cl * cl;
            float c33 = c3 * cl;
            r = h - jDiv(c33, 24.0f);
            break;
        }
        case P_CLAMP: r = jClamp(a, pk(i0), pk(i1)); break;
        case P_RANGE: r = (a >= pk(i0) && a < pk(i1)) ? bb : pReg[rc]; break;
        case P_RANGE_CONST: r = (a >= pk(i0) && a < pk(i1)) ? pk(gPParam[i2]) : pk(gPParam[i2 + 1]); break;
        case P_THRESH1: r = a < pk(i0) ? bb : pReg[rc]; break;
        case P_INTERVAL:
        {
            int nt = i1, sel = nt;
            for (int k = 0; k < nt; k++) if (a < pk(gPParam[i0 + k])) { sel = k; break; }
            r = pReg[gPParam[i0 + nt + sel]];
            break;
        }
        case P_LERP: r = a == 0.0f ? bb : (a == 1.0f ? pReg[rc] : jLerp(a, bb, pReg[rc])); break;
        case P_LERP_CF: r = a == 0.0f ? pk(i0) : (a == 1.0f ? bb : jLerp(a, pk(i0), bb)); break;
        case P_LERP_CS: r = a == 0.0f ? bb : (a == 1.0f ? pk(i0) : jLerp(a, bb, pk(i0))); break;
        case P_GRADIENT: r = pGradient(i0, i1, c); break;
        case P_NOISE:
        {
            double xzs = pD(i1), ys = pD(i1 + 2);
            double x = double(c.x) * xzs, y = double(c.y) * ys, z = double(c.z) * xzs;
            r = stackPoint(i0, x, y, z);
            break;
        }
        case P_SHIFTED:
        {
            double xzs = pD(i1), ys = pD(i1 + 2);
            double x = double(c.x) * xzs + double(a);
            double z = double(c.z) * xzs + double(bb);
            double y = double(c.y) * ys;
            if (rc >= 0) y = y + double(pReg[rc]);
            r = stackPoint(i0, x, y, z);
            break;
        }
        case P_SHIFTB:
        {
            double x = double(c.z) * 0.25, y = double(c.x) * 0.25;
            float v = stackPoint(i0, x, y, 0.0);
            r = v * 4.0f;
            break;
        }
        case P_SPLINE: r = pSpline(i0, i1, i2); break;
        case P_SETC:
            cStack[csp++] = c;
            if ((i0 & 1) != 0) c.x = ra;
            if ((i0 & 2) != 0) c.y = rb;
            if ((i0 & 4) != 0) c.z = rc;
            continue;
        case P_RESTC:
            c = cStack[--csp];
            continue;
        case P_FINDTOP:
        {
            // FindTopSurfaceFunction.findSurfaceFrom(x, z, upperBound)
            int lower = i0, h = i1;
            int topY = jFloorF(jDiv(a, float(h))) * h;
            if (topY <= lower) { pReg[dst] = float(lower); pc = rc; continue; }
            ftPc[ftDepth] = pc - 1;
            ftProbe[ftDepth] = topY;
            ftSavedY[ftDepth] = c.y;
            ftDepth++;
            c.y = topY;
            continue;
        }
        case P_RET:
        {
            int d = ftDepth - 1;
            int fb = ftPc[d] * 8;
            int fdst = gPCode[fb + 1], lower = gPCode[fb + 5], h = gPCode[fb + 6], after = gPCode[fb + 4];
            if (a > 0.0f)
            {
                pReg[fdst] = float(ftProbe[d]);
                c.y = ftSavedY[d];
                ftDepth--;
                pc = after;
                continue;
            }
            int next = ftProbe[d] - h;
            if (next >= lower)
            {
                ftProbe[d] = next;
                c.y = next;
                pc = ftPc[d] + 1;
                continue;
            }
            pReg[fdst] = float(lower);
            c.y = ftSavedY[d];
            ftDepth--;
            pc = after;
            continue;
        }
        default:
            r = uintBitsToFloat(0x7fc00000u);
            break;
        }
        if (dst >= 0) pReg[dst] = r;
    }
    return uintBitsToFloat(0x7fc00000u);
}
#endif
#endif
