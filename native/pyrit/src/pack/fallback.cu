// Notfall-Pack: nur begrenzen. Wird verwendet, wenn kein Pack baut.
PYR_STAGE_DISPLAY {
    out[0] = fminf(fmaxf(r, 0.f), 1.f);
    out[1] = fminf(fmaxf(g, 0.f), 1.f);
    out[2] = fminf(fmaxf(b, 0.f), 1.f);
}
