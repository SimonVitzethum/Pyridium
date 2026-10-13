// Pyridium: ferne LOD-Stufen als Hoehenfeld, ohne 3D-Dichte und ohne Hoehlen.
//
// Hoehlen sind nur in der Naehe sichtbar (Richtwert 64 Chunks); darueber genuegt die Oberflaeche.
// Eingabe: je Spalte die 9 x 9 vorlaeufigen Oberflaechenhoehen (wg_lod_band), bilinear auf die
// 32 x 32 Voxelspalten interpoliert, dazu das Biom je Spalte (wie wg_lod_voxels.cu). Kosten je Chunk:
// ein paar Prozent der 3D-Dichte, da der ganze 34 x SY x 34-Dichteblock entfaellt.
//
// Je Auftrag ein Pyrit-Chunk (32^3 Voxel der Stufe lod). Ausgabe: die sichtbare Haut in Pyrits Puffer.

typedef unsigned int uint;
#define INT_MIN (-2147483647 - 1)

__device__ void* vf_b[24];
#define STRUCT_EXTERNAL_VFB
#include "wg_struct_adapt.cuh"

#define gLp ((const int*)vf_b[1])         // Kopf, siehe LP_*
#define gJobs ((const int4*)vf_b[2])      // je Auftrag: (Spalte, key.y, Pyrit-Index, 0)
#define gVox ((uint4*)vf_b[3])            // Pyrit: PyrVoxel[count * capacity]
#define gCounts ((uint*)vf_b[4])          // Pyrit: uint[count]
#define gBiome ((const int*)vf_b[5])      // je Biom BIOME_WORDS
#define gTmp ((const int*)vf_b[6])        // Biom-Kernel: je Stuetzspalte TMP_WORDS, Wort 0 = Blatt
#define gNode ((const int*)vf_b[7])       // R-Baum-Knoten, Wort 30 = Biom-Index
#define gColumns ((const int*)vf_b[8])    // je Spalte Blockminimum x, z
#define gPre ((const float*)vf_b[9])      // je Spalte 9 x 9 Oberflaechenhoehen
#define gLut ((const uint*)vf_b[10])      // wg_lod_surface: je Auftrag und Spalte 4 Materialien (0xFFFFFFFF = keins)
#define gStruct ((const StructTables*)vf_b[11])   // GPU-Strukturen (StructureGpu.viewBuffer), aktiv mit LP_LUT-Bit 3

#define LP_STEP 3
#define LP_MINY 4
#define LP_HEIGHT 5
#define LP_SEA 6
#define LP_CAPACITY 7
#define LP_QUARTS 8
#define LP_BIOMES 9
#define LP_WATER 10
#define LP_STONE 11
#define LP_DEEPSLATE 12
#define LP_BEDROCK 13
#define LP_LUT 18

#define BIOME_WORDS 8
#define TMP_WORDS 17
#define PRE_SIDE 9

struct LodPush { int jobCount; int pad[7]; };

// Oberflaechenhoehe in Voxeln (abgerundet) der Spalte vx, vz (-1..32), bilinear aus dem 9 x 9-Gitter
// (Gitterpunkt i liegt bei Voxelspalte 4 i - 1, siehe ExactLodPipeline.plan2d)
__device__ __forceinline__ int heightVox(const float* pre, int vx, int vz, int s) {
	float fx = fminf(fmaxf((vx + 1.5f) * 0.25f, 0.0f), (float)(PRE_SIDE - 1) - 1e-3f);
	float fz = fminf(fmaxf((vz + 1.5f) * 0.25f, 0.0f), (float)(PRE_SIDE - 1) - 1e-3f);
	int ix = (int)fx, iz = (int)fz;
	float tx = fx - (float)ix, tz = fz - (float)iz;
	const float* p = pre + iz * PRE_SIDE + ix;
	float h = (p[0] * (1 - tx) + p[1] * tx) * (1 - tz) + (p[PRE_SIDE] * (1 - tx) + p[PRE_SIDE + 1] * tx) * tz;
	return (int)floorf(h / (float)s);
}

