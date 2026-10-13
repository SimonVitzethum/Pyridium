// wg_struct_jigsaw.cuh - generischer Jigsaw-Loeser auf der GPU.
//
// Portiert JigsawStructure.findGenerationPoint + JigsawPlacement (26.3) in einen Thread je Strukturstart:
// Pools (gewichtet, mit Fallback), Anschluss-Namen (name/target), Rotationen, Joint-Typ, Tiefenlimit,
// max_distance_from_center, Kollisionsprueffung ueber Freiraum-Boxen, rigid/terrain_matching,
// Expansion-Hack, Pool-Aliase, Platzierungs- und Auswahlprioritaeten.
//
// Die Umgebung (Hoehe, Biom) liefert der einbindende Code als Geraetefunktionen
//   int penv_height(const StructTables&, int x, int z, int hm)          // wie ChunkGenerator.getBaseHeight
//   int penv_biome(const StructTables&, int qx, int qy, int qz)         // Biom-Index (Quart-Koordinaten)
// (siehe wg_struct_env_*.cuh). Zufall: Legacy-LCG in Vanillas Aufrufreihenfolge.
#pragma once
#include "wg_struct_common.cuh"

__device__ int penv_height(const StructTables& T, int x, int z, int hm);
__device__ int penv_biome(const StructTables& T, int qx, int qy, int qz);
// Kandidaten-Haken (Umgebungs-Kacheln): penv_initCandidate reserviert und beschreibt die Kachel eines Kandidaten (place-Kernel) und gibt ihren
// Offset zurueck (-1: keine); penv_selectCandidate macht sie fuer den gen-Thread tid aktuell; penv_ringBiome: Biom bei y = 0 fuer die Ring-Suche.
__device__ int penv_initCandidate(const StructTables& T, const int* S, int cx, int cz);
__device__ void penv_selectCandidate(int tid, int tileOffset);
__device__ int penv_ringBiome(const StructTables& T, int set, int ring, int qx, int qz, int ncx, int ncz);

#define JG_MAXP 1024
#define JG_MAXQ 512
#define JG_MAXF 512
#define JG_MAXN 1024
#define JG_MAXJIG 96
#define JG_MAXPOOL 1536
#define STRUCT_SCRATCH_INTS (JG_MAXP * PIECE_W + JG_MAXQ * 4 + JG_MAXF * 8 + JG_MAXN * 8 + JG_MAXJIG * 2 + JG_MAXPOOL * 2 + 256)

#ifdef STRUCT_STATS
#define STAT(i, n) atomicAdd(((unsigned long long*)vf_b[5]) + (i), (unsigned long long)(n))
#else
#define STAT(i, n) do {} while (0)
#endif

struct JigV { int x, y, z, front, top, joint, name, pool, target, pprio, sprio, fin; };

struct JigCtx {
    const StructTables* T;
    LRand* rnd;
    int* pieces; int np;
    int* queue; int nq;
    int* frees; int nf;
    int* nodes; int nn;
    int* lstSrc;
    int* lstTgt;
    int* lstPool;
    int* aliasFrom; int* aliasTo; int nAlias;   // je 16 ints im Scratch (kleiner GPU-Stack)
    int maxDepth, expansion;
    int minY, height;
    int structIdx;
    int overflow;
    unsigned int hseed;
};

__device__ __forceinline__ const int* EL(const StructTables& T, int e) { return T.elems + e * ELEM_W; }
__device__ __forceinline__ const int* TP(const StructTables& T, int t) { return T.templates + t * TPL_W; }
__device__ __forceinline__ const int* PL(const StructTables& T, int p) { return T.pools + p * POOL_W; }

__device__ __forceinline__ int jg_alias(const JigCtx& c, int pool) {
    for (int i = 0; i < c.nAlias; i++) if (c.aliasFrom[i] == pool) return c.aliasTo[i];
    return pool;
}

__device__ __forceinline__ void jg_shuffle(LRand& r, int* a, int n) {
    for (int i = n; i > 1; i--) {
        int swapTo = lr_nextInt(r, i);
        int t = a[i - 1];
        a[i - 1] = a[swapTo];
        a[swapTo] = t;
    }
}

