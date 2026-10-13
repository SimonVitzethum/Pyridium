// Pyrit-Shaderpack-ABI (Version 1). Dieser Header gehoert dem Pyrit-Kern und
// wird vor jedes Pack gesetzt. Siehe docs/SHADERPACKS.md.
#pragma once
#define PYR_PACK_ABI 1

// Optionen des Packs (Reihenfolge wie in pack.json), zur Laufzeit gesetzt.
__device__ float pyr_pack_options[64];
#define PYR_OPT(i) (pyr_pack_options[(i)])

// Stage "display": Pixelfarbe nach Belichtung und Farbkorrektur (linear, HDR)
// in eine Anzeigefarbe (linear, 0..1; die sRGB-Kodierung macht Pyrit).
// `mode` ist der vom Host gewuenschte Tonemap-Modus (Hinweis, darf ignoriert werden).
#define PYR_STAGE_DISPLAY \
    extern "C" __device__ void pyr_pack_display(float r, float g, float b, float* out, unsigned mode)

// Stage "sky": Strahldichte des Himmels in Richtung (dx, dy, dz) (y nach oben,
// normiert). `sun` = {Richtung zur Sonne xyz, Beleuchtungsstaerke}. Pyrit baut damit
// die Umgebungskarte (Start, Optionswechsel, Heissladen); das Schattierungs-
// pfad liest nur die Karte, die Stage kostet pro Frame nichts.
#define PYR_STAGE_SKY \
    extern "C" __device__ void pyr_pack_sky(float dx, float dy, float dz, const float* sun, float* out)

// Stage "sun": Farbe des direkten Sonnenlichts am Boden (RGB, in Pyrit-Einheiten)
#define PYR_STAGE_SUN \
    extern "C" __device__ void pyr_pack_sun(const float* sun, float* out)

// Vorgaben fuer Stages, die ein Pack weglaesst (Pyrit haengt sie an, wenn der
// Quelltext die Stage nicht erwaehnt).
#define PYR_DEFAULT_DISPLAY_IMPL PYR_STAGE_DISPLAY { \
    out[0] = fminf(fmaxf(r, 0.f), 1.f); out[1] = fminf(fmaxf(g, 0.f), 1.f); out[2] = fminf(fmaxf(b, 0.f), 1.f); }
#define PYR_DEFAULT_SKY_IMPL PYR_STAGE_SKY { \
    float t = sqrtf(fminf(fabsf(dy), 1.f)); \
    float hx = 0.55f, hy = 0.7f, hz = 0.9f, zx = 0.12f, zy = 0.3f, zz = 0.75f; \
    if (dy >= 0.f) { out[0] = hx + (zx - hx) * t; out[1] = hy + (zy - hy) * t; out[2] = hz + (zz - hz) * t; } \
    else { out[0] = hx * 0.5f; out[1] = hy * 0.5f; out[2] = hz * 0.5f; } }
#define PYR_DEFAULT_SUN_IMPL PYR_STAGE_SUN { \
    out[0] = sun[3] * 1.0f; out[1] = sun[3] * 0.95f; out[2] = sun[3] * 0.85f; }
