// wg_struct_query.cuh - Abfrage der erzeugten Strukturen pro Voxel/Spalte: der Haken fuer LOD- und Nahwelt-Kernel.
//
//   #include "wg_struct_query.cuh"           (eigenstaendig; definiere vorher ggf. nichts)
//   const StructTables& T = *(const StructTables*)vf_b[N];     // Bindung mit der StructTables-Sicht (Java: StructureGpu.viewBuffer())
//
//   StructCol col;
//   struct_columnBegin(T, col, x, z, step, surfaceH);          // sammelt die Teile, die diese Spalte beruehren (Hash-Zelle)
//   if (col.n) for (int y = ...; ...; y += step) {
//       uint s = struct_columnAt(T, col, y);                   // 0 = keine Struktur; sonst STRUCT_PRESENT | Blockzustands-Id
//       if (s) { uint attr = struct_attr(T, s); ... }          // attr: Pyrit-Material/Farbe (Tabelle T.attr, falls gesetzt)
//   }
//
// Semantik wie StructureTemplate.placeInWorld: Luft der Vorlage ist "Struktur-Luft" (raeumt das Gelaende;
// STRUCT_PRESENT mit Zustands-Id 0), ausser bei Legacy-Elementen (dort entfaellt Luft). Spaetere Teile
// ueberschreiben fruehere (Reihenfolge der Teile in der Start-Liste wie in Vanilla).
// step = 2^lod: der Voxelmittelpunkt wird in der Mip-Ebene lod der Vorlage nachgeschlagen (grobe Silhouette).
// surfaceH: Hoehe der Welt-Oberflaeche (WORLD_SURFACE-Heightmap, erster freier Block) der Spalte, fuer
// terrain_matching-Teile (Strassen); INT_MIN = unbekannt (dann gilt die beim Platzieren ermittelte Hoehe).
#pragma once
#include "wg_struct_tables.cuh"

#define STRUCT_PRESENT 0x80000000u
#define STRUCT_CELL_SHIFT 6
#define STRUCT_COL_MAX 24

struct StructCol {
    int n;
    int step;
    int x, z;
    int surfaceH;
    int piece[STRUCT_COL_MAX];
};

__device__ __forceinline__ unsigned long long struct_cellKey(int cx, int cz) {
    return ((((unsigned long long)(unsigned int)(cx + 0x40000000)) << 32) | (unsigned long long)(unsigned int)(cz + 0x40000000)) + 1ULL;
}
__device__ __forceinline__ unsigned int struct_cellHash(unsigned long long k) {
    k ^= k >> 33; k *= 0xff51afd7ed558ccdULL; k ^= k >> 33; k *= 0xc4ceb9fe1a85ec53ULL; k ^= k >> 33;
    return (unsigned int)k;
}

// Spalte (x, z): Teile sammeln, deren Bounding Box die Spalte enthaelt (nach Teilindex aufsteigend sortiert)
__device__ void struct_columnBegin(const StructTables& T, StructCol& col, int x, int z, int step, int surfaceH) {
    col.n = 0; col.step = step; col.x = x; col.z = z; col.surfaceH = surfaceH;
    if (T.cellKeys == nullptr) return;
    unsigned long long key = struct_cellKey(x >> STRUCT_CELL_SHIFT, z >> STRUCT_CELL_SHIFT);
    unsigned int mask = (unsigned int)T.hashMask;
    unsigned int slot = struct_cellHash(key) & mask;
    for (unsigned int probe = 0; probe <= mask; probe++) {
        unsigned long long k = T.cellKeys[slot];
        if (k == key) break;
        if (k == 0ULL) return;
        slot = (slot + 1) & mask;
    }
    int hx = x + (step >> 1), hz = z + (step >> 1);
    for (int nd = T.cellHeads[slot]; nd != 0; nd = T.nodes[(size_t)nd * 2 + 1]) {
        int pi = T.nodes[(size_t)nd * 2];
        const int* p = T.pieces + (size_t)pi * PIECE_W;
        if (hx < p[PC_MINX] || hx > p[PC_MAXX] || hz < p[PC_MINZ] || hz > p[PC_MAXZ]) {
            // terrain_matching: Spalte kann in x/z nur innerhalb der Box liegen (y wird verschoben)
            continue;
        }
        if (col.n < STRUCT_COL_MAX) {
            // einsortieren nach Teilindex (stabile Reihenfolge: spaetere ueberschreiben fruehere)
            int j = col.n++;
            while (j > 0 && col.piece[j - 1] > pi) { col.piece[j] = col.piece[j - 1]; j--; }
            col.piece[j] = pi;
        }
    }
}

