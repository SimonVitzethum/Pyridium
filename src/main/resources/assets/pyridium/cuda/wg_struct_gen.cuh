// wg_struct_gen.cuh - Strukturstart-Erzeugung (ChunkGenerator.createStructures fuer ein (Set, Chunk))
// und die Kernel wg_struct_place (Kandidaten) / wg_struct_gen (Starts + Teile).
//
// Vor dem Einbinden muss die Umgebung definiert sein (penv_height / penv_biome), siehe
// wg_struct_env_synth.cuh (Test) und wg_struct_env_tile.cuh (Produktion).
#pragma once
#include "wg_struct_place.cuh"
#include "wg_struct_jigsaw.cuh"
#include "wg_struct_alias.cuh"
#include "wg_struct_query.cuh"

// JigsawStructure.findGenerationPoint + JigsawPlacement.addPieces (Teil 1: Mittelstueck, Platzierung, Biom-Position)
__device__ bool jg_findStart(const StructTables& T, const int* S, JigCtx& c, LRand& rnd, int cx, int cz, JigStub& out, int& dyOut) {
    int height = st_sampleHeight(T, S, rnd);
    int px = cx * 16, py = height, pz = cz * 16;
    jg_resolveAliases(T, S, c, px, py, pz);
    int rot = lr_nextInt(rnd, 4);
    int pool = jg_alias(c, S[ST_START_POOL]);
    if (pool < 0 || !PL(T, pool)[PL_EXISTS]) return false;
    int cnt = PL(T, pool)[PL_COUNT];
    if (cnt == 0) return false;
    int elem = T.poolElems[PL(T, pool)[PL_START] + lr_nextInt(rnd, cnt)];
    if (EL(T, elem)[EL_KIND] == 0) return false;
    int ax = px, ay = py, az = pz;
    if (S[ST_START_JIGSAW] >= 0) {
        int n = jg_jigList(T, elem, rnd, c.lstSrc);
        bool found = false;
        for (int i = 0; i < n; i++) {
            JigV j = jg_load(T, c.lstSrc[i], rot, px, py, pz);
            if (j.name == S[ST_START_JIGSAW]) { ax = j.x; ay = j.y; az = j.z; found = true; break; }
        }
        if (!found) return false;
    }
    int lx = ax - px, ly = ay - py, lz = az - pz;
    int adjX = px - lx, adjY = py - ly, adjZ = pz - lz;
    BBox box = jg_elemBox(T, elem, adjX, adjY, adjZ, rot);
    int ground = 1;
    int centerX = (box.x1 + box.x0) / 2, centerZ = (box.z1 + box.z0) / 2;
    int bottomY;
    int minY = (int)T.minY, maxY = (int)T.minY + (int)T.height - 1;
    if (S[ST_PROJECT] == HM_NONE) {
        bottomY = adjY;
    } else {
        if (!st_couldExistInColumn(T, S, centerX, centerZ, minY, maxY)) return false;
        bottomY = py + penv_height(T, centerX, centerZ, S[ST_PROJECT]);
    }
    int oldAbsoluteGroundY = box.y0 + ground;
    int dy = bottomY - oldAbsoluteGroundY;
    box = bbMoved(box, 0, dy, 0);
    int padTop = S[ST_PAD_TOP], padBottom = S[ST_PAD_BOTTOM];
    if (padTop != 0 || padBottom != 0) {
        if (box.y0 < minY + padBottom || box.y1 > maxY - padTop) return false;
    }
    int centerY = bottomY + ly;
    out.ok = 1;
    out.posX = centerX; out.posY = centerY; out.posZ = centerZ;
    out.centerX = centerX; out.centerY = centerY; out.centerZ = centerZ;
    // Mittelstueck eintragen
    jg_addPiece(c, elem, adjX, adjY + dy, adjZ, rot, ground, box, 0);
    dyOut = dy;
    return true;
}

