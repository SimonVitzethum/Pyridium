// Pack "Arc Realism": filmische Antwort. Eigene Umsetzung; die Idee einer
// rationalen Filmkurve mit Fuss und Schulter ist verbreitet (u. a. im
// MIT-lizenzierten Arc-Shader beschrieben), Code wurde nicht uebernommen.
// Optionen: 0 film, 1 saturation, 2 shoulder, 3 sky_ms, 4 stars.
#include "sky.cu"

__device__ inline float film_curve(float x) {
    // rationale S-Kurve: weicher Fuss, lange Schulter, 1 bei x -> gross
    return fminf(fmaxf((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f), 0.f), 1.f);
}

PYR_STAGE_DISPLAY {
    float film = PYR_OPT(0), sat = PYR_OPT(1), start = PYR_OPT(2);
    float c[3] = { fmaxf(r, 0.f), fmaxf(g, 0.f), fmaxf(b, 0.f) };
    float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    for (int i = 0; i < 3; i++) c[i] = l + (c[i] - l) * sat;
    float peak = fmaxf(c[0], fmaxf(c[1], c[2]));
    // linearer Zweig mit weicher Schulter
    float lin[3] = { c[0], c[1], c[2] };
    if (peak > start) {
        float room = 1.f - start;
        float np = start + room * (1.f - __expf(-(peak - start) / room));
        for (int i = 0; i < 3; i++) lin[i] *= np / peak;
    }
    for (int i = 0; i < 3; i++) {
        float f = film_curve(fmaxf(c[i], 0.f));
        out[i] = fminf(fmaxf(lin[i] + (f - lin[i]) * film, 0.f), 1.f);
    }
}