// Umkehrung von tpl_xform (siehe wg_struct_proc.cuh): Welt-Offset zur Vorlagenposition -> Vorlagen-lokale x/z
__device__ __forceinline__ void tpl_inverse(int dx, int dz, int rot, int mirror, int pvx, int pvz, int& lx, int& lz) {
    int ax = dx - pvx, az = dz - pvz, bx, bz;
    switch (rot) {
        case 1: bx = az; bz = -ax; break;       // R^-1 von CLOCKWISE_90
        case 2: bx = -ax; bz = -az; break;
        case 3: bx = -az; bz = ax; break;       // R^-1 von COUNTERCLOCKWISE_90
        default: bx = ax; bz = az; break;
    }
    bx += pvx; bz += pvz;
    if (mirror == 1) bz = -bz; else if (mirror == 2) bx = -bx;
    lx = bx; lz = bz;
}

// Prozessorliste auf einen Blockzustand anwenden (RuleProcessor / BlockRotProcessor); Rueckgabe -2 = Block entfaellt
__device__ int struct_applyProcs(const StructTables& T, int list, int state, int x, int y, int z) {
    const int* code = T.procCode + T.procOff[list];
    int nOps = code[0];
    code++;
    for (int o = 0; o < nOps; o++) {
        int op = code[0];
        if (op == 1) {                // RULE
            int nr = code[1];
            LRand r;
            lr_setSeed(r, st_mthSeed(x, y, z));
            int blk = T.stateBlock[state];
            for (int k = 0; k < nr; k++) {
                const int* q = code + 2 + k * 4;
                bool match;
                switch (q[0]) {
                    case 0: match = true; break;
                    case 1: match = blk == q[1]; break;
                    case 2: match = state == q[1]; break;
                    case 3: match = blk == q[1] && lr_nextFloat(r) < __int_as_float(q[2]); break;
                    case 4: match = state == q[1] && lr_nextFloat(r) < __int_as_float(q[2]); break;
                    default: match = blk >= 0 && ((T.tagBits[(size_t)q[1] * T.tagWords + (blk >> 5)] >> (blk & 31)) & 1) != 0; break;
                }
                if (match) { state = q[3]; break; }
            }
            code += 2 + nr * 4;
        } else if (op == 2) {         // ROT: Block faellt mit Wahrscheinlichkeit 1 - integrity weg
            int blk = T.stateBlock[state];
            bool rottable = code[2] < 0 || (blk >= 0 && ((T.tagBits[(size_t)code[2] * T.tagWords + (blk >> 5)] >> (blk & 31)) & 1) != 0);
            if (rottable) {
                LRand r;
                lr_setSeed(r, st_mthSeed(x, y, z));
                if (!(lr_nextFloat(r) <= __int_as_float(code[1]))) return -2;
            }
            code += 3;
        } else {                      // IGNORE
            int blk = T.stateBlock[state];
            if (blk >= 0 && ((T.tagBits[(size_t)code[1] * T.tagWords + (blk >> 5)] >> (blk & 31)) & 1) != 0) return -2;
            code += 2;
        }
    }
    return state;
}