// Jigsaw-Block eines Elements: g = Tabellenindex (Vorlagen-Jigsaw) oder -1 = synthetischer Feature-Jigsaw
__device__ JigV jg_load(const StructTables& T, int g, int rot, int px, int py, int pz) {
    JigV v;
    if (g < 0) {
        v.x = px; v.y = py; v.z = pz; v.front = 0; v.top = 3; v.joint = 0; v.name = -1;
        v.pool = (int)T.resv[0]; v.target = (int)T.resv[1]; v.pprio = 0; v.sprio = 0; v.fin = 0;
        return v;
    }
    const int* J = T.jigsaws + g * JIG_W;
    int rx, rz;
    rotXZ(J[JG_X], J[JG_Z], rot, rx, rz);
    v.x = rx + px; v.y = J[JG_Y] + py; v.z = rz + pz;
    v.front = dirRotate(J[JG_FRONT], rot);
    v.top = dirRotate(J[JG_TOP], rot);
    v.joint = J[JG_JOINT]; v.name = J[JG_NAME]; v.pool = J[JG_POOL]; v.target = J[JG_TARGET];
    v.pprio = J[JG_PPRIO]; v.sprio = J[JG_SPRIO]; v.fin = J[JG_FINAL];
    return v;
}

// StructurePoolElement.getShuffledJigsawBlocks: Liste mischen (Util.shuffle), stabil nach Auswahlprioritaet absteigend
__device__ int jg_jigList(const StructTables& T, int elem, LRand& r, int* out) {
    const int* E = EL(T, elem);
    int kind = E[EL_KIND];
    // ListPoolElement: Jigsaws des ersten Elements (verschachtelte Listen iterativ aufloesen)
    for (int guard = 0; kind == 2 && guard < 4; guard++) {
        if (E[EL_SUB_COUNT] == 0) return 0;
        E = EL(T, T.subElems[E[EL_SUB_START]]);
        kind = E[EL_KIND];
    }
    if (kind == 3) {
        out[0] = -1;   // ein Feature-Jigsaw (keine Zufallsaufrufe: List.of)
        return 1;
    }
    if (kind != 1) return 0;
    const int* P = TP(T, E[EL_TEMPLATE]);
    int n = P[TP_JIGCOUNT];
    if (n > JG_MAXJIG) n = JG_MAXJIG;
    for (int i = 0; i < n; i++) out[i] = P[TP_JIG] + i;
    jg_shuffle(r, out, n);
    // stabile Insertion-Sortierung, hoechste selection_priority zuerst
    for (int i = 1; i < n; i++) {
        int g = out[i];
        int pr = T.jigsaws[g * JIG_W + JG_SPRIO];
        int j = i - 1;
        while (j >= 0 && T.jigsaws[out[j] * JIG_W + JG_SPRIO] < pr) { out[j + 1] = out[j]; j--; }
        out[j + 1] = g;
    }
    return n;
}

__device__ BBox jg_tplBox(const StructTables& T, int tpl, int px, int py, int pz, int rot) {
    const int* P = TP(T, tpl);
    int sx = P[TP_SX], sy = P[TP_SY], sz = P[TP_SZ];
    int ax, az, bx, bz;
    rotXZ(0, 0, rot, ax, az);
    rotXZ(sx - 1, sz - 1, rot, bx, bz);
    BBox b;
    b.x0 = min(ax, bx) + px; b.x1 = max(ax, bx) + px;
    b.z0 = min(az, bz) + pz; b.z1 = max(az, bz) + pz;
    b.y0 = py; b.y1 = py + sy - 1;
    return b;
}