// JigsawPlacement.addPieces (Teil 2: Kinder), nur wenn der Startpunkt gueltig war
__device__ void jg_expand(const StructTables& T, const int* S, JigCtx& c, const JigStub& st) {
    int maxDepth = S[ST_MAX_DEPTH];
    if (maxDepth <= 0) return;
    BBox box = jg_pieceBox(c.pieces);
    int H = S[ST_MAXDIST_H], V = S[ST_MAXDIST_V];
    int minY = (int)T.minY, maxEx = (int)T.minY + (int)T.height;
    int y0 = max(st.centerY - V, minY + S[ST_PAD_BOTTOM]);
    int y1 = min(st.centerY + V + 1, maxEx - S[ST_PAD_TOP]);
    int fr = jg_freeNew(c, st.centerX - H, y0, st.centerZ - H, st.centerX + H + 1, y1, st.centerZ + H + 1);
    jg_freeSub(c, fr, box);
    jg_tryPlacingChildren(c, 0, fr, 0);
    int pi, fi, depth;
    while (jg_queuePop(c, pi, fi, depth)) jg_tryPlacingChildren(c, pi, fi, depth);
}

// Strukturstart eines Typs; Ergebnis: Teile in c.pieces, Start in rec. Rueckgabe: gueltiger Start (Teile vorhanden)
__device__ bool st_tryStructure(const StructTables& T, int structIdx, int cx, int cz, int* sc, int* rec) {
    const int* S = T.structs + structIdx * STRUCT_W;
    if (!(S[ST_FLAGS] & 1)) return false;
    JigCtx c;
    c.T = &T;
    LRand rnd;
    lr_largeFeatureSeed(rnd, T.seed, cx, cz);
    c.rnd = &rnd;
    c.pieces = sc; c.np = 0;
    c.queue = c.pieces + JG_MAXP * PIECE_W; c.nq = 0;
    c.frees = c.queue + JG_MAXQ * 4; c.nf = 0;
    c.nodes = c.frees + JG_MAXF * 8; c.nn = 0;
    c.lstSrc = c.nodes + JG_MAXN * 8;
    c.lstTgt = c.lstSrc + JG_MAXJIG;
    c.lstPool = c.lstTgt + JG_MAXJIG;
    int* tail = c.lstPool + JG_MAXPOOL * 2;       // Scratch-Rest: Alias-Tabellen
    c.aliasFrom = tail; c.aliasTo = tail + 16;
    c.nAlias = 0;
    c.maxDepth = S[ST_MAX_DEPTH];
    c.expansion = S[ST_EXPANSION];
    c.minY = (int)T.minY; c.height = (int)T.height;
    c.structIdx = structIdx;
    c.overflow = 0;
    c.hseed = (unsigned)T.seed ^ ((unsigned)(T.seed >> 32) * 0x2545F491u) ^ ((unsigned)cx * 0x9E3779B1u) ^ ((unsigned)cz * 0x85EBCA77u) ^ ((unsigned)structIdx * 0xC2B2AE3Du);
    int type = S[ST_TYPE];
    JigStub stub;
    stub.ok = 0;
    if (type == STT_JIGSAW) {
        int dy;
        if (!jg_findStart(T, S, c, rnd, cx, cz, stub, dy)) return false;
        // Structure.isValidBiome
        if (!st_biomeOk(T, S, penv_biome(T, stub.posX >> 2, stub.posY >> 2, stub.posZ >> 2))) return false;
        jg_expand(T, S, c, stub);
    } else {
        return false;       // Strukturtypen ohne Jigsaw-Daten gibt es nicht mehr (Typ-Register: unbekannte Typen werden uebersprungen)
    }
    if (c.np == 0) return false;
    // Start-Record
    BBox bb = {SMAXI, SMAXI, SMAXI, SMINI, SMINI, SMINI};
    for (int i = 0; i < c.np; i++) {
        const int* p = c.pieces + i * PIECE_W;
        bb.x0 = min(bb.x0, p[PC_MINX]); bb.y0 = min(bb.y0, p[PC_MINY]); bb.z0 = min(bb.z0, p[PC_MINZ]);
        bb.x1 = max(bb.x1, p[PC_MAXX]); bb.y1 = max(bb.y1, p[PC_MAXY]); bb.z1 = max(bb.z1, p[PC_MAXZ]);
    }
    int infl = S[ST_ADAPT] != 0 ? 12 : 0;
    rec[SR_STRUCT] = structIdx; rec[SR_CX] = cx; rec[SR_CZ] = cz; rec[SR_PIECES] = c.np;
    rec[SR_MINX] = bb.x0 - infl; rec[SR_MINY] = bb.y0 - infl; rec[SR_MINZ] = bb.z0 - infl;
    rec[SR_MAXX] = bb.x1 + infl; rec[SR_MAXY] = bb.y1 + infl; rec[SR_MAXZ] = bb.z1 + infl;
    rec[SR_STATE] = c.overflow ? 2 : 1;
    rec[SR_POSX] = stub.posX; rec[SR_POSY] = stub.posY; rec[SR_POSZ] = stub.posZ;
    return true;
}

