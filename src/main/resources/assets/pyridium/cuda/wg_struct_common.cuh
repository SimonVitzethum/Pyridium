// wg_struct_common.cuh - gemeinsame Grundlage der Kernel dieses Moduls (Tabellen + Richtungen/Boxen + Bindungen).
#pragma once
#include "wg_struct_tables.cuh"

// Puffer-Bindungen (Geraetezeiger je Binding; Host setzt sie per cuModuleGetGlobal("vf_b")). Bindet ein fremder Kernel
// die Struktur-Abfrage ein, definiert er vf_b selbst und setzt STRUCT_EXTERNAL_VFB.
#ifndef STRUCT_EXTERNAL_VFB
__device__ void* vf_b[24];
#endif

// ---------------------------------------------------------------------------------------------
// Richtungen, Rotation, Bounding Boxes (Vanilla-Konventionen)
// ---------------------------------------------------------------------------------------------
// Direction: DOWN 0, UP 1, NORTH 2, SOUTH 3, WEST 4, EAST 5
__device__ __constant__ int DIR_X[6] = {0, 0, 0, 0, -1, 1};
__device__ __constant__ int DIR_Y[6] = {-1, 1, 0, 0, 0, 0};
__device__ __constant__ int DIR_Z[6] = {0, 0, -1, 1, 0, 0};

__device__ __forceinline__ int dirOpposite(int d) { return d ^ 1; }
// Rotation: 0 NONE, 1 CLOCKWISE_90, 2 CLOCKWISE_180, 3 COUNTERCLOCKWISE_90
// Himmelsrichtungen im Uhrzeigersinn: NORTH(2) -> EAST(5) -> SOUTH(3) -> WEST(4)
__device__ __forceinline__ int dirRotate(int d, int rot) {
    if (d < 2) return d;
    int idx = d == 2 ? 0 : d == 5 ? 1 : d == 3 ? 2 : 3;
    idx = (idx + rot) & 3;
    return idx == 0 ? 2 : idx == 1 ? 5 : idx == 2 ? 3 : 4;
}

struct BBox { int x0, y0, z0, x1, y1, z1; };  // inklusive wie BoundingBox

__device__ __forceinline__ bool bbIntersects(const BBox& a, const BBox& b) {
    return a.x1 >= b.x0 && a.x0 <= b.x1 && a.z1 >= b.z0 && a.z0 <= b.z1 && a.y1 >= b.y0 && a.y0 <= b.y1;
}
__device__ __forceinline__ bool bbInside(const BBox& b, int x, int y, int z) {
    return x >= b.x0 && x <= b.x1 && y >= b.y0 && y <= b.y1 && z >= b.z0 && z <= b.z1;
}
__device__ __forceinline__ BBox bbMoved(BBox b, int dx, int dy, int dz) {
    b.x0 += dx; b.x1 += dx; b.y0 += dy; b.y1 += dy; b.z0 += dz; b.z1 += dz;
    return b;
}

// Zugriff auf die Tabellen
__device__ __forceinline__ const StructTables* structTables() { return (const StructTables*)vf_b[0]; }