// StructurePoolElement.getBoundingBox
__device__ BBox jg_elemBox(const StructTables& T, int elem, int px, int py, int pz, int rot) {
    const int* E = EL(T, elem);
    int kind = E[EL_KIND];
    if (kind == 1) return jg_tplBox(T, E[EL_TEMPLATE], px, py, pz, rot);
    if (kind == 2) {
        // Liste: Umschliessende Box der Unterelemente (nur einzelne Vorlagen/Features, wie in den Vanilla-Daten)
        BBox r = {SMAXI, SMAXI, SMAXI, SMINI, SMINI, SMINI};
        for (int i = 0; i < E[EL_SUB_COUNT]; i++) {
            const int* SE = EL(T, T.subElems[E[EL_SUB_START] + i]);
            if (SE[EL_KIND] == 0) continue;
            BBox b;
            if (SE[EL_KIND] == 1) b = jg_tplBox(T, SE[EL_TEMPLATE], px, py, pz, rot);
            else { b.x0 = px; b.y0 = py; b.z0 = pz; b.x1 = px; b.y1 = py; b.z1 = pz; }
            r.x0 = min(r.x0, b.x0); r.y0 = min(r.y0, b.y0); r.z0 = min(r.z0, b.z0);
            r.x1 = max(r.x1, b.x1); r.y1 = max(r.y1, b.y1); r.z1 = max(r.z1, b.z1);
        }
        return r;
    }
    // Feature: Groesse 0 -> Box aus einem Block
    BBox b = {px, py, pz, px, py, pz};
    return b;
}

__device__ __forceinline__ int jg_projRigid(const StructTables& T, int elem) { return EL(T, elem)[EL_PROJ]; }

// --- Freiraum (VoxelShape-Ersatz): Basis-Box [min, max) minus Liste platzierter Boxen ---
__device__ int jg_freeNew(JigCtx& c, int x0, int y0, int z0, int x1, int y1, int z1) {
    if (c.nf >= JG_MAXF) { c.overflow = 1; return c.nf - 1; }
    int* f = c.frees + c.nf * 8;
    f[0] = x0; f[1] = y0; f[2] = z0; f[3] = x1; f[4] = y1; f[5] = z1; f[6] = -1; f[7] = 0;
    return c.nf++;
}
__device__ void jg_freeSub(JigCtx& c, int fi, const BBox& b) {
    if (c.nn >= JG_MAXN) { c.overflow = 1; return; }
    int* n = c.nodes + c.nn * 8;
    n[0] = b.x0; n[1] = b.y0; n[2] = b.z0; n[3] = b.x1; n[4] = b.y1; n[5] = b.z1;
    n[6] = c.frees[fi * 8 + 6];
    n[7] = fi;                       // Besitzer (fuer den warp-parallelen Test in jg_freeFits)
    c.frees[fi * 8 + 6] = c.nn++;
}
// !Shapes.joinIsNotEmpty(free, box.deflate(0.25), ONLY_SECOND): Box liegt in der Basis und trifft keinen abgezogenen Block
__device__ bool jg_freeFits(const JigCtx& c, int fi, const BBox& b) {
    STAT(3, 1);
    const int* f = c.frees + fi * 8;
    if (b.x0 < f[0] || b.y0 < f[1] || b.z0 < f[2] || b.x1 + 1 > f[3] || b.y1 + 1 > f[4] || b.z1 + 1 > f[5]) return false;
    for (int n = f[6]; n >= 0; n = c.nodes[n * 8 + 6]) {
        const int* s = c.nodes + n * 8;
        STAT(4, 1);
        if (b.x0 <= s[3] && b.x1 >= s[0] && b.y0 <= s[4] && b.y1 >= s[1] && b.z0 <= s[5] && b.z1 >= s[2]) return false;
    }
    return true;
}

__device__ int jg_addPiece(JigCtx& c, int elem, int x, int y, int z, int rot, int ground, const BBox& bb, int depth) {
    if (c.np >= JG_MAXP) { c.overflow = 1; return c.np - 1; }
    int* p = c.pieces + c.np * PIECE_W;
    const int* E = EL(*c.T, elem);
    for (int i = 0; i < PIECE_W; i++) p[i] = 0;
    p[PC_KIND] = PK_POOL; p[PC_ELEM] = elem; p[PC_TPL] = E[EL_KIND] == 1 ? E[EL_TEMPLATE] : -1;
    p[PC_X] = x; p[PC_Y] = y; p[PC_Z] = z; p[PC_ROT] = rot; p[PC_PROJ] = E[EL_PROJ]; p[PC_GROUND] = ground;
    p[PC_MINX] = bb.x0; p[PC_MINY] = bb.y0; p[PC_MINZ] = bb.z0; p[PC_MAXX] = bb.x1; p[PC_MAXY] = bb.y1; p[PC_MAXZ] = bb.z1;
    p[PC_DEPTH] = depth; p[PC_FLAGS] = (E[EL_FLAGS] & 1) | (E[EL_PROJ] == 0 ? 2 : 0); p[PC_STRUCT] = c.structIdx; p[PC_PROCS] = E[EL_PROCS];
    return c.np++;
}
__device__ __forceinline__ BBox jg_pieceBox(const int* p) {
    BBox b = {p[PC_MINX], p[PC_MINY], p[PC_MINZ], p[PC_MAXX], p[PC_MAXY], p[PC_MAXZ]};
    return b;
}

