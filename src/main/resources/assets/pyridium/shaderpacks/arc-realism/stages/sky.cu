// Standardpack: Himmel. Einfachstreuung in der Erdatmosphaere (Rayleigh fuer das
// Blau, Mie fuer den Hof um die Sonne), keine Wolken, kein Dunst. Pyrit bakt
// das Ergebnis bei Start und Optionswechsel in die Umgebungskarte; die
// Sonne ist ein eigenes Licht (Stage "sun": Farbe aus der Durchlaessigkeit).
// Optionen: 3 = Mehrfachstreuungs-Faktor, 4 = Sternhelligkeit.

#define EARTH_R 6360e3f
#define ATMO_R 6420e3f
#define H_R 8000.f
#define H_M 1200.f
#define MIE_G 0.76f
#define PI_F 3.14159265f

__device__ inline float3 v3(float x, float y, float z) { return make_float3(x, y, z); }
__device__ inline float dot3(float3 a, float3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

__device__ inline float to_top(float3 o, float3 d) {
    float b = dot3(o, d);
    float c = dot3(o, o) - ATMO_R * ATMO_R;
    return -b + sqrtf(fmaxf(b * b - c, 0.f));
}

__device__ inline bool hits_ground(float3 o, float3 d) {
    float b = dot3(o, d);
    float c = dot3(o, o) - EARTH_R * EARTH_R;
    return b < 0.f && b * b - c > 0.f;
}

__device__ inline float2 optical_depth(float3 o, float3 d, int steps) {
    float len = to_top(o, d);
    float ds = len / (float)steps;
    float r = 0.f, m = 0.f;
    for (int i = 0; i < steps; i++) {
        float t = ((float)i + 0.5f) * ds;
        float3 p = v3(o.x + d.x * t, o.y + d.y * t, o.z + d.z * t);
        float hgt = sqrtf(dot3(p, p)) - EARTH_R;
        r += __expf(-hgt / H_R) * ds;
        m += __expf(-hgt / H_M) * ds;
    }
    return make_float2(r, m);
}

__device__ inline float3 beta_r() { return v3(5.8e-6f, 13.5e-6f, 33.1e-6f); }
#define BETA_M 8e-6f

__device__ inline float3 transmittance(float2 depth) {
    float3 b = beta_r();
    return v3(__expf(-(b.x * depth.x + BETA_M * 1.1f * depth.y)),
              __expf(-(b.y * depth.x + BETA_M * 1.1f * depth.y)),
              __expf(-(b.z * depth.x + BETA_M * 1.1f * depth.y)));
}

// sun[0..2] Richtung zur Sonne (normiert), sun[3] Beleuchtungsstaerke am Boden
PYR_STAGE_SUN {
    float3 eye = v3(0.f, EARTH_R + 100.f, 0.f);
    float3 sd = v3(sun[0], sun[1], sun[2]);
    float3 t = transmittance(optical_depth(eye, sd, 32));
    float lum = 0.2126f * t.x + 0.7152f * t.y + 0.0722f * t.z;
    float e0 = sun[3] / fmaxf(lum, 1e-4f);
    out[0] = e0 * t.x;
    out[1] = e0 * t.y;
    out[2] = e0 * t.z;
}

__device__ inline float hash31(float x, float y, float z) {
    unsigned h = (unsigned)(int)x * 0x8da6b343u + (unsigned)(int)y * 0xd8163841u + (unsigned)(int)z * 0xcb1ab31fu;
    h ^= h >> 15; h *= 0x2c1b3c6du; h ^= h >> 12; h *= 0x297a2d39u; h ^= h >> 15;
    return (float)(h >> 8) * (1.f / 16777216.f);
}

PYR_STAGE_SKY {
    float3 eye = v3(0.f, EARTH_R + 100.f, 0.f);
    float3 sd = v3(sun[0], sun[1], sun[2]);
    float3 d = v3(dx, dy, dz);
    // Strahlen unter dem Horizont: von der Horizontrichtung aus auswerten
    float3 dd = dy < 0.f ? v3(dx, 0.f, dz) : d;
    float3 b_r = beta_r();
    float e0;
    {
        float3 ts = transmittance(optical_depth(eye, sd, 32));
        float lum = 0.2126f * ts.x + 0.7152f * ts.y + 0.0722f * ts.z;
        e0 = sun[3] / fmaxf(lum, 1e-4f);
    }
    float len = to_top(eye, dd);
    const int steps = 24;
    float ds = len / (float)steps;
    float dr = 0.f, dm = 0.f;
    float3 sum_r = v3(0.f, 0.f, 0.f), sum_m = v3(0.f, 0.f, 0.f);
    for (int i = 0; i < steps; i++) {
        float t = ((float)i + 0.5f) * ds;
        float3 p = v3(eye.x + dd.x * t, eye.y + dd.y * t, eye.z + dd.z * t);
        float hgt = sqrtf(dot3(p, p)) - EARTH_R;
        float rr = __expf(-hgt / H_R) * ds;
        float mm = __expf(-hgt / H_M) * ds;
        dr += rr; dm += mm;
        if (hits_ground(p, sd)) continue; // im Erdschatten
        float2 ls = optical_depth(p, sd, 8);
        float tr = dr + ls.x, tm = dm + ls.y;
        float ax = __expf(-(b_r.x * tr + BETA_M * 1.1f * tm));
        float ay = __expf(-(b_r.y * tr + BETA_M * 1.1f * tm));
        float az = __expf(-(b_r.z * tr + BETA_M * 1.1f * tm));
        sum_r.x += rr * ax; sum_r.y += rr * ay; sum_r.z += rr * az;
        sum_m.x += mm * ax; sum_m.y += mm * ay; sum_m.z += mm * az;
    }
    float mu = dot3(dd, sd);
    float phase_r = 3.f / (16.f * PI_F) * (1.f + mu * mu);
    float g2 = MIE_G * MIE_G;
    float phase_m = 3.f / (8.f * PI_F) * ((1.f - g2) * (1.f + mu * mu)) / ((2.f + g2) * powf(1.f + g2 - 2.f * MIE_G * mu, 1.5f));
    // Pyrit fuehrt Lichtstaerken durch Pi geteilt; die Strahldichte braucht die ungeteilte
    float k = PI_F * e0 * PYR_OPT(3);
    float cx = k * (sum_r.x * b_r.x * phase_r + sum_m.x * BETA_M * phase_m);
    float cy = k * (sum_r.y * b_r.y * phase_r + sum_m.y * BETA_M * phase_m);
    float cz = k * (sum_r.z * b_r.z * phase_r + sum_m.z * BETA_M * phase_m);
    // Mehrfachstreuung faerbt den Horizont zum Weiss: Farbe zur Helligkeit hin mischen
    {
        float lum = 0.2126f * cx + 0.7152f * cy + 0.0722f * cz;
        float h = 1.f - fminf(fabsf(dy), 1.f);
        float w = 0.25f * h * h * h;
        cx += (lum - cx) * w; cy += (lum - cy) * w; cz += (lum - cz) * w;
    }
    // Sterne: nur bei tiefer Sonne, harte kleine Punkte aus einem Zellenhash
    float night = fminf(fmaxf((0.04f - sun[1]) / 0.2f, 0.f), 1.f);
    if (night > 0.f && dy > 0.f && PYR_OPT(4) > 0.f) {
        float s = 400.f;
        float h = hash31(floorf(dx * s), floorf(dy * s), floorf(dz * s));
        if (h > 0.9985f) {
            float b = PYR_OPT(4) * night * (0.4f + 1.6f * (h - 0.9985f) / 0.0015f);
            cx += b; cy += b * 0.95f; cz += b * 1.05f;
        }
    }
    if (dy < 0.f) {
        // unter dem Horizont: langsam dunkler statt hartem Band
        float f = fmaxf(1.f + dy * 1.5f, 0.35f);
        cx *= f; cy *= f; cz *= f;
    }
    out[0] = cx; out[1] = cy; out[2] = cz;
}