// Mip-Ebene einer Vorlage: Groesse und Daten (level 0 = volle Aufloesung)
__device__ __forceinline__ const unsigned short* struct_mip(const StructTables& T, const int* P, int level, int& sx, int& sy, int& sz) {
    if (level <= 0 || P[TP_MIP] < 0) {
        sx = P[TP_SX]; sy = P[TP_SY]; sz = P[TP_SZ];
        return T.voxels + P[TP_VOX];
    }
    const int* m = T.mips + P[TP_MIP];
    int nl = m[0];
    if (level > nl) level = nl;
    const int* d = m + 1 + (level - 1) * 4;
    sx = d[0]; sy = d[1]; sz = d[2];
    return T.voxels + d[3];
}

// Block der Struktur an (x, y, z) der Spalte; 0 = keine Struktur
__device__ unsigned int struct_columnAt(const StructTables& T, const StructCol& col, int y) {
    unsigned int result = 0;
    int step = col.step;
    int level = 0;
    while ((1 << (level + 1)) <= step) level++;
    int hx = col.x + (step >> 1), hz = col.z + (step >> 1), hy = y + (step >> 1);
    for (int i = 0; i < col.n; i++) {
        const int* p = T.pieces + (size_t)col.piece[i] * PIECE_W;
        int tpl = p[PC_TPL];
        if (tpl < 0) continue;
        const int* P = T.templates + (size_t)tpl * TPL_W;
        int rot = p[PC_ROT];
        int dx = hx - p[PC_X], dz = hz - p[PC_Z];
        int lx, lz;
        if (p[PC_KIND] == PK_PROC) tpl_inverse(dx, dz, rot, p[PC_MIRROR], p[PC_P0], p[PC_P1], lx, lz);
        else unrotXZ(dx, dz, rot, lx, lz);
        int py = p[PC_Y];
        if ((p[PC_FLAGS] & 2) && col.surfaceH != SMINI) {
            // terrain_matching (GravityProcessor): y = Hoehe(x, z) - 1 + lokales y
            py = col.surfaceH - 1;
        }
        int ly = hy - py;
        int sx, sy, sz;
        const unsigned short* vox = struct_mip(T, P, level, sx, sy, sz);
        int mlx = lx >> level, mly = ly >> level, mlz = lz >> level;
        if (lx < 0 || ly < 0 || lz < 0 || mlx >= sx || mly >= sy || mlz >= sz) continue;
        unsigned int v = vox[mlx + sx * (mlz + sz * mly)];
        if (v == 0) continue;
        int st = T.palette[P[TP_PAL] + (int)v - 1];
        if (level == 0 && p[PC_PROCS] >= 0 && T.procOff != nullptr) {
            // Prozessoren sehen die unverschobene Welt-Position (vor der Gelaende-Projektion)
            st = struct_applyProcs(T, p[PC_PROCS], st, hx, p[PC_Y] + ly, hz);
            if (st == -2) continue;
        }
        if (st == 0 && (p[PC_FLAGS] & 1)) continue;     // Legacy-Element: Luft wird nicht gesetzt (auch nach den Prozessoren)
        result = STRUCT_PRESENT | (unsigned int)st;
    }
    return result;
}

// Einzelabfrage (Spalte + y); fuer viele Voxel einer Spalte besser struct_columnBegin/struct_columnAt
__device__ unsigned int struct_blockAt(const StructTables& T, int x, int y, int z, int step, int surfaceH) {
    StructCol col;
    struct_columnBegin(T, col, x, z, step, surfaceH);
    if (col.n == 0) return 0;
    return struct_columnAt(T, col, y);
}

// Pyrit-Attribut zu einem Ergebnis (T.attr: Blockzustands-Id -> Attribut; ohne Tabelle die Zustands-Id selbst)
__device__ __forceinline__ unsigned int struct_attr(const StructTables& T, unsigned int s) {
    unsigned int id = s & 0x7fffffffu;
    if (T.attr == nullptr) return id;
    return id < (unsigned int)T.numAttr ? (unsigned int)T.attr[id] : 0u;
}