__device__ void jg_queuePush(JigCtx& c, int piece, int fr, int depth, int prio) {
    if (c.nq >= JG_MAXQ) { c.overflow = 1; return; }
    int* q = c.queue + c.nq * 4;
    q[0] = piece; q[1] = fr; q[2] = depth; q[3] = prio;
    c.nq++;
}
// SequencedPriorityIterator: hoechste Prioritaet, innerhalb FIFO
__device__ bool jg_queuePop(JigCtx& c, int& piece, int& fr, int& depth) {
    if (c.nq == 0) return false;
    int best = 0;
    for (int i = 1; i < c.nq; i++) if (c.queue[i * 4 + 3] > c.queue[best * 4 + 3]) best = i;
    piece = c.queue[best * 4]; fr = c.queue[best * 4 + 1]; depth = c.queue[best * 4 + 2];
    for (int i = best; i + 1 < c.nq; i++) {
        c.queue[i * 4] = c.queue[(i + 1) * 4]; c.queue[i * 4 + 1] = c.queue[(i + 1) * 4 + 1];
        c.queue[i * 4 + 2] = c.queue[(i + 1) * 4 + 2]; c.queue[i * 4 + 3] = c.queue[(i + 1) * 4 + 3];
    }
    c.nq--;
    return true;
}

// JigsawBlock.canAttach(source, target)
__device__ __forceinline__ bool jg_canAttach(const JigV& s, const JigV& t) {
    return s.front == dirOpposite(t.front) && (s.joint == 0 || s.top == t.top) && (t.name < 0 || s.target == t.name);
}

// Pool gueltig fuer Platzierung? (size()==0 && !is(EMPTY) -> uebersprungen)
__device__ __forceinline__ bool jg_poolUsable(const StructTables& T, int p) {
    if (p < 0) return false;
    const int* P = PL(T, p);
    if (!P[PL_EXISTS]) return false;
    return P[PL_COUNT] > 0 || P[PL_IS_EMPTYKEY];
}

// Zaehlerbasierter Zufall (kein sequentieller Zustand): Rang eines Versuchs aus (Struktur-Seed, Quellteil, Quell-Jigsaw, Versuch)
__device__ __forceinline__ unsigned int jg_hash(unsigned int a, unsigned int b, unsigned int c3, unsigned int d) {
    unsigned int h = a * 0x9E3779B1u ^ (b + 0x7F4A7C15u) * 0x85EBCA77u ^ (c3 + 0x165667B1u) * 0xC2B2AE3Du ^ (d + 0x27D4EB2Fu) * 0x9E3779B9u;
    h ^= h >> 16; h *= 0x7feb352du; h ^= h >> 15; h *= 0x846ca68bu; h ^= h >> 16;
    return h;
}

// Jigsaw-Block-Liste eines Elements ohne Mischen: Rueckgabe Anzahl, *base = erster Tabellenindex (-1: ein Feature-Jigsaw)
__device__ int jg_jigRange(const StructTables& T, int elem, int* base) {
    const int* E = EL(T, elem);
    int kind = E[EL_KIND];
    for (int guard = 0; kind == 2 && guard < 4; guard++) {
        if (E[EL_SUB_COUNT] == 0) return 0;
        E = EL(T, T.subElems[E[EL_SUB_START]]);
        kind = E[EL_KIND];
    }
    if (kind == 3) { *base = -1; return 1; }
    if (kind != 1) return 0;
    const int* P = TP(T, E[EL_TEMPLATE]);
    *base = P[TP_JIG];
    return min(P[TP_JIGCOUNT], JG_MAXJIG);
}

struct JigAtt {
    int tel, tRot, rawX, rawZ, tPosY, tGround;
    BBox tBB;
    bool empty;      // EmptyPoolElement: beendet die Kandidatenliste (nichts platzieren)
};

