// wg_struct_envfill.cu - Produktions-Anbieter der Strukturumgebung (Hoehen + Biome) aus der echten Gelaendefunktion der LOD-Pipeline.
//
// Modul wie wg_struct_tile.cu (gleiche Bindungen 0..12, siehe wg_struct_env_tile.cuh), dazu:
//   13 Spalten der Hoehenebene (int2 je Spalte: Block-Ursprung x, z)   14 Spalten der Klimaebene (int2)
//   15 Zaehler (int[0] = Hoehenspalten, int[1] = Klimaspalten, int[2] = Ueberlauf)
//   16 Scratch des Hoehenplans (floats je Spalte: scrF, vorlaeufige Oberflaeche ab preOff, 17 x 17 Punkte im Abstand 8)
//   17 Biom-Zwischenpuffer (wg_exact_biome: je Spalte QUARTS * 17 Worte, Wort 0 = R-Baum-Blatt)   18 R-Baum-Knoten
//
// Ablauf (Java: StructureEnv): wg_struct_place legt die Kacheln an -> wg_struct_env_cols macht aus jeder Kachel Spalten eines eigenen
// 2D-Plans (Hoehe: 128 x 128 Bloecke je Spalte, Klima: 12 x 12 Quarts je Kandidat) -> Plan laeuft (wg_exact_*, wie ExactLodPipeline
// plan2d) -> wg_struct_env_fill_h / _c schreiben Hoehen (bilinear, Oberflaeche = max(floor(h), Meeresspiegel), Boden = floor(h)) und
// Biom-Kachel (Biom aus dem R-Baum, Wert gNode[blatt * 31 + 30], fuer alle y gleich) in die Kacheln. Danach laeuft wg_struct_gen.
// Spalten werden in Stapeln gerechnet; die Fuell-Kerne nehmen nur die Spalten ihres Stapels.
#include "wg_struct_common.cuh"
#include "wg_struct_env_tile.cuh"
#include "wg_struct_gen.cuh"

#define ENV_SUB 128        // Kantenlaenge einer Hoehenspalte in Bloecken (16 Zellen zu 8)
#define ENV_PRE_SIDE 17
#define ENV_QUARTS 144     // 12 x 12 Klima-Quarts
#define ENV_TMP_WORDS 17

struct EnvPush { int c0, n, scrF, preOff, capH, capC, capCand, pad1; };

// Kachel -> Spalten. Ein Thread je Kandidat. Setzt die Kachel gueltig (die Daten folgen im selben Stream vor wg_struct_gen).
extern "C" __global__ void __launch_bounds__(64) wg_struct_env_cols(EnvPush p) {
    const int* cands = (const int*)vf_b[1];
    int n = min(cands[0], p.capCand);             
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= n) return;
    int off = cands[CAND_HDR + i * 4 + 3];
    if (off < 0) return;
    int* t = env_tileAt(off);
    int* cnt = (int*)vf_b[15];
    int2* colH = (int2*)vf_b[13];
    int2* colC = (int2*)vf_b[14];
    int hw = t[3], ns = (hw + ENV_SUB - 1) / ENV_SUB;
    int first = atomicAdd(&cnt[0], ns * ns);
    int c = atomicAdd(&cnt[1], 1);
    t[13] = first; t[14] = ns; t[15] = c;
    if (first + ns * ns > p.capH || c >= p.capC) { atomicOr(&cnt[2], 1); t[13] = -1; return; }
    for (int b = 0; b < ns; b++)
        for (int a = 0; a < ns; a++) colH[first + b * ns + a] = make_int2(t[1] + a * ENV_SUB, t[2] + b * ENV_SUB);
    colC[c] = make_int2(t[7] * 4, t[8] * 4);
    t[0] = 1;
}

// Hoehen: Block je Kandidat, Threads ueber die Kachelzellen; nur Spalten [c0, c0 + n) dieses Stapels
extern "C" __global__ void __launch_bounds__(256) wg_struct_env_fill_h(EnvPush p) {
    const StructTables& T = *structTables();
    const int* cands = (const int*)vf_b[1];
    int cn = min(cands[0], p.capCand);
    const float* scr = (const float*)vf_b[16];
    int sea = (int)T.seaLevel;
    for (int i = blockIdx.x; i < cn; i += gridDim.x) {
        int off = cands[CAND_HDR + i * 4 + 3];
        if (off < 0) continue;
        int* t = env_tileAt(off);
        if (t[13] < 0) continue;
        int hw = t[3], ns = t[14], first = t[13];
        if (first + ns * ns <= p.c0 || first >= p.c0 + p.n) continue;
        short* surf = (short*)(t + t[5]);
        short* flr = t[6] >= 0 ? (short*)(t + t[6]) : nullptr;
        for (int k = threadIdx.x; k < hw * hw; k += blockDim.x) {
            int lx = k % hw, lz = k / hw;
            int col = first + (lz / ENV_SUB) * ns + (lx / ENV_SUB);
            if (col < p.c0 || col >= p.c0 + p.n) continue;
            const float* pre = scr + (size_t)(col - p.c0) * p.scrF + p.preOff;
            int ux = lx % ENV_SUB, uz = lz % ENV_SUB;
            int ix = ux >> 3, iz = uz >> 3;
            float tx = (float)(ux & 7) * 0.125f, tz = (float)(uz & 7) * 0.125f;
            const float* q = pre + iz * ENV_PRE_SIDE + ix;
            float h = (q[0] * (1.0f - tx) + q[1] * tx) * (1.0f - tz) + (q[ENV_PRE_SIDE] * (1.0f - tx) + q[ENV_PRE_SIDE + 1] * tx) * tz;
            int hf = (int)floorf(h);
            surf[k] = (short)max(hf, sea);
            if (flr) flr[k] = (short)hf;
        }
    }
}

