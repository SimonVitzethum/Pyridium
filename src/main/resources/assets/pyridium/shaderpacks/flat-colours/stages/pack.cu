// Beispielpack "Flat Colours": posterisiert die Anzeigefarbe in wenige Stufen
// (in Wahrnehmungshelligkeit, ueber die Wurzel). Zeigt, dass ein Drittpack das
// Aussehen ohne Eingriff in Pyrit aendert. Option 0: Anzahl der Stufen.
PYR_STAGE_DISPLAY {
    float n = fmaxf(PYR_OPT(0), 2.f) - 1.f;
    float c[3] = { r, g, b };
    for (int i = 0; i < 3; i++) {
        float v = sqrtf(fminf(fmaxf(c[i], 0.f), 1.f));
        v = floorf(v * n + 0.5f) / n;
        out[i] = v * v;
    }
}