// Ein Versuch (Element x Rotation x Start-Jigsaw): passt das Ziel an den Quell-Jigsaw und in den Freiraum? Reine Lesezugriffe (Lanes laufen divergent).
__device__ bool jg_attempt(const JigCtx& c, const JigV& sj, int tjx, int tjy, int tjz, bool sRigid, int sBoxY, int sLocalY, int baseHeight, int childrenFree,
                           int sGround, int tel, int tRot, unsigned int rankSeed, JigAtt& out) {
    const StructTables& T = *c.T;
    const int* TE = EL(T, tel);
    out.tel = tel; out.tRot = tRot; out.empty = false;
    if (TE[EL_KIND] == 0) { out.empty = true; return true; }
    int jb;
    int nTj = jg_jigRange(T, tel, &jb);
    if (nTj == 0) return false;
    BBox hack = jg_elemBox(T, tel, 0, 0, 0, tRot);
    int expandTo = 0;
    if (c.expansion && (hack.y1 - hack.y0 + 1) <= 16) {
        for (int k = 0; k < nTj; k++) {
            JigV tv = jg_load(T, jb < 0 ? -1 : jb + k, tRot, 0, 0, 0);
            int v = 0;
            if (bbInside(hack, tv.x + DIR_X[tv.front], tv.y + DIR_Y[tv.front], tv.z + DIR_Z[tv.front])) {
                int cp = jg_alias(c, tv.pool);
                int a = 0, b = 0;
                if (cp >= 0 && PL(T, cp)[PL_EXISTS]) {
                    a = PL(T, cp)[PL_MAXSIZE];
                    int fb = PL(T, cp)[PL_FALLBACK];
                    if (fb >= 0) b = PL(T, fb)[PL_MAXSIZE];
                }
                v = max(a, b);
            }
            expandTo = max(expandTo, v);
        }
    }
    int k0 = nTj > 1 ? (int)(rankSeed % (unsigned)nTj) : 0;      // Start-Jigsaw je Versuch zufaellig statt feste Reihenfolge
    for (int kk = 0; kk < nTj; kk++) {
        int k = (k0 + kk) % nTj;
        JigV tv = jg_load(T, jb < 0 ? -1 : jb + k, tRot, 0, 0, 0);
        if (!jg_canAttach(sj, tv)) continue;
        int rawX = tjx - tv.x, rawY = tjy - tv.y, rawZ = tjz - tv.z;
        BBox rawBB = jg_elemBox(T, tel, rawX, rawY, rawZ, tRot);
        int rawTargetY = rawBB.y0;
        bool tRigid = TE[EL_PROJ] == 1;
        int deltaY = sLocalY - tv.y + DIR_Y[sj.front];
        int targetBoxY = (sRigid && tRigid) ? sBoxY + deltaY : baseHeight - tv.y;
        int yOff = targetBoxY - rawTargetY;
        BBox tBB = bbMoved(rawBB, 0, yOff, 0);
        int tPosY = rawY + yOff;
        if (expandTo > 0) {
            int newSize = max(expandTo + 1, tBB.y1 - tBB.y0);
            tBB.y1 = max(tBB.y1, tBB.y0 + newSize);
        }
        if (jg_freeFits(c, childrenFree, tBB)) {
            out.rawX = rawX; out.rawZ = rawZ; out.tPosY = tPosY; out.tBB = tBB;
            out.tGround = tRigid ? sGround - deltaY : 1;
            return true;
        }
    }
    return false;
}

__device__ __forceinline__ unsigned long long jg_warpMin(unsigned long long v) {
    for (int o = 16; o > 0; o >>= 1) {
        unsigned int lo = __shfl_xor_sync(0xffffffffu, (unsigned int)v, o), hi = __shfl_xor_sync(0xffffffffu, (unsigned int)(v >> 32), o);
        unsigned long long w = ((unsigned long long)hi << 32) | lo;
        v = w < v ? w : v;
    }
    return v;
}

