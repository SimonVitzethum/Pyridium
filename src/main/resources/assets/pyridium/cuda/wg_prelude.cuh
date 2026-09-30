// wg_prelude.cuh – gemeinsame Grundlage der exakten Worldgen-Kernels (CUDA).
// Uebersetzt mit -fmad=false -prec-div=true -prec-sqrt=true -ftz=false (build.gradle compileCuda /
// NVRTC in ExactCudaGen): jede float/double-Operation wird einzeln IEEE-gerundet wie in der JVM,
// keine FMA-Kontraktion, Denormals bleiben erhalten; '/' und sqrtf sind korrekt gerundet.
// GLSL-Typen und -Builtins der urspruenglichen Vulkan-Shader sind hier mit gleicher Semantik
// nachgebildet, damit die Kernels 1:1 derselben (bitgenau getesteten) Logik folgen.
#pragma once

typedef unsigned int uint;
// Javas long als long long (passend zu den LL-Literalen; stdint definiert int64_t teils als long,
// das machte Ueberladungen mehrdeutig). Eigene Namen, damit funktionale Casts gueltig bleiben.
typedef long long jlong;
typedef unsigned long long julong;

// Puffer-Bindungen: Geraetezeiger je Binding (Host setzt sie per cuModuleGetGlobal("vf_b"))
__device__ void* vf_b[24];

struct ivec2 {
    int x, y;
    __host__ __device__ constexpr ivec2() : x(0), y(0) {}
    __host__ __device__ constexpr ivec2(int a, int b) : x(a), y(b) {}
};

struct ivec3 {
    int x, y, z;
    __host__ __device__ constexpr ivec3() : x(0), y(0), z(0) {}
    __host__ __device__ constexpr ivec3(int a, int b, int c) : x(a), y(b), z(c) {}
};

__device__ __forceinline__ ivec3 operator+(ivec3 a, ivec3 b) { return ivec3(a.x + b.x, a.y + b.y, a.z + b.z); }
__device__ __forceinline__ ivec3 operator-(ivec3 a, ivec3 b) { return ivec3(a.x - b.x, a.y - b.y, a.z - b.z); }

struct uvec2 {
    uint x, y;
    __host__ __device__ constexpr uvec2(uint a, uint b) : x(a), y(b) {}
};

// ---------- Bit-Casts ----------
__device__ __forceinline__ int floatBitsToInt(float f) { return __float_as_int(f); }
__device__ __forceinline__ uint floatBitsToUint(float f) { return __float_as_uint(f); }
__device__ __forceinline__ float intBitsToFloat(int i) { return __int_as_float(i); }
__device__ __forceinline__ float uintBitsToFloat(uint u) { return __uint_as_float(u); }
__device__ __forceinline__ jlong doubleBitsToInt64(double d) { return __double_as_longlong(d); }
__device__ __forceinline__ double int64BitsToDouble(jlong i) { return __longlong_as_double(i); }
__device__ __forceinline__ double packDouble2x32(uvec2 v) { return __hiloint2double((int)v.y, (int)v.x); }

// ---------- GLSL-Builtins (eindeutige Ueberladungen) ----------
__device__ __forceinline__ int gl_abs(int a) { return a < 0 ? -a : a; }
__device__ __forceinline__ jlong gl_abs(jlong a) { return a < 0 ? -a : a; }
__device__ __forceinline__ float gl_abs(float a) { return fabsf(a); }
__device__ __forceinline__ double gl_abs(double a) { return fabs(a); }

// min/max: Ganzzahlen wie GLSL; float/double wie Javas Math.min/max (NaN setzt sich durch,
// -0.0 < +0.0) – die Shader bilden an diesen Stellen Vanillas Math.min/max nach.
__device__ __forceinline__ int gl_min(int a, int b) { return b < a ? b : a; }
__device__ __forceinline__ int gl_max(int a, int b) { return a < b ? b : a; }
__device__ __forceinline__ uint gl_min(uint a, uint b) { return b < a ? b : a; }
__device__ __forceinline__ uint gl_max(uint a, uint b) { return a < b ? b : a; }
__device__ __forceinline__ jlong gl_min(jlong a, jlong b) { return b < a ? b : a; }
__device__ __forceinline__ jlong gl_max(jlong a, jlong b) { return a < b ? b : a; }
__device__ __forceinline__ float gl_min(float a, float b)
{
    if (isnan(a)) return a;
    if (a == 0.0f && b == 0.0f && __float_as_uint(b) == 0x80000000u) return b;
    return a <= b ? a : b;
}
__device__ __forceinline__ float gl_max(float a, float b)
{
    if (isnan(a)) return a;
    if (a == 0.0f && b == 0.0f && __float_as_uint(a) == 0x80000000u) return b;
    return a >= b ? a : b;
}
__device__ __forceinline__ double gl_min(double a, double b)
{
    if (isnan(a)) return a;
    if (a == 0.0 && b == 0.0 && __double_as_longlong(b) == (jlong)0x8000000000000000ULL) return b;
    return a <= b ? a : b;
}
__device__ __forceinline__ double gl_max(double a, double b)
{
    if (isnan(a)) return a;
    if (a == 0.0 && b == 0.0 && __double_as_longlong(a) == (jlong)0x8000000000000000ULL) return b;
    return a >= b ? a : b;
}
__device__ __forceinline__ int gl_clamp(int v, int lo, int hi) { return gl_min(gl_max(v, lo), hi); }
__device__ __forceinline__ float gl_clamp(float v, float lo, float hi) { return gl_min(gl_max(v, lo), hi); }
__device__ __forceinline__ double gl_clamp(double v, double lo, double hi) { return gl_min(gl_max(v, lo), hi); }

__device__ __forceinline__ float gl_floor(float a) { return floorf(a); }
__device__ __forceinline__ double gl_floor(double a) { return floor(a); }
__device__ __forceinline__ float gl_ceil(float a) { return ceilf(a); }
__device__ __forceinline__ double gl_ceil(double a) { return ceil(a); }
__device__ __forceinline__ float gl_sqrt(float a) { return sqrtf(a); }
__device__ __forceinline__ double gl_sqrt(double a) { return sqrt(a); }
// fma bleibt fusioniert (Restterme exakt), wie FFMA/DFMA im Vulkan-Pfad
__device__ __forceinline__ float gl_fma(float a, float b, float c) { return fmaf(a, b, c); }
__device__ __forceinline__ double gl_fma(double a, double b, double c) { return fma(a, b, c); }
__device__ __forceinline__ bool gl_isnan(float a) { return isnan(a); }
__device__ __forceinline__ bool gl_isnan(double a) { return isnan(a); }

// Java-Ganzzahlarithmetik mit Ueberlauf (in C++ fuer signed undefiniert): ueber unsigned
__device__ __forceinline__ int jmul32(int a, int b) { return (int)((uint)a * (uint)b); }
__device__ __forceinline__ jlong jmul64(jlong a, jlong b) { return (jlong)((julong)a * (julong)b); }
__device__ __forceinline__ jlong jadd64(jlong a, jlong b) { return (jlong)((julong)a + (julong)b); }