// ---------------------------------------------------------------------------------------------
// Kernel
// ---------------------------------------------------------------------------------------------
// Bindungen: 0 StructTables, 1 Kandidaten (int[0] = Anzahl, ab 4: je (set, cx, cz, 0)), 2 Starts (Kopf 4 ints: Starts, Teile, Flags),
//            3 Teile, 4 Scratch (STRUCT_SCRATCH_INTS je Kandidat), 5 Chunk-Maske (optional, int je Kandidat, nur Diagnose)
struct StructPush { int cx0, cz0, cw, cd, capCand, capStart, capPieces, flags; };

#define CAND_HDR 4
#define OUT_HDR 4

extern "C" __global__ void __launch_bounds__(64) wg_struct_place(StructPush p) {
    const StructTables& T = *structTables();
    int set = blockIdx.y;
    if (set >= (int)T.numSets) return;
    const int* S = SETP(T, set);
    if (S[SET_ENTRY_COUNT] == 0) return;
    int* cands = (int*)vf_b[1];
    int idx = blockIdx.x * blockDim.x + threadIdx.x;
    int cx, cz;
    if (S[SET_TYPE] == 0) {
        int sp = S[SET_SPACING];
        int cellX0 = floorDivI(p.cx0, sp), cellX1 = floorDivI(p.cx0 + p.cw - 1, sp);
        int cellZ0 = floorDivI(p.cz0, sp), cellZ1 = floorDivI(p.cz0 + p.cd - 1, sp);
        int ncx = cellX1 - cellX0 + 1, ncz = cellZ1 - cellZ0 + 1;
        if (idx >= ncx * ncz) return;
        int gx = cellX0 + idx % ncx, gz = cellZ0 + idx / ncx;
        // potentielles Chunk dieser Zelle
        LRand r;
        lr_largeFeatureSalt(r, T.seed, gx, gz, S[SET_SALT]);
        int limit = sp - S[SET_SEPARATION];
        int spreadX, spreadZ;
        if (S[SET_SPREAD] == 0) { spreadX = lr_nextInt(r, limit); spreadZ = lr_nextInt(r, limit); }
        else { spreadX = (lr_nextInt(r, limit) + lr_nextInt(r, limit)) / 2; spreadZ = (lr_nextInt(r, limit) + lr_nextInt(r, limit)) / 2; }
        cx = gx * sp + spreadX; cz = gz * sp + spreadZ;
    } else {
        if (idx >= S[SET_RING_COUNT]) return;
        cx = T.ringPos[(S[SET_RING_POS] + idx) * 2];
        cz = T.ringPos[(S[SET_RING_POS] + idx) * 2 + 1];
    }
    if (cx < p.cx0 || cx >= p.cx0 + p.cw || cz < p.cz0 || cz >= p.cz0 + p.cd) return;
    if (!place_isStructureChunk(T, set, cx, cz, 0)) return;
    int i = atomicAdd(&cands[0], 1);
    if (i < p.capCand) {
        cands[CAND_HDR + i * 4] = set; cands[CAND_HDR + i * 4 + 1] = cx; cands[CAND_HDR + i * 4 + 2] = cz;
        cands[CAND_HDR + i * 4 + 3] = penv_initCandidate(T, S, cx, cz);   // Kachel-Offset der Umgebung (-1: keine)
    }
}

