// wg_structure_hook.cuh - OPTIONAL device interface for GPU structure generation (to be implemented by the structure stage).
//
// The full-chunk generator (wg_full_*.cu, FullChunkPipeline) has a stage slot after the ore stage and before decoration/
// heightmaps: FullChunkPipeline.addStage(new Dispatch(kernel, push, gx, 0)) (stageMask bit 4). The stage kernel is launched
// with gridDim.y = chunk count (blockIdx.y = chunk index c) and uses the same bindings as the other wg_full kernels:
//   vf_b[0] int2 per chunk (block minimum x, z)      vf_b[1] int states: index (c * H + y - minY) * 256 + z + x * 16
//   vf_b[2] int flags per state id (F_* in wg_full_common.cuh)      vf_b[5] cfg ints (CFG_* in wg_full_common.cuh)
//   vf_b[6..23] free for the structure tables.
// State ids are Block.BLOCK_STATE_REGISTRY ids (0 = air). Stateless contract: the kernel may only depend on (seed, x, y, z)
// plus the compiled tables, never on neighbouring chunks' results, so LOD and near world agree.
//
// Recommended shape of the per-region piece list (a region = StructurePlacement cell, e.g. random_spread spacing):
#pragma once

struct WsPiece {
    int minX, minY, minZ, maxX, maxY, maxZ;   // world-space bounding box (inclusive)
    int templateId;                           // index into the baked template table
    int rotation, mirror, flags;              // rotation 0..3, mirror, bit0: terrain adaptation (beardifier)
};

struct WsStructureApi {
    const int* regionIndex;    // per region hash cell: (offset, count) pairs into pieces
    const WsPiece* pieces;
    const int* templates;      // baked template voxels: per template (sizeX, sizeY, sizeZ, offset), then state ids
    long long seed;
    int regionSize;            // blocks per region cell
};

// Return the block state id a structure places at (x, y, z), -1 = no structure there, 0 = air (carves the terrain).
// Typical use inside the stage kernel: for each voxel of the chunk column box overlapped by a piece bounding box.
__device__ int structureBlockAt(const WsStructureApi& api, int x, int y, int z);

// Terrain adaptation (beads/beard) stays in the exact terrain kernels (OP_BEARD, ExactBeard.encode); the stage only writes blocks.
