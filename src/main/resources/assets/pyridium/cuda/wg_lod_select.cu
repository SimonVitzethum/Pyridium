// Pyridium: Bandauswahl der LOD-Chunks ganz auf der GPU (kein Readback zum Host).
//
// Eingabe: Kandidaten (Spalte, key.y, Pyrit-Index) und je Spalte die vorlaeufige Oberflaeche
// (9 x 9 Punkte im 2D-Scratch). Ausgabe: die Chunks, die das Band um die Oberflaeche (oder den
// Meeresspiegel) schneiden, dicht vorn in Jobs/Slots/SlotY; alle uebrigen SlotY = INT_MIN
// (die 3D-Kernel und der Voxelkernel ueberspringen sie).
// Ein Block (1024 Threads), bis 4096 Kandidaten.

typedef unsigned int uint;
#define INT_MIN (-2147483647 - 1)

__device__ void* vf_b[8];

#define sCand ((const int4*)vf_b[0])      // (Spalte, key.y, Pyrit-Index, 0)
#define sLp ((const int*)vf_b[1])
#define sJobs ((int4*)vf_b[2])
#define sSlots ((int2*)vf_b[3])
#define sSlotY ((int*)vf_b[4])
#define sColumns ((const int*)vf_b[5])    // je Spalte Blockminimum x, z
#define sBand ((const int2*)vf_b[6])      // je Spalte (lo, hi), wg_lod_band

#define LP_STEP 3
#define LP_SEA 6
#define LP_BORDER 17

struct SelPush { int n, bandBelow, bandAbove, pad[5]; };

extern "C" __global__ void __launch_bounds__(1024) wg_lod_select(SelPush p) {
	__shared__ int warpCount[32];
	int t = threadIdx.x;
	int s = sLp[LP_STEP], sea = sLp[LP_SEA], border = sLp[LP_BORDER];
	int lane = t & 31, warp = t >> 5;
	int base = 0;
	for (int i0 = 0; i0 < p.n; i0 += 1024) {
		int i = i0 + t;
		bool keep = false;
		int4 c = make_int4(0, 0, 0, 0);
		if (i < p.n) {
			c = sCand[i];
			int2 bd = sBand[c.x];
			int blo = bd.x, bhi = bd.y;
			int cy0 = c.y * 32 * s, cy1 = cy0 + 32 * s;
			keep = cy1 > blo && cy0 < bhi;
		}
		uint m = __ballot_sync(0xffffffffu, keep);
		if (lane == 0) warpCount[warp] = __popc(m);
		__syncthreads();
		int off = base;
		for (int w = 0; w < warp; w++) off += warpCount[w];
		off += __popc(m & ((1u << lane) - 1));
		if (keep) {
			sJobs[off] = c;
			sSlots[off] = make_int2(sColumns[c.x * 2], sColumns[c.x * 2 + 1]);
			sSlotY[off] = c.y * 32 * s - border * s;
		}
		__syncthreads();
		int total = 0;
		for (int w = 0; w < 32; w++) total += warpCount[w];
		base += total;
		__syncthreads();
	}
	// uebrige Plaetze abwaehlen
	for (int i = base + t; i < p.n; i += 1024) sSlotY[i] = INT_MIN;
}
