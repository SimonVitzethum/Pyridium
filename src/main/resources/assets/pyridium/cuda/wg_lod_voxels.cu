// Pyridium: LOD-Chunks aus der exakten Vanilla-Dichte (ExactLodPipeline).
//
// Je Auftrag ein Pyrit-Chunk (32^3 Voxel der Stufe lod, Schritt s = 2^lod): finalDensity auf
// 34 x SY x 34 Stuetzpunkten (ein Punkt Rand in x/z, 'border' Punkte in y), y-Ursprung je Auftrag
// (relative y-Volumen). Biom je Stuetzspalte aus dem 2D-Schritt der Spalte (Klima + R-Baum wie
// Vanilla). Ausgabe: die sichtbare Haut direkt in Pyrits Puffern.
//
// Dichte und Biom sind an den Stuetzpunkten bitgleich zu Vanilla; Oberflaechenmaterial, Wasser
// und Baeume sind Naeherungen je Biom.

typedef unsigned int uint;
#define INT_MIN (-2147483647 - 1)

__device__ void* vf_b[24];

#define gScratch ((const float*)vf_b[0])  // 3D-Dichte je Auftrag
#define gLp ((const int*)vf_b[1])         // Kopf, siehe LP_*
#define gJobs ((const int4*)vf_b[2])      // je Auftrag: (Spalte, key.y, Pyrit-Index, 0)
#define gVox ((uint4*)vf_b[3])            // Pyrit: PyrVoxel[count * capacity]
#define gCounts ((uint*)vf_b[4])          // Pyrit: uint[count]
#define gBiome ((const int*)vf_b[5])      // je Biom BIOME_WORDS
#define gTmp ((const int*)vf_b[6])        // Biom-Kernel: je Stuetzspalte TMP_WORDS, Wort 0 = Blatt
#define gNode ((const int*)vf_b[7])       // R-Baum-Knoten, Wort 30 = Biom-Index
#define gSlots ((const int2*)vf_b[8])     // je Auftrag: Blockminimum x/z
#define gSlotY ((const int*)vf_b[9])      // je Auftrag: y des untersten Stuetzpunkts

#define LP_SCRATCH 0
#define LP_DENSITY 1
#define LP_SY 2
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
#define LP_SEED 14

#define BIOME_WORDS 8
// je Biom: 0 Oberseite, 1 Fuellung, 2 unter Wasser, 3 Laub, 4 Baumdichte (float-Bits, je Block^2), 5 Stamm
#define TMP_WORDS 17
#define SIDE 34

__device__ __forceinline__ uint hash3(int x, int z, uint seed) {
	uint h = (uint)x * 0x8da6b343u ^ (uint)z * 0xd8163841u ^ seed * 0x9e3779b9u;
	h ^= h >> 15;
	h *= 0x2c1b3c6du;
	h ^= h >> 12;
	h *= 0x297a2d39u;
	h ^= h >> 15;
	return h;
}

struct LodPush { int jobCount; int pad[7]; };

