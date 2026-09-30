// Aus shaders/glsl/wg_exact_common.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#if defined(VF_SCOPE_FILE) && !defined(VF_WG_EXACT_COMMON_FILE)
#define VF_WG_EXACT_COMMON_FILE

#define gTask ((int*)vf_b[0])

#define gParam ((int*)vf_b[1])

#define gLayer ((uint*)vf_b[2])

#define gStack ((int*)vf_b[3])

#define gSeg ((int*)vf_b[4])

#define gChunk ((ivec2*)vf_b[5])
// Chunk-Minimum x/z (Bloecke)

#define gCoord ((uint*)vf_b[6])
// je Chunk: Eintraege x 3 (floor, rel, fudged)

#define gScratch ((float*)vf_b[7])

#define gChunkY ((int*)vf_b[20])
// Pyridium: y-Ursprung je Chunk fuer relative y-Volumen (absMask Bit 2, LOD-Baender)
struct ExactPush
{
    int task;          // Aufgabe (Task-Kernel)
    int chunkCount;
    int scratchFloats; // je Chunk
    int coordEntries;  // je Chunk
    int segCount;
    int stackOffsetBase;
    int taskCount, pad1;   // taskCount: Aufgaben der fusionierten Gruppe ab 'task' (0 = einzeln)
};
// GradientNoise.GRADIENT

__device__ const ivec3 GRAD[16] = {ivec3(1, 1, 0), ivec3(-1, 1, 0), ivec3(1, -1, 0), ivec3(-1, -1, 0),
    ivec3(1, 0, 1), ivec3(-1, 0, 1), ivec3(1, 0, -1), ivec3(-1, 0, -1),
    ivec3(0, 1, 1), ivec3(0, -1, 1), ivec3(0, 1, -1), ivec3(0, -1, -1),
    ivec3(1, 1, 0), ivec3(0, -1, 1), ivec3(-1, 1, 0), ivec3(0, -1, -1)};
#endif
#if defined(VF_SCOPE_MEMBER) && !defined(VF_WG_EXACT_COMMON_MEMBER)
#define VF_WG_EXACT_COMMON_MEMBER
// wg_exact_common.glsl – bitgenaue Worldgen (Minecraft 26.3): gemeinsame Deklarationen.
// Arithmetik exakt wie die JVM: jede float-Operation einzeln gerundet (precise = keine
// FMA-Kontraktion), Denormals/±0/NaN erhalten, Round-to-nearest-even. double nur in den
// Koordinaten-Vorstufen (Gitterkoordinaten wie Vanilla in double, danach (float)).
// Plan und Kodierung: ExactPlan.java.

    ExactPush gP;


    static constexpr int TASK_WORDS = 24;


    static constexpr int LAYER_WORDS = 72;


    static constexpr int SEG_WORDS = 12;


__device__ double loadD(uint lo, uint hi) { return packDouble2x32(uvec2(lo, hi)); }

// ---------- Java-Semantik (float) ----------

__device__ bool jIsNaN(float a) { return gl_isnan(a); }


__device__ float jMin(float a, float b)
{
    if (gl_isnan(a)) return a;
    if (a == 0.0f && b == 0.0f && floatBitsToUint(b) == 0x80000000u) return b;
    return (a <= b) ? a : b;
}


__device__ float jMax(float a, float b)
{
    if (gl_isnan(a)) return a;
    if (a == 0.0f && b == 0.0f && floatBitsToUint(a) == 0x80000000u) return b;
    return (a >= b) ? a : b;
}


__device__ float jClamp(float v, float lo, float hi) { return v < lo ? lo : jMin(v, hi); }


__device__ float jAbs(float a) { return uintBitsToFloat(floatBitsToUint(a) & 0x7fffffffu); }


__device__ float jSignum(float a)
{
    if (a == 0.0f || gl_isnan(a)) return a;
    return uintBitsToFloat((floatBitsToUint(a) & 0x80000000u) | 0x3f800000u);
}