// JigsawPlacement.Placer.tryPlacingChildren, parallel: alle (Element x Rotation)-Versuche eines Quell-Jigsaws laufen ueber die 32 Lanes des Warps;
// gewaehlt wird der Passende mit dem kleinsten zufaelligen Rang (Hauptpool vor Fallback, EmptyPoolElement beendet die Liste).
// Alle Lanes fuehren den uebrige Code konvergent und mit gleichen Daten aus (siehe wg_struct_gen).
__device__ void jg_tryPlacingChildren(JigCtx& c, int srcIdx, int contextFree, int depth) {
    const StructTables& T = *c.T;
    LRand& rnd = *c.rnd;
    const int lane = threadIdx.x & 31;
    int* src = c.pieces + srcIdx * PIECE_W;
    int sElem = src[PC_ELEM];
    int sx = src[PC_X], sy = src[PC_Y], sz = src[PC_Z], sRot = src[PC_ROT];
    bool sRigid = EL(T, sElem)[EL_PROJ] == 1;
    int sourceFree = -1;
    BBox sBB = jg_pieceBox(src);
    int sBoxY = sBB.y0;
    int sGround = src[PC_GROUND];
    int nSrc = jg_jigList(T, sElem, rnd, c.lstSrc);
    for (int si = 0; si < nSrc; si++) {
        STAT(0, 1);
        JigV sj = jg_load(T, c.lstSrc[si], sRot, sx, sy, sz);
        int tjx = sj.x + DIR_X[sj.front], tjy = sj.y + DIR_Y[sj.front], tjz = sj.z + DIR_Z[sj.front];
        int sLocalY = sj.y - sBoxY;
        int poolName = jg_alias(c, sj.pool);
        if (poolName < 0 || !PL(T, poolName)[PL_EXISTS]) continue;
        if (!jg_poolUsable(T, poolName)) continue;
        int fallback = PL(T, poolName)[PL_FALLBACK];
        if (!jg_poolUsable(T, fallback)) continue;
        bool inside = bbInside(sBB, tjx, tjy, tjz);
        int childrenFree;
        if (inside) {
            if (sourceFree < 0) sourceFree = jg_freeNew(c, sBB.x0, sBB.y0, sBB.z0, sBB.x1 + 1, sBB.y1 + 1, sBB.z1 + 1);
            childrenFree = sourceFree;
        } else {
            childrenFree = contextFree;
        }
        // Kandidaten: Elemente des Ziel-Pools (ausser auf maximaler Tiefe), dann die des Fallbacks (ungemischt, der Rang mischt)
        int nT = 0, nMain = 0;
        if (depth != c.maxDepth) {
            const int* P = PL(T, poolName);
            int cnt = P[PL_COUNT];
            if (cnt > JG_MAXPOOL) cnt = JG_MAXPOOL;
            for (int i = 0; i < cnt; i++) c.lstPool[nT + i] = T.poolElems[P[PL_START] + i];
            nT += cnt;
            nMain = cnt;
        }
        {
            const int* P = PL(T, fallback);
            int cnt = P[PL_COUNT];
            if (nT + cnt > JG_MAXPOOL * 2) cnt = JG_MAXPOOL * 2 - nT;
            for (int i = 0; i < cnt; i++) c.lstPool[nT + i] = T.poolElems[P[PL_START] + i];
            nT += cnt;
        }
        __syncwarp();
        STAT(5, nT);
        int baseHeight = penv_height(T, sj.x, sj.z, HM_WORLD_SURFACE_WG);
        unsigned int sseed = jg_hash(c.hseed, (unsigned)srcIdx, (unsigned)si, 77u);
        int nA = nT * 4;
        unsigned long long best = ~0ull;
        for (int a = lane; a < nA; a += 32) {
            int ti = a >> 2, tRot = a & 3;
            unsigned int h = jg_hash(sseed, (unsigned)ti, (unsigned)tRot, (unsigned)a);
            unsigned long long key = ((unsigned long long)((ti >= nMain ? 0x80000000u : 0u) | (h & 0x7fffffffu)) << 32) | (unsigned)a;
            if (key >= best) continue;
            JigAtt at;
            STAT(1, 1);
            if (jg_attempt(c, sj, tjx, tjy, tjz, sRigid, sBoxY, sLocalY, baseHeight, childrenFree, sGround, c.lstPool[ti], tRot, h, at)) best = key;
        }
        best = jg_warpMin(best);
        if (best == ~0ull) continue;
        int a = (int)(unsigned)best;
        int ti = a >> 2, tRot = a & 3;
        JigAtt at;
        unsigned int h = jg_hash(sseed, (unsigned)ti, (unsigned)tRot, (unsigned)a);
        jg_attempt(c, sj, tjx, tjy, tjz, sRigid, sBoxY, sLocalY, baseHeight, childrenFree, sGround, c.lstPool[ti], tRot, h, at);
        if (at.empty) continue;
        jg_freeSub(c, childrenFree, at.tBB);
        int pi = jg_addPiece(c, at.tel, at.rawX, at.tPosY, at.rawZ, at.tRot, at.tGround, at.tBB, depth + 1);
        if (depth + 1 <= c.maxDepth) jg_queuePush(c, pi, childrenFree, depth + 1, sj.pprio);
        __syncwarp();
    }
}

