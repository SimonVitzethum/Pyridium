// wg_full_hf.cu - fast terrain for the full-chunk generator: heightfield from the preliminary surface instead of the
// per-block 3D density. Writes the block states (stone / water / air) consumed by the surface kernel (wg_full_surface, pass 1 only). Stateless: depends only on (seed, x, z) through the coarse surface grid + hash noise.
// Bindings as the exact kernels (common macros) plus 8 = cfg ints, 11 = substance out.
// cfg: 0 minY, 1 height, 2 sea level, 3 coarse grid scratch offset (6x6 points, step 4, origin -4), 4/5 seed lo/hi, 6 amp1, 7 amp2 (float bits),
//      8 default block state, 9 water state, 10 air state. Writes block states directly (coalesced) + the column tops the surface kernel expects (gCol).
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#define gHfCfg ((const int*)vf_b[8])
#define gStateOut ((uint*)vf_b[13])
#define gColOut ((int*)vf_b[15])
#undef VF_SCOPE_FILE

__device__ __forceinline__ unsigned long long hf_mix(unsigned long long z)
{
    z += 0x9E3779B97F4A7C15ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}
__device__ __forceinline__ float hf_val(unsigned long long seed, int ix, int iz, int o)
{
    unsigned long long h = hf_mix(seed ^ ((unsigned long long)(unsigned)ix | ((unsigned long long)(unsigned)iz << 32)) ^ ((unsigned long long)o << 56));
    return (float)(h >> 40) * (1.0f / 8388608.0f) - 1.0f;
}
// smooth value noise in [-1,1], cell size 'cs'
__device__ float hf_noise(unsigned long long seed, int x, int z, int cs, int o)
{
    int ix = (int)floorf((float)x / cs), iz = (int)floorf((float)z / cs);
    float fx = ((float)x - (float)(ix * cs)) / cs, fz = ((float)z - (float)(iz * cs)) / cs;
    fx = fx * fx * (3.0f - 2.0f * fx);
    fz = fz * fz * (3.0f - 2.0f * fz);
    float a = hf_val(seed, ix, iz, o), b = hf_val(seed, ix + 1, iz, o), c = hf_val(seed, ix, iz + 1, o), d = hf_val(seed, ix + 1, iz + 1, o);
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
}

extern "C" __global__ void __launch_bounds__(256) wg_full_hf(ExactPush p)
{
    int c = blockIdx.x;
    if (c >= p.chunkCount) return;
    int tid = threadIdx.x;
    int lz = tid & 15, lx = tid >> 4;        // thread -> column; state index yi * 256 + z + x * 16 = yi * 256 + tid (coalesced)
    const int minY = gHfCfg[0], H = gHfCfg[1], sea = gHfCfg[2], off = gHfCfg[3];
    unsigned long long seed = ((unsigned long long)(unsigned)gHfCfg[5] << 32) | (unsigned)gHfCfg[4];
    float amp1 = __int_as_float(gHfCfg[6]), amp2 = __int_as_float(gHfCfg[7]);
    ivec2 cm = gChunk[c];
    int x = cm.x + lx, z = cm.y + lz;
    // coarse grid (6x6, step 4, origin -4): a sample represents the quart [4q, 4q + 4)
    const float* g = gScratch + (size_t)c * p.scratchFloats + off;
    float u = (float)(lx + 2) * 0.25f, v = (float)(lz + 2) * 0.25f;
    int iu = (int)u, iv = (int)v;
    float fu = u - (float)iu, fv = v - (float)iv;
    float h00 = g[iu + iv * 6], h10 = g[iu + 1 + iv * 6], h01 = g[iu + (iv + 1) * 6], h11 = g[iu + 1 + (iv + 1) * 6];
    float h = (h00 + (h10 - h00) * fu) + ((h01 + (h11 - h01) * fu) - (h00 + (h10 - h00) * fu)) * fv;
    h += amp1 * hf_noise(seed, x, z, 24, 1) + amp2 * hf_noise(seed, x, z, 7, 2);
    int top = (int)floorf(h);
    if (top < minY + 2) top = minY + 2;
    if (top > minY + H - 2) top = minY + H - 2;
    uint stone = (uint)gHfCfg[8], water = (uint)gHfCfg[9], air = (uint)gHfCfg[10];
    uint* out = gStateOut + (size_t)c * 256 * H + tid;
    for (int yi = 0; yi < H; yi++) {
        int y = minY + yi;
        out[(size_t)yi * 256] = y <= top ? stone : (y <= sea ? water : air);
    }
    int topNonAir = top < sea ? sea : top;
    int* col = gColOut + ((size_t)c * 256 + lx + lz * 16) * 4;
    col[0] = topNonAir;
    col[1] = topNonAir;
    col[2] = topNonAir;
}
