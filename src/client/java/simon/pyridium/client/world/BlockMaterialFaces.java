package simon.pyridium.client.world;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import simon.pyridium.client.mixin.SpriteContentsAccessor;

/** CPU material preparation only; tracing, filtering and PBR run on CUDA.
 * Six faces use -X,+X,-Y,+Y,-Z,+Z. Color contains untinted faces then tinted
 * contributions, so grass overlays never tint their dirt backing a second time.
 */
final class BlockMaterialFaces {
    static final Direction[] DIRECTIONS = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    record Image(int[] pixels, int w, int h) {
        int sample(float u, float v) {
            int x = Math.clamp((int)Math.floor(u * w), 0, w - 1);
            int y = Math.clamp((int)Math.floor(v * h), 0, h - 1);
            return pixels[y * w + x];
        }
    }
    record Maps(Image color, Image normal, Image specular) {}
    record Face(int size, int[] color, int[] tint, int[] normal, int[] specular, boolean tinted, boolean cutout, boolean pbrNormal, boolean pbrSpecular, int contentHash) {
        Face(int size, int[] color, int[] tint, int[] normal, int[] specular, boolean tinted, boolean cutout, boolean pbrNormal, boolean pbrSpecular) {
            this(size, color, tint, normal, specular, tinted, cutout, pbrNormal, pbrSpecular,
                Objects.hash(size, tinted, cutout, pbrNormal, pbrSpecular, Arrays.hashCode(color), Arrays.hashCode(tint),
                    pbrNormal ? Arrays.hashCode(normal) : 0, pbrSpecular ? Arrays.hashCode(specular) : 0));
        }
        @Override public int hashCode() { return contentHash; }
        @Override public boolean equals(Object o) {
            if (o == this) return true;
            return o instanceof Face f && contentHash == f.contentHash && size == f.size && tinted == f.tinted && cutout == f.cutout
                && pbrNormal == f.pbrNormal && pbrSpecular == f.pbrSpecular && Arrays.equals(color, f.color) && Arrays.equals(tint, f.tint)
                && (!pbrNormal || Arrays.equals(normal, f.normal)) && (!pbrSpecular || Arrays.equals(specular, f.specular));
        }
    }
    record FaceKey(List<BakedQuad> quads, int size) {}
    record Atlas(int size, int[] color, int[] normal, int[] specular, boolean tinted, boolean cutout) {}
    private final Map<TextureAtlasSprite, Maps> sprites = new IdentityHashMap<>();
    private final Map<FaceKey, Face> faces = new HashMap<>();
    private final Map<Face, Face> canonicalFaces = new HashMap<>();
    private final Map<TextureAtlasSprite, Face> particles = new IdentityHashMap<>();
    private final int limit = Integer.highestOneBit(Math.clamp(Integer.getInteger("pyridium.maxTextureSize", 128), 16, 256));
    int normalMaps, specularMaps;

