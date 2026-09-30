// Aus shaders/glsl/wg_exact_mathtest.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_mathtest.glsl – Testkern fuer die exakten Java-Hilfen: je Thread (a, b) aus gScratch,
// Ergebnis von jDiv(a, b) zurueck an Stelle 3i+2.

#include "wg_exact_common.cuh"

    static constexpr int VF_BLOCK = 64;


__device__ void main_()
{
    int i = int(vf_gid.x);
    if (i >= gP.chunkCount) return;
    gScratch[i * 3 + 2] = jDiv(gScratch[i * 3], gScratch[i * 3 + 1]);
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_mathtest(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
