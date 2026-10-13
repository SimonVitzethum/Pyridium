// Aus shaders/glsl/wg_exact_coords.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_coords.glsl – FP64-Vorstufe: Rauschgitter-Koordinaten je Achse (Vanilla
// PerlinNoise/SmearedPerlinNoise.addToVolume). Ein Thread je (Eintrag, Chunk). Klein:
// die 3D-Arbeit laeuft danach komplett in float (wg_exact_task.glsl).

#include "wg_exact_common.cuh"

    static constexpr int VF_BLOCK = 64;


__device__ void main_()
{
    int entry = int(vf_gid.x);
    int chunk = int(vf_gid.y);
    if (entry >= gP.coordEntries || chunk >= gP.chunkCount) return;
    if (gP.pad1 == 1 && gChunkY[chunk] == (-2147483647 - 1)) return;  // vom GPU-Band abgewaehlt
    // Segment suchen (sortiert nach entryOffset)
    int lo = 0, hi = gP.segCount - 1;
    while (lo < hi)
    {
        int mid = (lo + hi + 1) >> 1;
        if (gSeg[mid * SEG_WORDS + 5] <= entry) lo = mid; else hi = mid - 1;
    }
    int sb = lo * SEG_WORDS;
    int layer = gSeg[sb];
    int flags = gSeg[sb + 1];
    int kind = flags & 3;
    bool smeared = (flags & 4) != 0;
    bool relative = (flags & 8) != 0;
    int axis = (flags >> 4) & 3;
    int i = entry - gSeg[sb + 5];
    int block = gSeg[sb + 3] + i * gSeg[sb + 4];
    if (relative) block += axis == 0 ? gChunk[chunk].x : (axis == 2 ? gChunk[chunk].y : gChunkY[chunk]);
    double scale = loadD(uint(gSeg[sb + 6]), uint(gSeg[sb + 7]));
    double orig = double(block) * scale;          // volume.blockX(i) * xzScale
    double c = jWrap(orig) + layerOff(layer, kind); // wrap(...) + offset
    double fl = gl_floor(c);
    int ifl = int(fl);                                      // Mth.floor(double)
    double rel = c - fl;                            // exakt
    float relF = float(rel);                                // (float)(x - floorX)
    float fudged = 0.0f;
    if (kind == 1 && smeared)
    {
        // SmearedPerlinNoise.computeFudgeY(originalY, relativeY), alles double
        double fudgeScale = layerOff(layer, 3);
        double limit = (orig >= 0.0 && orig < rel) ? orig : rel;
        double q = limit / fudgeScale;
        // Division exakt runden: Kandidat per FMA-Rest pruefen (DFMA ist einfach gerundet)
        double r0 = gl_fma(-q, fudgeScale, limit);
        jlong qb = doubleBitsToInt64(q);
        double qUp = int64BitsToDouble(qb + 1LL);
        double qDn = int64BitsToDouble(qb - 1LL);
        double rUp = gl_fma(-qUp, fudgeScale, limit);
        double rDn = gl_fma(-qDn, fudgeScale, limit);
        if (gl_abs(rUp) < gl_abs(r0)) { q = qUp; r0 = rUp; }
        if (gl_abs(rDn) < gl_abs(r0)) { q = qDn; }
        double e7 = double(1.0E-7f);                 // 1.0E-7F nach double
        double s = q + e7;
        int fq = int(gl_floor(s));
        double fudge = double(fq) * fudgeScale;
        double diff = rel - fudge;
        fudged = float(diff);
    }
    int o = (chunk * gP.coordEntries + entry) * 3;
    gCoord[o] = uint(ifl);
    gCoord[o + 1] = floatBitsToUint(relF);
    gCoord[o + 2] = floatBitsToUint(fudged);
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_coords(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
