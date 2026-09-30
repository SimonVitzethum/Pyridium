// Aus shaders/glsl/wg_exact_pointtest.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_pointtest.glsl – Testkern fuer den Punkt-Interpreter: je Thread eine Abfrage
// (x, y, z, Code-Start) aus gCoord, Ergebnis nach gScratch.

#include "wg_exact_common.cuh"
#include "wg_exact_point.cuh"

    static constexpr int VF_BLOCK = 64;


__device__ void main_()
{
    int i = int(vf_gid.x);
    if (i >= gP.chunkCount) return;
    ivec3 c = ivec3(int(gCoord[i * 4]), int(gCoord[i * 4 + 1]), int(gCoord[i * 4 + 2]));
    gScratch[i] = runPoint(int(gCoord[i * 4 + 3]), c);
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_pointtest(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
