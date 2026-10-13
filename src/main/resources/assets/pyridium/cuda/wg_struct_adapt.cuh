// wg_struct_adapt.cuh - Gelaendeanpassung der GPU-Strukturen fuer die LOD-Kerne (Hoehenfeld und 3D-Dichte).
//
// Unser Gelaende (Hoehenfeld aus der vorlaeufigen Oberflaeche, 3D-Dichte) ist nicht bitgleich zu Vanillas Gelaende, an dem die
// Strukturplatzierung ausgerichtet wurde. Damit nichts schwebt oder versinkt, passt jede Spalte die Struktur an das Gelaende an,
// das der jeweilige Pfad wirklich erzeugt:
//   * Fundament: Luft/Wasser unter dem tiefsten Strukturblock der Spalte wird bis zum tatsaechlichen Boden mit dem Material
//     des tiefsten Blocks gefuellt (struct_columnLow), hoechstens STRUCT_FOUND_MAX Bloecke tief.
//   * Ausraeumen: Luft der Vorlage (Zustand 0) und Zustaende ohne sichtbares Material raeumen das Gelaende (struct_columnAt).
//   * Strassen (terrain_matching) folgen der Hoehe, die der Pfad als surfaceH uebergibt (gleiche Funktion in beiden Pfaden:
//     vorlaeufige Oberflaeche), Luecken bis zum echten Boden schliesst das Fundament.
// Beide Pfade benutzen dieselbe Struktur-Ankerhoehe (Teile-Y aus dem Erzeugen, Umgebungs-Kacheln aus der echten vorlaeufigen
// Oberflaeche); nur die Spaltenanpassung unterscheidet sich je nach Gelaendeformat.
#pragma once
#include "wg_struct_query.cuh"

#define STRUCT_FOUND_MAX 48
// LP_LUT-Bit: Strukturen aktiv
#define LPF_STRUCT 8

__device__ __forceinline__ int struct_floordiv(int a, int b) {
    int q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

// vorlaeufige Oberflaeche (9 x 9 Gitter je Spalte, siehe wg_lod_height.cu): Hoehe in Bloecken an der Voxelmitte (vx, vz) der Stufe
// (in Voxeln relativ zum Spaltenursprung, auch -1..32)
__device__ __forceinline__ float struct_preHeight(const float* pre, float vx, float vz) {
    float fx = fminf(fmaxf((vx + 1.5f) * 0.25f, 0.0f), 8.0f - 1e-3f);
    float fz = fminf(fmaxf((vz + 1.5f) * 0.25f, 0.0f), 8.0f - 1e-3f);
    int ix = (int)fx, iz = (int)fz;
    float tx = fx - (float)ix, tz = fz - (float)iz;
    const float* p = pre + iz * 9 + ix;
    return (p[0] * (1 - tx) + p[1] * tx) * (1 - tz) + (p[9] * (1 - tx) + p[10] * tx) * tz;
}

// Tiefster nicht-leerer Block der Spalte ueber alle Teile (yLow = y, state = Blockzustand). false: kein fester Block.
__device__ bool struct_columnLow(const StructTables& T, const StructCol& col, int& yLow, int& state) {
    int step = col.step, level = 0;
    while ((1 << (level + 1)) <= step) level++;
    int hx = col.x + (step >> 1), hz = col.z + (step >> 1);
    bool found = false;
    yLow = SMAXI;
    state = 0;
    for (int i = 0; i < col.n; i++) {
        const int* p = T.pieces + (size_t)col.piece[i] * PIECE_W;
        int tpl = p[PC_TPL];
        if (tpl < 0) continue;
        const int* P = T.templates + (size_t)tpl * TPL_W;
        int dx = hx - p[PC_X], dz = hz - p[PC_Z], lx, lz;
        if (p[PC_KIND] == PK_PROC) tpl_inverse(dx, dz, p[PC_ROT], p[PC_MIRROR], p[PC_P0], p[PC_P1], lx, lz);
        else unrotXZ(dx, dz, p[PC_ROT], lx, lz);
        int py = p[PC_Y];
        if ((p[PC_FLAGS] & 2) && col.surfaceH != SMINI) py = col.surfaceH - 1;
        int sx, sy, sz;
        const unsigned short* vox = struct_mip(T, P, level, sx, sy, sz);
        int mlx = lx >> level, mlz = lz >> level;
        if (lx < 0 || lz < 0 || mlx >= sx || mlz >= sz) continue;
        for (int mly = 0; mly < sy; mly++) {
            unsigned int v = vox[mlx + sx * (mlz + sz * mly)];
            if (v == 0) continue;
            int st = T.palette[P[TP_PAL] + (int)v - 1];
            if (st == 0) continue;
            int y = py + (mly << level);
            if (y < yLow) { yLow = y; state = st; found = true; }
            break;
        }
    }
    return found;
}

// Spaltenanpassung im 3D-Pfad: bearbeitet die Festkoerper-Maske m der Stuetzspalte (Bit yi = Punkt y0 + yi * s fest).
// stMask: Bits der Strukturbloecke, fMask: Bits des Fundaments. Rueckgabe: Spalte beruehrt eine Struktur.
//   G = Bodenhoehe-Schaetzung (vorlaeufige Oberflaeche, erster freier y) fuer terrain_matching und fuer Fundamente ausserhalb des y-Bereichs
__device__ bool struct_adaptMask(const StructTables& T, int wx, int wz, int s, int G, int y0, int sy, int minY, int maxY,
                                 unsigned long long& m, unsigned long long& stMask, unsigned long long& fMask) {
    StructCol sc;
    struct_columnBegin(T, sc, wx, wz, s, G);
    if (!sc.n) return false;
    for (int yi = 0; yi < sy; yi++) {
        int yb = y0 + yi * s;
        if (yb < minY || yb >= maxY) continue;
        unsigned int r = struct_columnAt(T, sc, yb);
        if (!r) continue;
        unsigned long long bit = 1ull << yi;
        if ((r & 0x7fffffffu) == 0 || struct_attr(T, r) == 0) m &= ~bit;
        else { m |= bit; stMask |= bit; }
    }
    int yLow, fst;
    if (struct_columnLow(T, sc, yLow, fst)) {
        int yi0 = struct_floordiv(yLow - 1 - y0, s);
        if (yi0 >= sy) {
            // Bereich liegt unter der Struktur: Boden laut Schaetzung muss unter dem Fundament liegen
            yi0 = G < yLow ? sy - 1 : -1;
        }
        for (int yi = yi0; yi >= 0; yi--) {
            int yb = y0 + yi * s;
            if (yLow - yb > STRUCT_FOUND_MAX) break;
            if (yb < minY) break;
            if (((m >> yi) & 1ull) != 0) break;       // echter Boden erreicht
            m |= 1ull << yi;
            fMask |= 1ull << yi;
        }
    }
    return true;
}