// ChunkGenerator.createStructures fuer ein Kandidat (Set, Chunk): Auswahl nach Gewicht, Versuch bis zum ersten gueltigen Start
extern "C" __global__ void __launch_bounds__(32) wg_struct_gen(StructPush p) {
    const StructTables& T = *structTables();
    int* cands = (int*)vf_b[1];
    int* starts = (int*)vf_b[2];
    int* pieces = (int*)vf_b[3];
    int* scratch = (int*)vf_b[4];
    // Ein Warp (32 Lanes) je Slot: alle Lanes fuehren den Loeser identisch aus (gleicher Zufall, gleiche Daten), die teuren Boxtests
    // (jg_freeFits) laufen ueber die Lanes verteilt; Ausgabe und Atomics nur durch Lane 0 bzw. lane-gestreift.
    const int tid = blockIdx.x;
    const int lane = threadIdx.x & 31;
    int n = min(cands[0], p.capCand);
    int slots = p.flags;   // Anzahl Scratch-Slots = Warps; Kandidaten werden im Raster verteilt
    int* sc = scratch + (size_t)tid * STRUCT_SCRATCH_INTS;
    for (int i = tid; i < n; i += slots) {
    int set = cands[CAND_HDR + i * 4], cx = cands[CAND_HDR + i * 4 + 1], cz = cands[CAND_HDR + i * 4 + 2];
    const int* S = SETP(T, set);
    penv_selectCandidate(tid, cands[CAND_HDR + i * 4 + 3]);
    __syncwarp();
    int* rec = sc + STRUCT_SCRATCH_INTS - 256 + 32;          // Start-Record im Scratch-Rest (Stack klein halten)
    for (int k = 0; k < START_W; k++) rec[k] = 0;
    bool ok = false;
    int ne = S[SET_ENTRY_COUNT];
    int es = S[SET_ENTRY_START];
    if (ne == 1) {
        ok = st_tryStructure(T, T.entries[es * ENTRY_W], cx, cz, sc, rec);
    } else if (ne > 1) {
        int* opt = sc + STRUCT_SCRATCH_INTS - 256 + 64;
        int cnt = min(ne, 32);
        for (int k = 0; k < cnt; k++) opt[k] = k;
        LRand r;
        lr_largeFeatureSeed(r, T.seed, cx, cz);
        int total = 0;
        for (int k = 0; k < cnt; k++) total += T.entries[(es + opt[k]) * ENTRY_W + 1];
        while (cnt > 0) {
            int choice = lr_nextInt(r, total);
            int index = 0;
            for (int k = 0; k < cnt; k++) {
                choice -= T.entries[(es + opt[k]) * ENTRY_W + 1];
                if (choice < 0) break;
                index++;
            }
            int sel = opt[index];
            if (st_tryStructure(T, T.entries[(es + sel) * ENTRY_W], cx, cz, sc, rec)) { ok = true; break; }
            total -= T.entries[(es + sel) * ENTRY_W + 1];
            for (int k = index; k + 1 < cnt; k++) opt[k] = opt[k + 1];
            cnt--;
        }
    }
    __syncwarp();
    if (!ok) continue;
    int np = rec[SR_PIECES];
    int si = 0, pb = 0;
    if (lane == 0) { si = atomicAdd(&starts[0], 1); pb = atomicAdd(&starts[1], np); }
    si = __shfl_sync(0xffffffffu, si, 0);
    pb = __shfl_sync(0xffffffffu, pb, 0);
    if (si >= p.capStart || pb + np > p.capPieces) { if (lane == 0) atomicOr(&starts[2], 1); continue; }
    rec[SR_SET] = set; rec[SR_PIECE0] = pb;
    int* d = starts + OUT_HDR + si * START_W;
    for (int k = lane; k < START_W; k += 32) d[k] = rec[k];
    for (int w = lane; w < np * PIECE_W; w += 32) {
        int k = w / PIECE_W, q = w - k * PIECE_W;
        pieces[(size_t)(pb + k) * PIECE_W + q] = q == PC_START ? si : sc[k * PIECE_W + q];
    }
    __syncwarp();
    }
}

