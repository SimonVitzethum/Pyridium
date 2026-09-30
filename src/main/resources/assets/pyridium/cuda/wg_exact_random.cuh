// Aus shaders/glsl/wg_exact_random.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#if defined(VF_SCOPE_FILE) && !defined(VF_WG_EXACT_RANDOM_FILE)
#define VF_WG_EXACT_RANDOM_FILE
#ifndef WG_EXACT_RANDOM
#define WG_EXACT_RANDOM


struct Xo { julong lo; julong hi; };
#endif
#endif
#if defined(VF_SCOPE_MEMBER) && !defined(VF_WG_EXACT_RANDOM_MEMBER)
#define VF_WG_EXACT_RANDOM_MEMBER
// wg_exact_random.glsl – Zufall und Hilfen wie Vanilla (26.3): Xoroshiro128++
// (XoroshiroRandomSource inkl. Positionsfabrik), Mth.getSeed, korrekt gerundete double-Division.

#ifndef WG_EXACT_RANDOM_MEMBER
#define WG_EXACT_RANDOM_MEMBER

// ---------- double-Division korrekt gerundet (Rest per DFMA pruefen) ----------

__device__ double dDiv(double a, double b)
{
    double q = a / b;
    double r0 = gl_fma(-q, b, a);
    jlong qb = doubleBitsToInt64(q);
    double qUp = int64BitsToDouble(qb + 1LL);
    double qDn = int64BitsToDouble(qb - 1LL);
    double rUp = gl_fma(-qUp, b, a);
    double rDn = gl_fma(-qDn, b, a);
    if (gl_abs(rUp) < gl_abs(r0)) { q = qUp; r0 = rUp; }
    if (gl_abs(rDn) < gl_abs(r0)) { q = qDn; }
    return q;
}

// ---------- Xoroshiro128++ (XoroshiroRandomSource) ----------

__device__ julong rotl64(julong x, int k) { return (x << k) | (x >> (64 - k)); }


__device__ julong xoNext(Xo& r)
{
    julong s0 = r.lo, s1 = r.hi;
    julong res = rotl64(s0 + s1, 17) + s0;
    s1 ^= s0;
    r.lo = rotl64(s0, 49) ^ s1 ^ (s1 << 21);
    r.hi = rotl64(s1, 28);
    return res;
}


__device__ int xoNextInt(Xo& r, int bound)
{
    julong bits = julong(uint(xoNext(r)));          // Integer.toUnsignedLong(nextInt())
    julong mult = bits * julong(bound);
    julong frac = mult & 0xFFFFFFFFULL;
    if (frac < julong(bound))
    {
        uint start = uint(-bound) % uint(bound);         // Integer.remainderUnsigned(~bound + 1, bound)
        while (frac < julong(start))
        {
            bits = julong(uint(xoNext(r)));
            mult = bits * julong(bound);
            frac = mult & 0xFFFFFFFFULL;
        }
    }
    return int(mult >> 32);
}

// Mth.getSeed(x, y, z) – x * 3129871 ist int-Multiplikation (Ueberlauf wie Java)

__device__ jlong mthSeed(int x, int y, int z)
{
    jlong seed = jlong(jmul32(x, 3129871)) ^ jmul64(jlong(z), 116129781LL) ^ jlong(y);
    seed = jadd64(jmul64(jmul64(seed, seed), 42317861LL), jmul64(seed, 11LL));
    return seed >> 16;
}


__device__ Xo xoAt(julong seedLo, julong seedHi, int x, int y, int z)
{
    Xo r;
    r.lo = julong(mthSeed(x, y, z)) ^ seedLo;
    r.hi = seedHi;
    if ((r.lo | r.hi) == 0ULL)
    {
        r.lo = julong(-7046029254386353131LL);
        r.hi = julong(7640891576956012809LL);
    }
    return r;
}


__device__ julong seed64(int lo, int hi) { return julong(uint(lo)) | (julong(uint(hi)) << 32); }

// nextFloat: nextBits(24) * 5.9604645E-8F

__device__ float xoNextFloat(Xo& r)
{
    uint bits = uint(xoNext(r) >> 40);
    float f = float(bits) * 5.9604645E-8f;
    return f;
}

// nextDouble: nextBits(53) * (double) 1.110223E-16F

__device__ double xoNextDouble(Xo& r)
{
    julong bits = xoNext(r) >> 11;
    double d = double(bits) * double(1.110223E-16f);
    return d;
}
#endif
#endif
