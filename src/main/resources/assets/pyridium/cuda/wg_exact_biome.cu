// Aus shaders/glsl/wg_exact_biome.glsl uebersetzt (CUDA-Pfad der exakten Worldgen). Gleiche Logik wie der
// bitgenau getestete Vulkan-Shader; Semantik-Hilfen in wg_prelude.cuh.
#include "wg_prelude.cuh"
#define VF_SCOPE_FILE
#include "wg_exact_common.cuh"
// Parameter: 0 Knotenanzahl, 1 Quarts je Chunk (4 * H/4 * 4), 2 Quart-Hoehe, 3-8 Wurzel-Offsets
// der sechs Klima-Aufgaben im Scratch (temperature, vegetation, continents, erosion, depth, ridges)

#define gBp ((int*)vf_b[8])

#define gNode ((int*)vf_b[9])

#define gTmp ((int*)vf_b[10])
// je Quart: Blatt, m lo, m hi, Ziel x7 (je 2)

#define gBiomeOut ((int*)vf_b[11])
#undef VF_SCOPE_FILE

struct VfKernel
{
    uint3 vf_gid;
#define VF_SCOPE_MEMBER
// wg_exact_biome.glsl – Biome je Quart (MultiNoiseBiomeSource.createResolverForChunk, 26.3):
// Pass 0 (gP.task = 0): Klima aus sechs Quart-Volumen (Chunk-Scratch), Climate.target
// (long)(v * 10000F), R-Baum-Suche ohne Kandidat -> erstes Minimum + Distanz.
// Pass 1 (gP.task = 1): je Chunk ein Thread, Fuellreihenfolge von Vanilla (Sections aufwaerts,
// x, y, z): letztes Ergebnis bleibt, wenn es die Minimaldistanz ebenfalls erreicht.

#include "wg_exact_common.cuh"

    static constexpr int VF_BLOCK = 64;

// je Quart: Biom-Index


    static constexpr int NODE_WORDS = 31;


    static constexpr int TMP_WORDS = 3 + 14;


__device__ jlong nodeMin(int n, int d) { int b = n * NODE_WORDS + d * 4; return jlong(julong(uint(gNode[b])) | (julong(uint(gNode[b + 1])) << 32)); }


__device__ jlong nodeMax(int n, int d) { int b = n * NODE_WORDS + d * 4 + 2; return jlong(julong(uint(gNode[b])) | (julong(uint(gNode[b + 1])) << 32)); }

// Node.distance: Summe der Quadrate von Parameter.distance je Dimension

__device__ jlong nodeDistance(int n, jlong t[7])
{
    jlong dist = 0LL;
    for (int d = 0; d < 7; d++)
    {
        jlong above = t[d] - nodeMax(n, d);
        jlong below = nodeMin(n, d) - t[d];
        jlong v = above > 0LL ? above : gl_max(below, 0LL);
        dist += v * v;
    }
    return dist;
}

// (long)(coord * 10000.0F): float-Produkt, dann Java-(long) (abschneiden, saettigen, NaN -> 0)

__device__ jlong quantize(float v)
{
    float p = v * 10000.0f;
    if (gl_isnan(p)) return 0LL;
    if (p >= 9.223372e18f) return 9223372036854775807LL;
    if (p <= -9.223372e18f) return -9223372036854775807LL - 1LL;
    return jlong(p);
}


