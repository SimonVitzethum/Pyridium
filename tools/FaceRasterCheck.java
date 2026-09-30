package simon.pyridium.client.world;

/** Standalone regression: uses the production projector, no Minecraft/GPU/window. */
public final class FaceRasterCheck {
    static void close(float actual, float expected) {
        if (Math.abs(actual - expected) > 1e-5f) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        float[] p = {0, 0, 1, 0, 1, 1, 0, 1}, out = new float[6];
        float[] ordinary = {0, 1, 1, 1, 1, 0, 0, 0};
        if (!FaceRaster.interpolate(p, ordinary, .25f, .75f, out)) throw new AssertionError();
        close(out[0], .25f); close(out[1], .25f);
        close(out[2], 1); close(out[3], 0); close(out[4], 0); close(out[5], -1);
        float[] rotated = {1, 1, 1, 0, 0, 0, 0, 1};
        if (!FaceRaster.interpolate(p, rotated, .25f, .75f, out)) throw new AssertionError();
        close(out[0], .25f); close(out[1], .75f);
        close(out[2], 0); close(out[3], -1); close(out[4], -1); close(out[5], 0);
        float[] crop = {.25f, .75f, .75f, .75f, .75f, .25f, .25f, .25f};
        if (!FaceRaster.interpolate(p, crop, .5f, .5f, out)) throw new AssertionError();
        close(out[0], .5f); close(out[1], .5f);
        float[] half = {0, 0, .5f, 0, .5f, 1, 0, 1};
        if (FaceRaster.interpolate(half, ordinary, .75f, .5f, out)) throw new AssertionError("outside quad");
        if (!FaceRaster.interpolate(half, ordinary, .25f, .75f, out)) throw new AssertionError();
        close(out[0], .5f); close(out[1], .25f);
        // Non-parallelogram: second triangle must follow Vanilla's 0->2 diagonal.
        float[] skew = {0, 0, 1, 0, .8f, 1, 0, .5f};
        if (!FaceRaster.interpolate(skew, ordinary, .16f, .35f, out)) throw new AssertionError();
        close(out[0], .2f); close(out[1], .5f);
        System.out.println("PASS: ordinary, rotated, cropped, partial and skewed block-face UVs/tangents");
    }
}
