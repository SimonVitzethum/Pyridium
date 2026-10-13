// wg_struct_alias.cuh - Pool-Aliase (PoolAliasLookup.create) fuer Jigsaw-Strukturen (Trial Chambers u. a.).
// Zufall: RandomSource.create(seed).forkPositional().at(pos) = LegacyRandomSource (26.3).
// Kodierung (aliasData ab ST_ALIAS_START, ST_ALIAS_COUNT Bindungen), jede Bindung: [len, type, ...]
//   type 0 direct:       alias, target
//   type 1 random:       alias, n, (target, weight)*n
//   type 2 random_group: nGroups, (weight, nBindings, binding*)*nGroups
#pragma once
#include "wg_struct_jigsaw.cuh"

__device__ __forceinline__ void jg_aliasPut(JigCtx& c, int alias, int target) {
    for (int i = 0; i < c.nAlias; i++) if (c.aliasFrom[i] == alias) { c.aliasTo[i] = target; return; }
    if (c.nAlias < 16) { c.aliasFrom[c.nAlias] = alias; c.aliasTo[c.nAlias] = target; c.nAlias++; }
}

// direct/random: ohne Rekursion; random_group ruft diese fuer seine Unterbindungen (eine Ebene, wie in den Vanilla-Daten)
__device__ void jg_aliasLeaf(const int* b, LRand& r, JigCtx& c) {
    int type = b[1];
    if (type == 0) {
        jg_aliasPut(c, b[2], b[3]);
    } else if (type == 1) {
        int n = b[3], total = 0;
        for (int i = 0; i < n; i++) total += b[4 + i * 2 + 1];
        int sel = lr_nextInt(r, total);
        for (int i = 0; i < n; i++) {
            sel -= b[4 + i * 2 + 1];
            if (sel < 0) { jg_aliasPut(c, b[2], b[4 + i * 2]); break; }
        }
    }
}

__device__ const int* jg_aliasBinding(const int* b, LRand& r, JigCtx& c) {
    int len = b[0], type = b[1];
    if (type != 2) {
        jg_aliasLeaf(b, r, c);
    } else {
        int nGroups = b[2];
        const int* g = b + 3;
        int total = 0;
        const int* gp = g;
        for (int i = 0; i < nGroups; i++) {
            total += gp[0];
            int nb = gp[1];
            const int* q = gp + 2;
            for (int k = 0; k < nb; k++) q += q[0];
            gp = q;
        }
        int sel = lr_nextInt(r, total);
        gp = g;
        for (int i = 0; i < nGroups; i++) {
            sel -= gp[0];
            int nb = gp[1];
            const int* q = gp + 2;
            if (sel < 0) {
                for (int k = 0; k < nb; k++) { jg_aliasLeaf(q, r, c); q += q[0]; }
                break;
            }
            for (int k = 0; k < nb; k++) q += q[0];
            gp = q;
        }
    }
    return b + len;
}

__device__ void jg_resolveAliases(const StructTables& T, const int* S, JigCtx& c, int px, int py, int pz) {
    int n = S[ST_ALIAS_COUNT];
    c.nAlias = 0;
    if (n == 0) return;
    LRand base;
    lr_setSeed(base, T.seed);
    jlong posSeed = lr_nextLong(base);                // forkPositional(): LegacyPositionalRandomFactory(nextLong())
    LRand r;
    lr_setSeed(r, st_mthSeed(px, py, pz) ^ posSeed);  // .at(pos)
    const int* b = T.aliasData + S[ST_ALIAS_START];
    for (int i = 0; i < n; i++) b = jg_aliasBinding(b, r, c);
}
