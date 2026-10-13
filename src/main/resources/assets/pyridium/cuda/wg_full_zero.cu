// part of the wg_full_* family (full-chunk GPU generator), see docs/FULL_CHUNK_GEN.md
#include "wg_full_common.cuh"

// zero an int buffer: binding 0, push a0 = int count
extern "C" __global__ void __launch_bounds__(256) wg_full_zero(FullPush p)
{
    int* b = (int*)vf_b[0];
    for (int i = blockIdx.x * 256 + threadIdx.x; i < p.a[0]; i += gridDim.x * 256) b[i] = 0;
}