// Ergebnis der Jigsaw-Startberechnung
struct JigStub {
    int ok;
    int posX, posY, posZ;     // GenerationStub.position (Biom-Pruefung)
    int centerX, centerY, centerZ;
};

// Hoehenanker (VerticalAnchor) aufloesen
__device__ __forceinline__ int st_anchor(const StructTables& T, int kind, int val) {
    int minGen = (int)T.minY, depth = (int)T.height;
    if (kind == 0) return val;
    if (kind == 1) return minGen + val;
    return depth - 1 + minGen - val;
}

// HeightProvider.sample (konstant, uniform, biased, trapezoid)
__device__ int st_sampleHeight(const StructTables& T, const int* S, LRand& r) {
    int kind = S[ST_HP_KIND];
    int lo = st_anchor(T, S[ST_HP_MIN_KIND], S[ST_HP_MIN_VAL]);
    if (kind == 0) return lo;
    int hi = st_anchor(T, S[ST_HP_MAX_KIND], S[ST_HP_MAX_VAL]);
    if (kind == 1) {
        if (lo > hi) return lo;
        return lr_between(r, lo, hi);
    }
    if (kind == 2 || kind == 3) {   // BiasedToBottomHeight / VeryBiasedToBottomHeight
        int inner = S[ST_HP_EXTRA];
        if (kind == 2) {
            if (hi - lo - inner + 1 <= 0) return lo;
            int a = lr_nextInt(r, hi - lo - inner + 1);
            return lr_nextInt(r, a + inner) + lo;
        }
        if (hi - lo - inner + 1 <= 0) return lo;
        int a = lr_between(r, lo + inner, hi);
        int b = lr_between(r, lo, a - 1);
        return lr_between(r, lo, b - 1 + inner);   // Naeherung: VeryBiased siehe Vanilla (selten genutzt)
    }
    // trapezoid
    int plateau = S[ST_HP_EXTRA];
    int span = hi - lo;
    if (plateau >= span) return lr_between(r, lo, hi);
    int sideLen = (span - plateau) / 2;
    int sideRem = span - sideLen;
    return lo + lr_between(r, 0, sideRem) + lr_between(r, 0, sideLen);
}

// biome mask test
__device__ __forceinline__ bool st_biomeOk(const StructTables& T, const int* S, int biome) {
    if (biome < 0) return false;
    return (T.biomeMasks[S[ST_BIOME_MASK] + (biome >> 5)] >> (biome & 31)) & 1;
}

// Structure.GenerationContext.couldStructureExistInColumn
__device__ bool st_couldExistInColumn(const StructTables& T, const int* S, int bx, int bz, int minBY, int maxBY) {
    int qx = bx >> 2, qz = bz >> 2;
    int q0 = minBY >> 2, q1 = maxBY >> 2;
    for (int qy = q0; qy <= q1; qy++) if (st_biomeOk(T, S, penv_biome(T, qx, qy, qz))) return true;
    return false;
}

// Alias-Bindungen (aliasData): [n, (type, a, b, ...)] -> siehe StructureCompiler
// Kodierung je Struktur ab ST_ALIAS_START: Zahl Bindungen; dann je Bindung:
//   0 direct:       alias, target
//   1 random:       alias, nTargets, (target, weight)*
//   2 random_group: nGroups, (weight, nBindings, binding*)*   (Bindungen nur direct/random, wie Vanilla-Daten)
// Aufloesung wie PoolAliasLookup.create: RandomSource.create(seed).forkPositional().at(pos) (Xoroshiro), siehe wg_struct_alias.cuh
