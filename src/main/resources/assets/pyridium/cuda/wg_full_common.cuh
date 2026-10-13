// wg_full_common.cuh - shared declarations of the full-chunk GPU stages (wg_full_*.cu).
// State buffer layout (from ExactTerrainPipeline.stateBuffer): int per block, index (c * H + yi) * 256 + z + x * 16,
// low 30 bits = block state id (Block.BLOCK_STATE_REGISTRY), bits 30/31 = vanilla post-processing marks (ignored here).
#pragma once
#include "wg_prelude.cuh"
#include "wg_carve.cuh"

struct FullPush { int a[8]; };

// per block state flags (gFlags[state id]), built on the host from the registry/tags
#define F_AIR 1
#define F_FLUID 2
#define F_MOTION 4        // blocks motion (OCEAN_FLOOR heightmap)
#define F_LEAVES 8
#define F_CARVABLE 16     // carver_replaceables
#define F_ORE_STONE 32    // stone variants (replaced by the stone flavour of an ore)
#define F_ORE_DEEP 64     // deepslate variants
#define F_BEDROCK 128
#define F_OPAQUE 256      // full light dampening (skylight stops)
#define F_DAMP 512        // partial dampening (water, leaves, ice: -1)
#define F_SOLID_GROUND 1024  // can carry vegetation (grass block, dirt, ...)
#define F_GRASS 2048
#define F_SAND 4096
#define F_WATER 8192
#define F_LAVA 16384
#define F_HM_MB 65536       // MOTION_BLOCKING heightmap predicate
#define F_HM_NL 131072      // MOTION_BLOCKING_NO_LEAVES predicate
#define F_HASFLUID 262144   // fluid state not empty (section fluid count)
#define F_RTICK 524288      // randomly ticking block
#define F_FTICK 1048576     // randomly ticking fluid

#define gChunkXZ ((const int*)vf_b[0])
#define gStates ((int*)vf_b[1])
#define gFlags ((const int*)vf_b[2])
#define gCarveP ((const CarveParams*)vf_b[3])
#define gOreP ((const OreParams*)vf_b[4])
#define gCfg ((const int*)vf_b[5])

// gCfg layout (ints)
#define CFG_MINY 0
#define CFG_HEIGHT 1
#define CFG_SEA 2
#define CFG_LAVAY 3
#define CFG_AIR 4
#define CFG_WATER 5
#define CFG_LAVA 6
#define CFG_BEDROCK 7
#define CFG_HBITS 8      // bits per heightmap entry
#define CFG_NSTATES 9
#define CFG_BITS_TABLE 16     // [CFG_BITS_TABLE + count] = storage bits for palette size count (0..256)
#define CFG_DECOR 300         // decoration tables start (see wg_full_decor.cu)

__device__ __forceinline__ int fdiv16(int v) { return v >> 4; }
__device__ __forceinline__ int fdivn(int v, int n) { return (int)floorf((float)v / (float)n); }
