// Pyridium: Band je Spalte (Hoehe der vorlaeufigen Oberflaeche -> [lo, hi) in Bloecken), direkt nach
// dem 2D-Schritt. Je Stufe ein eigener Puffer, weil sich alle Stufen den 2D-Scratch teilen.

__device__ void* vf_b[4];
#define bPreCopy ((float*)vf_b[3])     // je Spalte die 9 x 9 Oberflaechenwerte (Hoehenfeld ferner Stufen)

#define bPre ((const float*)vf_b[0])   // 2D-Scratch
#define bLp ((const int*)vf_b[1])
#define bOut ((int2*)vf_b[2])          // je Spalte (lo, hi)

#define LP_SEA 6
#define LP_PRE_STRIDE 15
#define LP_PRE_OFFSET 16
#define PRE 81

struct BandPush { int n, bandBelow, bandAbove, pad[5]; };

extern "C" __global__ void __launch_bounds__(64) wg_lod_band(BandPush p) {
	int c = blockIdx.x * blockDim.x + threadIdx.x;
	if (c >= p.n) return;
	const float* f = bPre + (long long)c * bLp[LP_PRE_STRIDE] + bLp[LP_PRE_OFFSET];
	float lo = 3.4e38f, hi = -3.4e38f;
	for (int k = 0; k < PRE; k++) { lo = fminf(lo, f[k]); hi = fmaxf(hi, f[k]); }
	int blo = (int)floorf(lo) - p.bandBelow, bhi = (int)ceilf(hi) + p.bandAbove;
	if ((int)floorf(lo) < bLp[LP_SEA]) bhi = max(bhi, bLp[LP_SEA] + 1);
	bOut[c] = make_int2(blo, bhi);
	for (int k = 0; k < PRE; k++) bPreCopy[(long long)c * PRE + k] = f[k];
}
