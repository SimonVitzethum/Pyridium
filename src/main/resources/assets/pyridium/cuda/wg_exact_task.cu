// Aus shaders/glsl/wg_exact_task.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_task_body.cuh"
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_task.glsl – Volumen-Aufgaben ohne Punkt-Interpreter (Rumpf: wg_exact_task_body.glsl).

#include "wg_exact_task_body.cuh"
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_task(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