// Ein Thread je Voxelspalte (32 x 32) eines Auftrags; blockIdx.y = Auftrag
extern "C" __global__ void __launch_bounds__(128) wg_lod_height(LodPush p) {
	int job = blockIdx.y;
	if (job >= p.jobCount) return;
	int t = blockIdx.x * blockDim.x + threadIdx.x;
	int4 j = gJobs[job];
	int col = j.x, keyY = j.y, out = j.z;
	int vx = t & 31, vz = t >> 5;
	int s = gLp[LP_STEP], minY = gLp[LP_MINY], height = gLp[LP_HEIGHT], sea = gLp[LP_SEA];
	int cap = gLp[LP_CAPACITY];
	const float* pre = gPre + (long long)col * (PRE_SIDE * PRE_SIDE);
	int g0 = heightVox(pre, vx, vz, s);
	int gn = min(min(heightVox(pre, vx - 1, vz, s), heightVox(pre, vx + 1, vz, s)),
		min(heightVox(pre, vx, vz - 1, s), heightVox(pre, vx, vz + 1, s)));
	int xi = vx + 1, zi = vz + 1;
	int q = (xi >> 1) + (zi >> 1) * 17;
	int leaf = gTmp[((unsigned long long)col * gLp[LP_QUARTS] + q) * TMP_WORDS];
	int bi = leaf >= 0 ? gNode[leaf * 31 + 30] : -1;
	if (bi < 0 || bi >= gLp[LP_BIOMES]) bi = 0;
	const int* B = gBiome + bi * BIOME_WORDS;
	const uint* lut = gLp[LP_LUT] ? gLut + ((unsigned long long)job * 1024 + t) * 4 : nullptr;
	int vy0 = keyY * 32;                       // erste absolute Voxelzeile des Chunks
	int top = g0 - 1;                          // oberste feste Voxelzeile
	int lo = min(gn, top);                     // darunter ist die Spalte verdeckt
	// Strukturen: gleiche Bodenhoehe wie im 3D-Pfad (vorlaeufige Oberflaeche, erster freier y); Fundament bis zum Boden dieses
	// Hoehenfelds, geraeumtes Gelaende bekommt eine Haut von 3 Voxeln (die Nachbarn sind u. U. hoeher)
	StructCol sc;
	sc.n = 0;
	int yLowS = 0, fStS = 0;
	bool hasLow = false;
	uint fAttr = 0;
	if (gLp[LP_LUT] & LPF_STRUCT) {
		int wx = (int)gColumns[col * 2] + vx * s, wz = (int)gColumns[col * 2 + 1] + vz * s;
		int G = (int)floorf(struct_preHeight(pre, (float)vx + 0.5f, (float)vz + 0.5f));
		struct_columnBegin(*gStruct, sc, wx, wz, s, G);
		if (sc.n) {
			hasLow = struct_columnLow(*gStruct, sc, yLowS, fStS);
			if (hasLow) fAttr = struct_attr(*gStruct, STRUCT_PRESENT | (uint)fStS);
			if (fAttr == 0) fAttr = (uint)gLp[LP_STONE];
			lo = min(lo, top - 3);
		}
	}
	int seaVox = (int)floorf((float)(sea - 1) / (float)s);   // oberste Wasserzeile
	for (int vy = 0; vy < 32; vy++) {
		int ay = vy0 + vy;
		int yb = ay * s;
		uint attr = 0;
		uint sv = 0;
		if (sc.n && yb >= minY && yb < minY + height) sv = struct_columnAt(*gStruct, sc, yb);
		if (sv) {
			// Strukturblock (Zustand 0 / ohne Material: raeumt das Gelaende)
			attr = (sv & 0x7fffffffu) ? struct_attr(*gStruct, sv) : 0u;
		} else if (hasLow && ay > top && yb < yLowS && yb >= yLowS - STRUCT_FOUND_MAX && yb >= minY) {
			attr = fAttr;                            // Fundament zwischen Boden und tiefstem Strukturblock
		} else if (yb >= minY && yb < minY + height) {
			if (ay <= top && ay >= lo) {
				if (yb < minY + s) attr = (uint)gLp[LP_BEDROCK];
				else if (ay == top) attr = (uint)(yb + s < sea ? B[2] : B[0]);
				else if ((top - ay) * s < 4) attr = (uint)B[1];
				else attr = (uint)(yb < 0 ? gLp[LP_DEEPSLATE] : gLp[LP_STONE]);
				if (lut && attr != (uint)gLp[LP_BEDROCK]) {
					int k = min(top - ay, 3);
					uint m = lut[k];
					if (m != 0xFFFFFFFFu && !(k == 3 && yb < 0)) attr = m;
				}
			} else if (ay > top && ay == seaVox && yb < sea) {
				attr = (uint)gLp[LP_WATER];
			}
		}
		// ein zusammenhaengender Bereich je Warp (wie wg_lod_voxels.cu); Zaehler zaehlen Ueberlauf mit
		uint active = __activemask();
		uint mask = __ballot_sync(active, attr != 0);
		if (mask != 0) {
			int leader = __ffs(mask) - 1;
			uint lane = threadIdx.x & 31;
			uint base = 0;
			if (lane == (uint)leader) base = atomicAdd(&gCounts[out], (uint)__popc(mask));
			base = __shfl_sync(active, base, leader);
			uint k = base + (uint)__popc(mask & ((1u << lane) - 1));
			if (attr == 0) continue;
			if (k < (uint)cap) gVox[(unsigned long long)out * cap + k] = make_uint4((uint)vx, (uint)vy, (uint)vz, attr);
		}
	}
}