// Korrekt gerundete float-Division (JVM a / b). Schneller Pfad in FP32: Kehrwert mit einem
// Newton-Schritt, Markstein-Korrektur q = q0 + (a - b*q0)*y, dann Pruefung mit exaktem Rest
// (FFMA): q ist RN(a/b) genau dann, wenn |a - b*q| < |b| * ulp(q)/2 (Mitten sind bei einem
// Quotienten zweier floats unmoeglich). Operanden in [2^-40, 2^40] halten Rest und Schranke im
// Normalbereich; Zweierpotenz-Quotienten (asymmetrische ulp) und alles andere: Quotient in
// double (Fehler < 2^-49 relativ genuegt), danach RTE nach float.

__device__ float jDiv(float a, float b)
{
    float ab = gl_abs(a), bb = gl_abs(b);
    if (ab >= 9.094947e-13f && ab <= 1099511627776.0f && bb >= 9.094947e-13f && bb <= 1099511627776.0f)
    {
        float y0 = 1.0f / b;
        float e = gl_fma(-b, y0, 1.0f);
        float y = gl_fma(e, y0, y0);
        float q0 = a * y;
        float r0 = gl_fma(-b, q0, a);
        float q = gl_fma(r0, y, q0);
        uint qb = floatBitsToUint(q) & 0x7fffffffu;
        if ((qb & 0x7fffffu) != 0u)
        {
            float res = gl_fma(-b, q, a);
            float halfUlp = uintBitsToFloat((qb & 0x7f800000u) - (24u << 23));
            float lim = bb * halfUlp;
            if (gl_abs(res) < lim) return q;
        }
    }
    double qd = double(a) / double(b);
    return float(qd);
}

// Korrekt gerundete float-Wurzel ((float) Math.sqrt(double)): gleiche Begruendung.

__device__ float jSqrt(float a)
{
    double r = gl_sqrt(double(a));
    return float(r);
}


__device__ float jLerp(float t, float a, float b)
{
    float d = b - a;
    float m = t * d;
    float r = a + m;
    return r;
}


__device__ float jSmooth(float x)
{
    float a = x * 6.0f;
    float b = a - 15.0f;
    float c = x * b;
    float d = c + 10.0f;
    float x2 = x * x;
    float x3 = x2 * x;
    float r = x3 * d;
    return r;
}

// Mth.lerp3(a1,a2,a3, x000,x100,x010,x110,x001,x101,x011,x111)

__device__ float jLerp3(float a1, float a2, float a3, float x000, float x100, float x010, float x110,
             float x001, float x101, float x011, float x111)
{
    float l00 = jLerp(a1, x000, x100);
    float l10 = jLerp(a1, x010, x110);
    float l0 = jLerp(a2, l00, l10);
    float l01 = jLerp(a1, x001, x101);
    float l11 = jLerp(a1, x011, x111);
    float l1 = jLerp(a2, l01, l11);
    return jLerp(a3, l0, l1);
}

// Math.floorDiv/floorMod ohne GLSL-% (fuer negative Operanden undefiniert): Rest selbst bilden,
// korrekt fuer abschneidende wie abrundende Hardware-Division.

__device__ int jFloorDiv(int a, int b)
{
    int q = a / b;
    int r = a - q * b;
    if (r != 0 && ((r < 0) != (b < 0))) q--;
    return q;
}


__device__ int jFloorMod(int a, int b) { return a - jFloorDiv(a, b) * b; }


__device__ int permute(int layer, int x)
{
    uint i = uint(x) & 255u;
    return int((gLayer[layer * LAYER_WORDS + int(i >> 2)] >> ((i & 3u) * 8u)) & 255u);
}


__device__ double layerOff(int layer, int axis) // 0 x, 1 y, 2 z, 3 fudge
{
    int b = layer * LAYER_WORDS + 64 + axis * 2;
    return loadD(gLayer[b], gLayer[b + 1]);
}

// GradientNoise.wrap (ohne Farlands)

__device__ double jWrap(double x)
{
    const double HALF = 16777215.999999998; // Math.nextDown(1.6777216E7)
    if (x >= -HALF && x < HALF) return x;
    double q = x / 33554432.0 + 0.5;  // Division durch 2^25 exakt
    double r = x - gl_floor(q) * 33554432.0;
    return r;
}
#endif
