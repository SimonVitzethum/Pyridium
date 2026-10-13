// Standardpack, Stage "display": Lebendigkeit und weiche Lichterschulter.
// Eigene Umsetzung (Idee: Belichtung unveraendert bis zur Schulter, darueber
// exponentielle Kompression der hellsten Komponente, helle Farben gehen
// zum Weiss). Optionen: 0 vibrance, 1 shoulder, 2 white_desat.
#include "sky.cu"

PYR_STAGE_DISPLAY {
    float vib = PYR_OPT(0), start = PYR_OPT(1), desat = PYR_OPT(2);
    float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    // Lebendigkeit: gedaempfte Farben staerker anheben als gesaettigte
    float mx = fmaxf(r, fmaxf(g, b)), mn = fminf(r, fminf(g, b));
    float sat = mx > 1e-5f ? (mx - mn) / mx : 0.f;
    float k = 1.f + vib * (1.f - sat);
    r = l + (r - l) * k;
    g = l + (g - l) * k;
    b = l + (b - l) * k;
    r = fmaxf(r, 0.f); g = fmaxf(g, 0.f); b = fmaxf(b, 0.f);
    float peak = fmaxf(r, fmaxf(g, b));
    if (peak > start) {
        float room = 1.f - start;
        float np = start + room * (1.f - __expf(-(peak - start) / room));
        float s = np / peak;
        r *= s; g *= s; b *= s;
        // je naeher am Weiss, desto weniger Farbe
        float w = desat * (peak - np) / (peak - np + 1.f);
        r += (np - r) * w; g += (np - g) * w; b += (np - b) * w;
    }
    out[0] = fminf(r, 1.f);
    out[1] = fminf(g, 1.f);
    out[2] = fminf(b, 1.f);
}