// Raumindex: neue Teile (Cursor starts[3] .. starts[1]) in die Hash-Zellen (64 x 64 Bloecke) eintragen.
// Bindungen: 2 Arena-Kopf, 3 Teile, 6 Zell-Schluessel (u64), 7 Zell-Kopfe, 8 Knoten (int[0] = Zaehler, int[1] = Ueberlauf, ab 2: Teil, naechster)
// Push: capCand = Knotenkapazitaet, capStart = Hash-Maske, capPieces = Teilekapazitaet
extern "C" __global__ void __launch_bounds__(64) wg_struct_index(StructPush p) {
    int* hdr = (int*)vf_b[2];
    int pi = hdr[3] + blockIdx.x * blockDim.x + threadIdx.x;
    if (pi >= min(hdr[1], p.capPieces)) return;
    const int* pc = (const int*)vf_b[3] + (size_t)pi * PIECE_W;
    unsigned long long* keys = (unsigned long long*)vf_b[6];
    int* heads = (int*)vf_b[7];
    int* nodes = (int*)vf_b[8];
    unsigned int mask = (unsigned int)p.capStart;
    int cx0 = pc[PC_MINX] >> STRUCT_CELL_SHIFT, cx1 = pc[PC_MAXX] >> STRUCT_CELL_SHIFT;
    int cz0 = pc[PC_MINZ] >> STRUCT_CELL_SHIFT, cz1 = pc[PC_MAXZ] >> STRUCT_CELL_SHIFT;
    for (int cz = cz0; cz <= cz1; cz++) {
        for (int cx = cx0; cx <= cx1; cx++) {
            unsigned long long key = struct_cellKey(cx, cz);
            unsigned int slot = struct_cellHash(key) & mask;
            for (unsigned int probe = 0; probe <= mask; probe++) {
                unsigned long long old = atomicCAS(&keys[slot], 0ULL, key);
                if (old == 0ULL || old == key) break;
                slot = (slot + 1) & mask;
            }
            int nd = atomicAdd(&nodes[0], 1) + 1;
            if (nd >= p.capCand) { atomicOr(&nodes[1], 1); continue; }
            nodes[(size_t)nd * 2] = pi;
            nodes[(size_t)nd * 2 + 1] = atomicExch(&heads[slot], nd);
        }
    }
}

// Cursor fortschreiben (nach wg_struct_index)
extern "C" __global__ void wg_struct_index_commit(StructPush p) {
    int* hdr = (int*)vf_b[2];
    hdr[3] = min(hdr[1], p.capPieces);
}

