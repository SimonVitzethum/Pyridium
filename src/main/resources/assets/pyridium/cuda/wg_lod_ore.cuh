// Pyridium: Erze mit Biomfilter fuer die LOD-Kerne (Datapack-Erze nur in ihren Biomen).
// Gleiche Blobs wie oreAt() aus wg_carve.cuh, aber Eintraege werden nach Biom-Bitmaske uebersprungen.
#pragma once
#include "wg_carve.cuh"

// mask: je Eintrag nWords Worte (Bit b = Biom b darf diesen Eintrag verwenden)
__device__ __forceinline__ int oreAtBiome(const OreParams& op, const int* mask, int nWords, int biome, int x, int y, int z, int deep)
{
    int ccx = x >> 4, ccy = y >> 4, ccz = z >> 4;
    int lx = x & 15, ly = y & 15, lz = z & 15;
    for (int e = 0; e < op.count; e++) {
        if (((mask[e * nWords + (biome >> 5)] >> (biome & 31)) & 1) == 0) continue;
        const OreEntry& o = op.e[e];
        float rm = o.radius * 1.3f + 1.0f;
        if (y < o.minY - (int)rm || y > o.maxY + (int)rm) continue;
        int ox0 = (float)lx < rm ? -1 : 0, ox1 = (float)lx > 16.0f - rm ? 1 : 0;
        int oy0 = (float)ly < rm ? -1 : 0, oy1 = (float)ly > 16.0f - rm ? 1 : 0;
        int oz0 = (float)lz < rm ? -1 : 0, oz1 = (float)lz > 16.0f - rm ? 1 : 0;
        for (int dz = oz0; dz <= oz1; dz++)
            for (int dy = oy0; dy <= oy1; dy++)
                for (int dx = ox0; dx <= ox1; dx++) {
                    int cx = ccx + dx, cy = ccy + dy, cz = ccz + dz;
                    wc_u64 h = wc_ore_cell_hash(op, e, cx, cy, cz);
                    int n = wc_ore_cell_count(o, h);
                    for (int j = 0; j < n; j++) {
                        WcBlob b;
                        if (wc_ore_blob(op, e, h, j, cx, cy, cz, b) && wc_ore_voxel(op, b, x, y, z)) {
                            int st = deep ? o.stateDeep : o.stateStone;
                            if (st >= 0) return st;
                        }
                    }
                }
    }
    return -1;
}