    static constexpr jlong LMAX = 9223372036854775807LL;

// SubTree.search mit Kandidat -1 (null), iterativ

__device__ int searchTree(jlong t[7], jlong& outDist)
{
    int stNode[8];
    int stChild[8];
    jlong stMin[8];
    int stClosest[8];
    int sp = 0;
    stNode[0] = 0;
    stChild[0] = 0;
    stMin[0] = LMAX;
    stClosest[0] = -1;
    if (gNode[29] == 0) { outDist = nodeDistance(0, t); return 0; }
    int ret = -1;
    bool returning = false;
    int retChild = -1;
    jlong retChildDist = 0LL;
    for (int guard = 0; guard < 100000; guard++)
    {
        int node = stNode[sp];
        int cStart = gNode[node * NODE_WORDS + 28];
        int cCount = gNode[node * NODE_WORDS + 29];
        if (returning)
        {
            // Kind lieferte Blatt 'ret' (Kind war retChild mit Distanz retChildDist)
            jlong leafDist = retChild == ret ? retChildDist : nodeDistance(ret, t);
            if (stMin[sp] > leafDist) { stMin[sp] = leafDist; stClosest[sp] = ret; }
            returning = false;
        }
        bool descended = false;
        while (stChild[sp] < cCount)
        {
            int child = cStart + stChild[sp];
            stChild[sp]++;
            jlong cd = nodeDistance(child, t);
            if (stMin[sp] > cd)
            {
                if (gNode[child * NODE_WORDS + 29] == 0)
                {
                    // Blatt: search() liefert das Blatt selbst
                    if (stMin[sp] > cd) { stMin[sp] = cd; stClosest[sp] = child; }
                    continue;
                }
                // in den Teilbaum mit Kandidat = aktuellem closest
                retChild = child;
                retChildDist = cd;
                sp++;
                stNode[sp] = child;
                stChild[sp] = 0;
                stClosest[sp] = stClosest[sp - 1];
                stMin[sp] = stClosest[sp] < 0 ? LMAX : nodeDistance(stClosest[sp], t);
                descended = true;
                break;
            }
        }
        if (descended) continue;
        // Knoten fertig
        ret = stClosest[sp];
        if (sp == 0) { outDist = stMin[0]; return ret; }
        sp--;
        returning = true;
        // retChild/retChildDist gehoeren zum gerade verlassenen Kind dieses Rahmens
        retChild = stNode[sp + 1];
        retChildDist = nodeDistance(retChild, t);
    }
    outDist = LMAX;
    return -1;
}


__device__ void main_()
{
    int chunk = int(vf_gid.y);
    if (chunk >= gP.chunkCount) return;
    int quarts = gBp[1];
    int qh = gBp[2];
    if (gP.task == 0)
    {
        int q = int(vf_gid.x);
        if (q >= quarts) return;
        jlong t[7];
        for (int i = 0; i < 6; i++) t[i] = quantize(gScratch[chunk * gP.scratchFloats + gBp[3 + i] + q]);
        t[6] = 0LL;
        jlong m;
        int leaf = searchTree(t, m);
        int b = (chunk * quarts + q) * TMP_WORDS;
        gTmp[b] = leaf;
        gTmp[b + 1] = int(julong(m) & 0xFFFFFFFFULL);
        gTmp[b + 2] = int(julong(m) >> 32);
        for (int i = 0; i < 7; i++)
        {
            gTmp[b + 3 + i * 2] = int(julong(t[i]) & 0xFFFFFFFFULL);
            gTmp[b + 4 + i * 2] = int(julong(t[i]) >> 32);
        }
        return;
    }
    // Pass 1: Kette je Chunk
    if (vf_gid.x != 0) return;
    int prev = -1;
    int sections = qh / 4;
    for (int s = 0; s < sections; s++)
        for (int x = 0; x < 4; x++)
            for (int yy = 0; yy < 4; yy++)
                for (int z = 0; z < 4; z++)
                {
                    int qy = s * 4 + yy;
                    int q = qy + (x + z * 4) * qh;             // indexUnchecked(x, y, z) des Quart-Volumens
                    int b = (chunk * quarts + q) * TMP_WORDS;
                    int first = gTmp[b];
                    jlong m = jlong(julong(uint(gTmp[b + 1])) | (julong(uint(gTmp[b + 2])) << 32));
                    int res = first;
                    if (prev >= 0)
                    {
                        jlong t[7];
                        for (int i = 0; i < 7; i++)
                            t[i] = jlong(julong(uint(gTmp[b + 3 + i * 2])) | (julong(uint(gTmp[b + 4 + i * 2])) << 32));
                        if (nodeDistance(prev, t) == m) res = prev;
                    }
                    prev = res;
                    gBiomeOut[chunk * quarts + q] = gNode[res * NODE_WORDS + 30];
                }
}
#undef VF_SCOPE_MEMBER
};

extern "C" __global__ void __launch_bounds__(VfKernel::VF_BLOCK) wg_exact_biome(ExactPush p)
{
    VfKernel k;
    k.gP = p;
    k.vf_gid = make_uint3(blockIdx.x * blockDim.x + threadIdx.x, blockIdx.y, blockIdx.z);
    k.main_();
}