// Testwerkzeug: Draufsicht. Je Pixel (Spalte x0 + px * step, z0 + pz * step) das oberste Strukturblock-Ergebnis von y1 abwaerts bis y0.
// Bindungen: 0 Tabellen; 9 Ausgabe int[2 je Pixel]: Zustands-Id|Praesenz-Bit, y.  Push: cx0 = x0, cz0 = z0, cw = Breite, cd = Hoehe (Pixel),
// capCand = step, capStart = y0, capPieces = y1, flags = Spalten-Hoehenmodus (1: Gelaendehoehe aus der Umgebung, sonst unbekannt)
extern "C" __global__ void __launch_bounds__(64) wg_struct_topview(StructPush p) {
    const StructTables& T = *structTables();
    int px = blockIdx.x * blockDim.x + threadIdx.x, pz = blockIdx.y;
    if (px >= p.cw || pz >= p.cd) return;
    int step = p.capCand;
    int x = p.cx0 + px * step, z = p.cz0 + pz * step;
    int* out = (int*)vf_b[9] + ((size_t)pz * p.cw + px) * 2;
    out[0] = 0; out[1] = 0;
    int surfaceH = p.flags ? penv_height(T, x, z, HM_WORLD_SURFACE_WG) : SMINI;
    StructCol col;
    struct_columnBegin(T, col, x, z, step, surfaceH);
    if (col.n == 0) return;
    for (int y = p.capPieces; y >= p.capStart; y -= step) {
        unsigned int s = struct_columnAt(T, col, y);
        if (s != 0u && (s & 0x7fffffffu) != 0u) { out[0] = (int)s; out[1] = y; return; }
    }
}

// Testwerkzeug: Seitenansicht (Schnitt bei konstantem z). Pixel (px, py): x = x0 + px * step, y = yTop - py * step.
// Push: cx0 = x0, cz0 = z, cw = Breite, cd = Hoehe (Pixel), capCand = step, capStart = yTop, flags = Hoehenmodus
extern "C" __global__ void __launch_bounds__(64) wg_struct_slice(StructPush p) {
    const StructTables& T = *structTables();
    int px = blockIdx.x * blockDim.x + threadIdx.x, py = blockIdx.y;
    if (px >= p.cw || py >= p.cd) return;
    int step = p.capCand;
    int x = p.cx0 + px * step, z = p.cz0, y = p.capStart - py * step;
    int* out = (int*)vf_b[9] + ((size_t)py * p.cw + px) * 2;
    int surfaceH = p.flags ? penv_height(T, x, z, HM_WORLD_SURFACE_WG) : SMINI;
    out[0] = (int)struct_blockAt(T, x, y, z, step, surfaceH);
    out[1] = surfaceH;
}

// ---------------------------------------------------------------------------------------------
// concentric_rings (Strongholds): ChunkGeneratorStructureState.generateRingPositions auf der GPU.
// Teil 1 (ein Thread, sequentiell wie Vanillas Zufall): Ausgangspositionen und Seeds der Biomsuche je Ring.
// Teil 2 (parallel je Ring): BiomeSource.findBiomeHorizontal (57 x 57 Quarts bei y = 0, gleichverteilte Auswahl unter den
// Treffern) -> Chunk der Ringposition. Bindungen: 0 Tabellen, 4 Scratch (4 ints je Ring), Ergebnis in T.ringPos.
// Ein Block je Set (gridDim.x = Anzahl Sets), 128 Threads. Kernel wg_struct_rings = Teil 1 + 2; fuer Umgebungs-Kacheln
// getrennt: wg_struct_rings_init (schreibt die Ausgangspositionen vorlaeufig nach ringPos), dann fuellt der Anbieter die
// Biom-Ebenen (ENV_RINGPLANES), dann wg_struct_rings_search.
// ---------------------------------------------------------------------------------------------
__device__ void rings_init(const StructTables& T, int set, int* sc) {
    const int* S = SETP(T, set);
    int count = S[SET_RING_COUNT];
    int* out = (int*)T.ringPos + (size_t)S[SET_RING_POS] * 2;
    LRand r;
    lr_setSeed(r, T.seed);
    const double PI2 = 3.141592653589793 * 2.0;
    double angle = lr_nextDouble(r) * PI2;
    int positionInCircle = 0, circle = 0, spread = S[SET_RING_SPREAD], distance = S[SET_RING_DIST];
    for (int i = 0; i < count; i++) {
        double dist = (double)(4 * distance + distance * circle * 6) + (lr_nextDouble(r) - 0.5) * ((double)distance * 2.5);
        int ix = (int)floor(cos(angle) * dist + 0.5);
        int iz = (int)floor(sin(angle) * dist + 0.5);
        jlong fseed = lr_nextLong(r);        // random.fork()
        sc[i * 4] = ix; sc[i * 4 + 1] = iz; sc[i * 4 + 2] = (int)fseed; sc[i * 4 + 3] = (int)(fseed >> 32);
        out[i * 2] = ix; out[i * 2 + 1] = iz;
        angle += PI2 / (double)spread;
        if (++positionInCircle == spread) {
            circle++;
            positionInCircle = 0;
            spread += 2 * spread / (circle + 1);
            spread = min(spread, count - i);
            angle += lr_nextDouble(r) * PI2;
        }
    }
}