// Ein Thread je Voxelspalte (32 x 32) eines Auftrags; blockIdx.y = Auftrag
extern "C" __global__ void __launch_bounds__(128) wg_lod_voxels(LodPush p) {
	int job = blockIdx.y;
	if (job >= p.jobCount) return;
	int t = blockIdx.x * blockDim.x + threadIdx.x;
	if (t >= 32 * 32) return;
	int4 j = gJobs[job];
	int col = j.x, keyY = j.y, out = j.z;
	int vx = t & 31, vz = t >> 5;
	int s = gLp[LP_STEP], sy = gLp[LP_SY], minY = gLp[LP_MINY], height = gLp[LP_HEIGHT], sea = gLp[LP_SEA];
	int cap = gLp[LP_CAPACITY];
	const float* d = gScratch + (unsigned long long)job * gLp[LP_SCRATCH] + gLp[LP_DENSITY];
	int y0 = gSlotY[job];
	int xi = vx + 1, zi = vz + 1;
	int q = (xi >> 1) + (zi >> 1) * 17;   // Biom je 2 x 2 Stuetzspalten (ExactLodPipeline.BIOME_SIDE)
	int leaf = gTmp[((unsigned long long)col * gLp[LP_QUARTS] + q) * TMP_WORDS];
	int bi = leaf >= 0 ? gNode[leaf * 31 + 30] : -1;
	if (bi < 0 || bi >= gLp[LP_BIOMES]) bi = 0;
	const int* B = gBiome + bi * BIOME_WORDS;
	int2 org = gSlots[job];
	int wx = org.x + vx * s, wz = org.y + vz * s;
	float treeDensity = __int_as_float(B[4]);
	uint seed = (uint)gLp[LP_SEED];
	int yChunk0 = keyY * 32 * s;
	auto solidAt = [&](int x, int yb, int z) -> bool {
		if (yb < minY) return true;
		if (yb >= minY + height) return false;
		int yi = (yb - y0) / s;
		if (yi < 0) return true;        // unter dem Bereich: verdeckter Fels
		if (yi >= sy) return false;     // darueber: Luft
		return d[yi + (x + z * SIDE) * sy] > 0.0f;
	};
	auto waterAt = [&](int x, int yb, int z) -> bool {
		return yb < sea && yb >= minY && !solidAt(x, yb, z);
	};
	// Oberflaeche der Spalte im Dichtebereich (erste Luft ueber Fels, von oben)
	int ground = INT_MIN;
	{
		const float* c = d + (xi + zi * SIDE) * sy;
		if (!(c[sy - 1] > 0.0f)) {
			for (int yi = sy - 2; yi >= 0; yi--) {
				if (c[yi] > 0.0f) { ground = y0 + (yi + 1) * s; break; }
			}
		}
	}
	// Baeume: gejittertes Raster in Blockkoordinaten, unabhaengig von der Stufe (kein Springen beim LOD-Wechsel)
	int trunkTop = INT_MIN, crownLo = INT_MIN, crownHi = INT_MIN;
	bool trunkHere = false, coverTop = false;
	if (treeDensity > 0.0f && ground != INT_MIN && ground > sea) {
		int C = max(4, (int)(rsqrtf(treeDensity)));
		float pTree = fminf(0.9f, treeDensity * (float)(C * C));
		if (s >= 8) {
			// grobe Stufen: Kronen als Laubfarbe der Oberflaeche nach Bedeckungsgrad
			float cover = fminf(0.95f, pTree * 12.5f / (float)(C * C));
			coverTop = (hash3(wx, wz, seed ^ 0x51ed27u) & 0xffffff) * (1.0f / 16777216.0f) < cover;
		} else {
			float cx = (float)wx + 0.5f * (float)s, cz = (float)wz + 0.5f * (float)s;
			int gx = (int)floorf(cx / (float)C), gz = (int)floorf(cz / (float)C);
			float best = 1e9f;
			int bestH = 0;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int ux = gx + dx, uz = gz + dz;
					uint th = hash3(ux, uz, seed ^ 0x7ee5u);
					if ((th & 0xffff) * (1.0f / 65536.0f) >= pTree) continue;
					int tx = ux * C + (int)((th >> 16) & 0xff) % C, tz = uz * C + (int)((th >> 24) & 0xff) % C;
					if (tx >= wx && tx < wx + s && tz >= wz && tz < wz + s) trunkHere = true;
					float ex = (float)tx + 0.5f - cx, ez = (float)tz + 0.5f - cz;
					float d2 = ex * ex + ez * ez;
					if (d2 < best) { best = d2; bestH = 4 + (int)((th >> 13) % 3u); }
				}
			}
			float r = 2.3f + 0.5f * (float)s;
			if (best <= r * r) {
				crownLo = ground + bestH - 2;
				crownHi = ground + bestH + (best <= 1.6f ? 2 : 1);
				trunkTop = ground + bestH;
			} else {
				trunkHere = false;
			}
		}
	}
	for (int vy = 0; vy < 32; vy++) {
		int yb = yChunk0 + vy * s;
		if (yb < minY || yb >= minY + height) continue;
		uint attr = 0;
		if (solidAt(xi, yb, zi)) {
			bool above = solidAt(xi, yb + s, zi);
			bool exposed = !above || !solidAt(xi, yb - s, zi) || !solidAt(xi - 1, yb, zi) || !solidAt(xi + 1, yb, zi) ||
				!solidAt(xi, yb, zi - 1) || !solidAt(xi, yb, zi + 1);
			if (!exposed) {
				// Keep the whole warp at the output ballot below. Hidden cells
				// contribute no voxel, but still participate in compaction.
			} else if (yb < minY + s) {
				attr = (uint)gLp[LP_BEDROCK];
			} else if (!above) {
				attr = (uint)(yb + s < sea ? B[2] : B[0]);
			} else {
				int depth = 1;
				while (depth * s < 4 && solidAt(xi, yb + (depth + 1) * s, zi)) depth++;
				attr = (uint)(depth * s < 4 ? B[1] : (yb < 0 ? gLp[LP_DEEPSLATE] : gLp[LP_STONE]));
			}
		} else if (waterAt(xi, yb, zi)) {
			if (!waterAt(xi, yb + s, zi) && !solidAt(xi, yb + s, zi)) attr = (uint)gLp[LP_WATER];
		} else if (crownLo != INT_MIN) {
			// Krone (Naeherung, an Chunkgrenzen ohne Nachbarwissen): Stamm und runde Krone
			if (yb + s > crownLo && yb < crownHi) attr = (uint)B[3];
			else if (trunkHere && s == 1 && yb >= ground && yb < trunkTop) attr = (uint)B[5];
		}
		// Reserve one contiguous range per warp, rather than contending on
		// the same chunk counter for every exposed voxel. Counts still include
		// overflow, allowing Pyrit to retry with a larger capacity.
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