    List<Face> faces(List<BlockStateModelPart> parts, TextureAtlasSprite fallback) {
        List<List<BakedQuad>> qs = new ArrayList<>(6);
        int size = 16;
        for (Direction d : DIRECTIONS) {
            List<BakedQuad> quads = new ArrayList<>();
            for (var part : parts) {
                quads.addAll(part.getQuads(d));
                for (var q : part.getQuads(null)) if (q.direction() == d) quads.add(q);
            }
            for (var q : quads) size = Math.max(size, Math.min(limit, Math.max(q.materialInfo().sprite().contents().width(), q.materialInfo().sprite().contents().height())));
            qs.add(List.copyOf(quads));
        }
        // Power-of-two tiles keep the face boundaries aligned throughout mips.
        size = Math.min(limit, Integer.highestOneBit(size));
        List<Face> out = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            if (qs.get(i).isEmpty()) out.add(particles.computeIfAbsent(fallback, this::particle));
            else {
                FaceKey key = new FaceKey(qs.get(i), size);
                Direction direction = DIRECTIONS[i];
                out.add(faces.computeIfAbsent(key, k -> intern(bake(k, direction))));
            }
        }
        return out;
    }

    private Face intern(Face f) { return canonicalFaces.computeIfAbsent(f, k -> k); }

    private Maps maps(TextureAtlasSprite sprite) {
        return sprites.computeIfAbsent(sprite, s -> {
            var c = s.contents();
            NativeImage img = ((SpriteContentsAccessor)c).pyridium$originalImage();
            Image color = copy(img, c.width(), c.height());
            Identifier id = c.name();
            Image normal = resource(id, "_n"), spec = resource(id, "_s");
            if (normal != null) normalMaps++;
            if (spec != null) specularMaps++;
            return new Maps(color, normal, spec);
        });
    }

    private Image resource(Identifier sprite, String suffix) {
        var id = Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + suffix + ".png");
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (var stream = resource.get().open(); var img = NativeImage.read(stream)) {
            // First animation frame, like the albedo sprite. Independent map resolutions are valid.
            int h = Math.min(img.getHeight(), img.getWidth());
            return copy(img, img.getWidth(), h);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable PBR map " + id, e);
        }
    }

    private static Image copy(NativeImage image, int w, int h) {
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = image.getPixel(x, y);
        return new Image(pixels, w, h);
    }

    private Face particle(TextureAtlasSprite sprite) {
        Maps m = maps(sprite);
        int size = Math.max(16, Math.min(limit, Integer.highestOneBit(m.color.w)));
        int[] color = new int[size * size], tint = new int[color.length];
        int[] normal = new int[color.length], spec = new int[color.length];
        boolean cutout = false;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float u = (x + .5f) / size, v = (y + .5f) / size;
            int i = y * size + x;
            color[i] = m.color.sample(u, v); cutout |= color[i] >>> 24 < 128;
            normal[i] = m.normal == null ? 0xff8080ff : m.normal.sample(u, v);
            spec[i] = canonicalSpec(m.specular == null ? 0xff140a00 : m.specular.sample(u, v));
        }
        return intern(new Face(size, color, tint, m.normal == null ? null : normal, m.specular == null ? null : spec, false, cutout, m.normal != null, m.specular != null));
    }

    private Face bake(FaceKey key, Direction direction) {
        int size = key.size, n = size * size;
        int[] color = new int[n], tint = new int[n], normal = new int[n], spec = new int[n];
        Arrays.fill(normal, 0xff8080ff); Arrays.fill(spec, 0x00140a00);
        boolean tinted = false, hasNormal = false, hasSpec = false;
        float[] projected = new float[8], uv = new float[8], sample = new float[6];
        for (BakedQuad q : key.quads) {
            var sprite = q.materialInfo().sprite();
            var m = maps(sprite);
            boolean qt = q.materialInfo().isTinted();
            tinted |= qt; hasNormal |= m.normal != null; hasSpec |= m.specular != null;
            for (int c = 0; c < 4; c++) {
                var p = q.position(c);
                projected[c * 2] = direction.getAxis() == Direction.Axis.X ? p.z() : p.x();
                projected[c * 2 + 1] = direction.getAxis() == Direction.Axis.Y ? p.z() : p.y();
                long packed = q.packedUV(c);
                uv[c * 2] = (UVPair.unpackU(packed) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                uv[c * 2 + 1] = (UVPair.unpackV(packed) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
            }
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                if (!FaceRaster.interpolate(projected, uv, (x + .5f) / size, 1 - (y + .5f) / size, sample)) continue;
                int i = y * size + x, src = m.color.sample(sample[0], sample[1]);
                float a = (src >>> 24) / 255f;
                if (a == 0) continue;
                color[i] = contribution(color[i], qt ? 0 : src, a);
                tint[i] = contribution(tint[i], qt ? src : 0, a);
                int alpha = Math.round((a + (color[i] >>> 24) / 255f * (1 - a)) * 255);
                color[i] = (color[i] & 0xffffff) | alpha << 24;
                int nm = m.normal == null ? 0xff8080ff : m.normal.sample(sample[0], sample[1]);
                float nx = (((nm >>> 16) & 255) - 128) / 127f, ny = (((nm >>> 8) & 255) - 128) / 127f;
                int rx = Math.clamp(Math.round(128 + 127 * (nx * sample[2] + ny * sample[4])), 0, 255);
                int ry = Math.clamp(Math.round(128 - 127 * (nx * sample[3] + ny * sample[5])), 0, 255);
                nm = (nm & 0xff0000ff) | rx << 16 | ry << 8;
                normal[i] = channels(normal[i], nm, a);
                spec[i] = channels(spec[i], canonicalSpec(m.specular == null ? 0xff140a00 : m.specular.sample(sample[0], sample[1])), a);
            }
        }
        boolean cutout = false;
        // Contributions are premultiplied while layering. Divide by coverage
        // afterwards so filtered mip colors do not darken transparent leaves.
        for (int i = 0; i < n; i++) {
            int alpha = color[i] >>> 24; cutout |= alpha < 128;
            if (alpha != 0 && alpha != 255) {
                color[i] = unpremultiply(color[i], alpha);
                tint[i] = unpremultiply(tint[i], alpha);
            }
        }
        fillTransparentColor(color, color);
        fillTransparentColor(tint, color);
        return new Face(size, color, tint, hasNormal ? normal : null, hasSpec ? spec : null, tinted, cutout, hasNormal, hasSpec);
    }

    private static void fillTransparentColor(int[] pixels, int[] coverage) {
        long r = 0, g = 0, b = 0, count = 0;
        for (int i = 0; i < pixels.length; i++) if (coverage[i] >>> 24 >= 128) {
            int c = pixels[i]; r += (c >>> 16) & 255; g += (c >>> 8) & 255; b += c & 255; count++;
        }
        if (count == 0) return;
        int average = (int)(r / count) << 16 | (int)(g / count) << 8 | (int)(b / count);
        for (int i = 0; i < pixels.length; i++) if (coverage[i] >>> 24 == 0) pixels[i] = (pixels[i] & 0xff000000) | average;
    }

    private static int canonicalSpec(int argb) { return argb >>> 24 == 255 ? argb & 0xffffff : argb; }
    private static int unpremultiply(int c, int a) {
        int result = c & 0xff000000;
        for (int shift : new int[]{0, 8, 16}) result |= Math.min(255, ((c >>> shift) & 255) * 255 / a) << shift;
        return result;
    }
    private static int contribution(int dst, int src, float opacity) {
        return (dst & 0xff000000) | (channels(dst, src, opacity) & 0xffffff);
    }
    private static int channels(int dst, int src, float opacity) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8)
            result |= Math.round(((src >>> shift) & 255) * opacity + ((dst >>> shift) & 255) * (1 - opacity)) << shift;
        return result;
    }

    static Atlas atlas(List<Face> faces) {
        int size = 16;
        boolean tinted = false, cutout = false, normal = false, spec = false;
        for (Face f : faces) { size = Math.max(size, f.size); tinted |= f.tinted; cutout |= f.cutout; normal |= f.pbrNormal; spec |= f.pbrSpecular; }
        int[] c = new int[size * size * 12], nm = normal ? new int[size * size * 6] : null, sm = spec ? new int[size * size * 6] : null;
        for (int k = 0; k < 6; k++) {
            Face f = faces.get(k);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int src = y * f.size / size * f.size + x * f.size / size;
                int dst = (k * size + y) * size + x;
                c[dst] = f.color[src]; c[dst + size * size * 6] = f.tint[src];
                if (nm != null) nm[dst] = f.normal == null ? 0xff8080ff : f.normal[src];
                if (sm != null) sm[dst] = f.specular == null ? 0x00140a00 : f.specular[src];
            }
        }
        return new Atlas(size, c, nm, sm, tinted, cutout);
    }
}