__device__ void rings_search(const StructTables& T, int set, const int* sc) {
    const int* S = SETP(T, set);
    int count = S[SET_RING_COUNT];
    int* out = (int*)T.ringPos + (size_t)S[SET_RING_POS] * 2;
    const int* mask = T.biomeMasks + S[SET_RING_MASK];
    for (int i = threadIdx.x; i < count; i += blockDim.x) {
        int ix = sc[i * 4], iz = sc[i * 4 + 1];
        jlong fseed = (jlong)((julong)(unsigned int)sc[i * 4 + 2] | ((julong)(unsigned int)sc[i * 4 + 3] << 32));
        LRand rr;
        lr_setSeed(rr, fseed);
        int ncx = ((ix << 4) + 8) >> 2, ncz = ((iz << 4) + 8) >> 2;
        int found = 0, rx = 0, rz = 0;
        bool have = false;
        for (int z = -28; z <= 28; z++)
            for (int x = -28; x <= 28; x++) {
                int nx = ncx + x, nz = ncz + z;
                int b = penv_ringBiome(T, set, i, nx, nz, ncx, ncz);
                if (b >= 0 && ((mask[b >> 5] >> (b & 31)) & 1)) {
                    if (!have || lr_nextInt(rr, found + 1) == 0) { rx = nx << 2; rz = nz << 2; have = true; }
                    found++;
                }
            }
        __syncwarp();
        out[i * 2] = have ? (rx >> 4) : ix;
        out[i * 2 + 1] = have ? (rz >> 4) : iz;
    }
}

extern "C" __global__ void __launch_bounds__(128) wg_struct_rings(StructPush p) {
    const StructTables& T = *structTables();
    int set = blockIdx.x;
    if (set >= (int)T.numSets || SETP(T, set)[SET_TYPE] != 1) return;
    int* sc = (int*)vf_b[4] + (size_t)set * 4096 * 4;
    if (threadIdx.x == 0) rings_init(T, set, sc);
    __syncthreads();
    rings_search(T, set, sc);
}

extern "C" __global__ void __launch_bounds__(128) wg_struct_rings_init(StructPush p) {
    const StructTables& T = *structTables();
    int set = blockIdx.x;
    if (set >= (int)T.numSets || SETP(T, set)[SET_TYPE] != 1) return;
    if (threadIdx.x == 0) rings_init(T, set, (int*)vf_b[4] + (size_t)set * 4096 * 4);
}

extern "C" __global__ void __launch_bounds__(128) wg_struct_rings_search(StructPush p) {
    const StructTables& T = *structTables();
    int set = blockIdx.x;
    if (set >= (int)T.numSets || SETP(T, set)[SET_TYPE] != 1) return;
    rings_search(T, set, (const int*)vf_b[4] + (size_t)set * 4096 * 4);
}
