package simon.pyridium.client.world;

/** Inverse quad projection, shared by albedo and every material map.
 * Coordinates are face-plane (u, v-up); source UVs are image (u, v-down).
 * Diagonal 0->2 matches Vanilla. No dependency on a graphics backend.
 */
final class FaceRaster {
    private FaceRaster() {}

    // out: source u/v, unit face-plane tangent for source u, then source v.
    static boolean interpolate(float[] plane, float[] uv, float x, float y, float[] out) {
        return triangle(plane, uv, 0, 1, 2, x, y, out)
            || triangle(plane, uv, 0, 2, 3, x, y, out);
    }

    private static boolean triangle(float[] p, float[] uv, int a, int b, int c, float x, float y, float[] out) {
        float ax = p[a * 2], ay = p[a * 2 + 1];
        float bx = p[b * 2] - ax, by = p[b * 2 + 1] - ay;
        float cx = p[c * 2] - ax, cy = p[c * 2 + 1] - ay;
        float det = bx * cy - by * cx;
        if (Math.abs(det) < 1e-8f) return false;
        float s = ((x - ax) * cy - (y - ay) * cx) / det;
        float t = (bx * (y - ay) - by * (x - ax)) / det;
        if (s < -1e-5f || t < -1e-5f || s + t > 1.00001f) return false;
        float bu = uv[b * 2] - uv[a * 2], bv = uv[b * 2 + 1] - uv[a * 2 + 1];
        float cu = uv[c * 2] - uv[a * 2], cv = uv[c * 2 + 1] - uv[a * 2 + 1];
        out[0] = uv[a * 2] + bu * s + cu * t;
        out[1] = uv[a * 2 + 1] + bv * s + cv * t;
        float ud = bu * cv - bv * cu;
        if (Math.abs(ud) < 1e-8f) {
            out[2] = out[5] = 1; out[3] = out[4] = 0;
        } else {
            float ux = (bx * cv - cx * bv) / ud, uy = (by * cv - cy * bv) / ud;
            float vx = (cx * bu - bx * cu) / ud, vy = (cy * bu - by * cu) / ud;
            float ul = (float)Math.hypot(ux, uy), vl = (float)Math.hypot(vx, vy);
            out[2] = ux / Math.max(ul, 1e-8f); out[3] = uy / Math.max(ul, 1e-8f);
            out[4] = vx / Math.max(vl, 1e-8f); out[5] = vy / Math.max(vl, 1e-8f);
        }
        return true;
    }
}