// Biome: Block je Kandidat, Threads ueber 12 x 12 Quarts x alle y-Quarts (Oberflaechenbiom fuer alle y)
extern "C" __global__ void __launch_bounds__(256) wg_struct_env_fill_c(EnvPush p) {
    const int* cands = (const int*)vf_b[1];
    int cn = min(cands[0], p.capCand);
    const int* tmp = (const int*)vf_b[17];
    const int* node = (const int*)vf_b[18];
    for (int i = blockIdx.x; i < cn; i += gridDim.x) {
        int off = cands[CAND_HDR + i * 4 + 3];
        if (off < 0) continue;
        int* t = env_tileAt(off);
        if (t[13] < 0) continue;
        int c = t[15];
        if (c < p.c0 || c >= p.c0 + p.n) continue;
        unsigned char* bio = (unsigned char*)(t + t[12]);
        int bw = t[9], bqh = t[11];
        for (int k = threadIdx.x; k < bw * bw * bqh; k += blockDim.x) {
            int ix = k % bw, iz = (k / bw) % bw;
            int leaf = tmp[((size_t)(c - p.c0) * ENV_QUARTS + iz * bw + ix) * ENV_TMP_WORDS];
            bio[k] = leaf >= 0 ? (unsigned char)node[leaf * 31 + 30] : (unsigned char)255;
        }
    }
}

// Hoehenraster eines Blocks (Strukturschicht): Spalten zeilenweise (n = ncols * ncols), je Spalte 16 x 16 Punkte (Abstand 8) in ein
// ncols * 16 breites int16-Raster (vf_b[13] = Ausgabe); Wert = floor(vorlaeufige Oberflaeche), gleiche Funktion wie das Gelaende.
// Push: c0 = 0, n, scrF, preOff, capH = ncols (Spalten je Zeile)
extern "C" __global__ void __launch_bounds__(256) wg_struct_env_grid(EnvPush p) {
    const float* scr = (const float*)vf_b[16];
    short* grid = (short*)vf_b[13];
    int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= p.n * 256) return;
    int col = idx >> 8, pt = idx & 255, px = pt & 15, pz = pt >> 4;
    int ncols = p.capH;
    int a = col % ncols, b = col / ncols;
    const float* pre = scr + (size_t)col * p.scrF + p.preOff;
    int gw = ncols * 16;
    grid[(size_t)(b * 16 + pz) * gw + a * 16 + px] = (short)(int)floorf(pre[pz * ENV_PRE_SIDE + px]);
}

// Biomraster eines Blocks (Strukturschicht, Tint): je Klimaspalte (12 x 12 Quarts = 48 Bloecke) das Biom in der Mitte (Quart 6, 6).
// Bindungen: 13 = Ausgabe (uint8), 17 = Biom-Zwischenpuffer, 18 = R-Baum-Knoten. Push: c0 = Ausgabe-Offset, n = Spalten dieses Stapels
extern "C" __global__ void __launch_bounds__(256) wg_struct_env_bgrid(EnvPush p) {
    unsigned char* out = (unsigned char*)vf_b[13];
    const int* tmp = (const int*)vf_b[17];
    const int* node = (const int*)vf_b[18];
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= p.n) return;
    int leaf = tmp[((size_t)i * ENV_QUARTS + 6 * 12 + 6) * ENV_TMP_WORDS];
    out[p.c0 + i] = leaf >= 0 ? (unsigned char)node[leaf * 31 + 30] : (unsigned char)255;
}

// Ring-Ebenen (Strongholds): je Ring 57 x 57 Quarts bei y = 0 um die Ausgangsposition (Ursprung ncx - 28, ncz - 28 mit ncx = ((ix << 4) + 8) >> 2).
// wg_struct_ring_cols: Ring i -> Spalte (Block-Ursprung); danach laeuft der Klimaplan (57 x 57 Punkte je Spalte), wg_struct_ring_fill schreibt die Ebenen.
// Bindungen: 12 = Ebenen (uint8), 13 = Spalten (int2), 17 = Biom-Zwischenpuffer (je Spalte 3249 * 17 Worte), 18 = R-Baum-Knoten. Push: c0, n, n Ringe gesamt
extern "C" __global__ void __launch_bounds__(64) wg_struct_ring_cols(EnvPush p) {
    const StructTables& T = *structTables();
    int2* col = (int2*)vf_b[13];
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= p.n) return;
    int ix = T.ringPos[i * 2], iz = T.ringPos[i * 2 + 1];
    int ncx = ((ix << 4) + 8) >> 2, ncz = ((iz << 4) + 8) >> 2;
    col[i] = make_int2((ncx - 28) * 4, (ncz - 28) * 4);
}

extern "C" __global__ void __launch_bounds__(256) wg_struct_ring_fill(EnvPush p) {
    unsigned char* planes = (unsigned char*)vf_b[12];
    const int* tmp = (const int*)vf_b[17];
    const int* node = (const int*)vf_b[18];
    size_t g = (size_t)blockIdx.x * blockDim.x + threadIdx.x;
    if (g >= (size_t)p.n * 3249) return;
    int ring = (int)(g / 3249), k = (int)(g % 3249);
    int leaf = tmp[(g) * ENV_TMP_WORDS];
    planes[(size_t)(p.c0 + ring) * 3249 + k] = leaf >= 0 ? (unsigned char)node[leaf * 31 + 30] : (unsigned char)255;
}
