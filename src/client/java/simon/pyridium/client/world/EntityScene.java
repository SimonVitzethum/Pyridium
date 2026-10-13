package simon.pyridium.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import simon.pyridium.client.backend.PydTexture;
import simon.pyridium.client.backend.PydTextureView;
import simon.pyridium.client.gpu.Native;
import simon.pyridium.client.mixin.ModelPartAccessor;

/** Real Vanilla model parts and animation poses, rendered exclusively by Pyrit.
 * Textured surfaces are cached CUDA-built DAGs at 32 voxels/block. This bridge
 * covers models, items and quad-based custom geometry, not every feature submit.
 */
public final class EntityScene {
    private static final int MAX_MODEL_PARTS = 1 << 20;
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<Object, Geometry> GEOMETRIES = new HashMap<>();
    private static final Map<InstanceKey, Identity> IDENTITIES = new HashMap<>();
    private static ByteBuffer instances;
    private static long nextIdentity = 1, frame, nextLog;
    private static int instanceCount;
    private record CubeKey(ModelPart.Cube cube, PydTexture texture, int tint, float u0, float u1, float v0, float v1) {}
    private record ItemFace(BakedQuad quad, PydTexture texture, int tint) {}
    private record ItemKey(List<ItemFace> faces) {}
    private record CustomKey(RenderType type, List<Surface> faces) {}
    private record Vertex(float x, float y, float z, float u, float v, int color) {}
    private record Surface(PydTexture texture, List<Vertex> vertices) {}
    private static final class Geometry {
        final long handle;
        final float x, y, z;
        long seen;
        Geometry(long handle, float x, float y, float z) { this.handle = handle; this.x = x; this.y = y; this.z = z; }
    }
    private record InstanceKey(boolean blockEntity, long owner, Object part, RenderType renderType, int occurrence) {}
    private static final class Identity { final long id = nextIdentity++; long seen; }

    private EntityScene() {}

    public static void reset() {
        GEOMETRIES.clear();
        IDENTITIES.clear();
        if (instances != null) MemoryUtil.memFree(instances);
        instances = null;
        nextIdentity = 1;
        frame = nextLog = 0;
    }

    public static void render(CameraRenderState camera, Vec3 renderPosition) {
        Minecraft mc = Minecraft.getInstance();
        var state = mc.gameRenderer.gameRenderState().levelRenderState;
        frame++;
        instanceCount = 0;
        Collector collector = new Collector(camera, renderPosition);
        PoseStack poses = new PoseStack();
        for (EntityRenderState entity : state.entityRenderStates) {
            collector.blockEntity = false;
            collector.owner = ((PyritEntityState)entity).pyridium$id();
            collector.occurrences.clear();
            if (collector.owner == 0) throw new IllegalStateException("Entity-Renderzustand ohne stabile Identität");
            mc.levelRenderer.entityRenderDispatcher().submit(entity, camera,
                entity.x - camera.pos.x, entity.y - camera.pos.y, entity.z - camera.pos.z, poses, collector);
        }
        for (var block : state.blockEntityRenderStates) {
            collector.blockEntity = true;
            collector.owner = block.blockPos.asLong();
            collector.occurrences.clear();
            poses.pushPose();
            poses.translate(block.blockPos.getX() - camera.pos.x, block.blockPos.getY() - camera.pos.y, block.blockPos.getZ() - camera.pos.z);
            mc.levelRenderer.blockEntityRenderDispatcher().submit(block, poses, collector, camera);
            poses.popPose();
        }
        if (instances == null) instances = MemoryUtil.memAlloc(64 * 256);
        LodStructureLayer.renderHook(camera, renderPosition);   // Strukturschicht des LOD (eigene Instanzen, siehe LodStructureLayer)
        Native.modelsFrame(MemoryUtil.memAddress(instances), instanceCount);
        IDENTITIES.entrySet().removeIf(e -> frame - e.getValue().seen > 120);
        // Native instances expire after 120 unseen frames. Evict later so an
        // inactive instance cannot still hold a reference to the geometry.
        if (frame % 60 == 0) GEOMETRIES.entrySet().removeIf(e -> {
            if (frame - e.getValue().seen <= 240) return false;
            if (e.getValue().handle != 0) Native.modelDestroy(e.getValue().handle);
            return true;
        });
        int unhandledGroups = 0;
        for (var phase : collector.allPhases()) if (!phase.isEmpty()) unhandledGroups++;
        if (System.currentTimeMillis() >= nextLog) {
            nextLog = System.currentTimeMillis() + 10000;
            LOG.info("Pyridium: {} Entities, {} Block-Entities, {} Modellteile, {} gecachte DAGs; {} Item- und {} Custom-Instanzen; {} noch nicht übernommene Featuregruppen",
                state.entityRenderStates.size(), state.blockEntityRenderStates.size(), instanceCount, GEOMETRIES.size(),
                collector.itemInstances, collector.customInstances, unhandledGroups);
        }
        // These snapshots are consumed exactly once, just as Vanilla submitFeatures does.
        state.entityRenderStates.clear();
        state.blockEntityRenderStates.clear();
    }

    private static final class Collector extends SubmitNodeCollection implements SubmitNodeCollector {
        final CameraRenderState camera;
        final Vec3 renderPosition;
        final Map<Object, Integer> occurrences = new HashMap<>();
        final Map<RenderType, PydTexture> textures = new HashMap<>();
        boolean blockEntity;
        long owner;
        int itemInstances, customInstances;

        Collector(CameraRenderState camera, Vec3 renderPosition) {
            super(false, new TranslucentFeatureRenderPhase());
            this.camera = camera;
            this.renderPosition = renderPosition;
        }

        @Override public OrderedSubmitNodeCollector order(int order) { return this; }

        // Pyrit traces physical shadows from the same model geometry. Do not
        // enqueue Vanilla's duplicate screen-space shadow decals.
        @Override public void submitShadow(PoseStack poses, float radius, java.util.List<EntityRenderState.ShadowPiece> pieces) {}

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poses, RenderType renderType,
            int light, int overlay, int tint, UvMapping uv, int outline) {
            if (renderType.isOutline()) return;
            PydTexture texture = texture(renderType);
            if (texture == null) return;
            model.setupAnim(state);
            visit(model.root(), poses, renderType, texture, tint, uv);
        }

        private void visit(ModelPart part, PoseStack poses, RenderType type, PydTexture texture, int tint, UvMapping uv) {
            if (!part.visible) return;
            poses.pushPose();
            part.translateAndRotate(poses);
            ModelPartAccessor access = (ModelPartAccessor)(Object)part;
            if (!part.skipDraw) for (ModelPart.Cube cube : access.pyridium$cubes()) {
                if (cube.polygons.length == 0) continue;
                CubeKey key = new CubeKey(cube, texture, tint,
                    uv == null ? 0 : uv.getU(0), uv == null ? 1 : uv.getU(1),
                    uv == null ? 0 : uv.getV(0), uv == null ? 1 : uv.getV(1));
                Geometry geo = GEOMETRIES.computeIfAbsent(key, k -> createGeometry(cubeSurfaces(key, uv)));
                submitGeometry(geo, cube, type, poses);
            }
            for (ModelPart child : access.pyridium$children().values()) visit(child, poses, type, texture, tint, uv);
            poses.popPose();
        }

        private PydTexture texture(RenderType type) {
            if (textures.containsKey(type)) return textures.get(type);
            PydTexture result = null;
            for (var binding : type.prepare().textures()) {
                if (binding.name().equals("Sampler0") && binding.textureView() instanceof PydTextureView view) {
                    PydTexture p = view.pyd();
                    if (p.bpp == 4 && !p.isClosed()) result = p;
                    break;
                }
            }
            textures.put(type, result);
            return result;
        }

        @Override
        public void submitItem(PoseStack poses, ItemDisplayContext display, int light, int overlay, int outline,
            int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foil) {
            List<ItemFace> faces = new ArrayList<>(quads.all().size());
            for (BakedQuad quad : quads.all()) {
                var material = quad.materialInfo();
                PydTexture texture = texture(material.itemRenderType());
                if (texture == null) continue;
                int i = material.tintIndex();
                faces.add(new ItemFace(quad, texture, i >= 0 && i < tints.length ? tints[i] : -1));
            }
            if (faces.isEmpty()) return;
            ItemKey key = new ItemKey(List.copyOf(faces));
            Geometry geo = GEOMETRIES.computeIfAbsent(key, k -> {
                List<Surface> surfaces = new ArrayList<>(faces.size());
                for (ItemFace face : faces) {
                    List<Vertex> vertices = new ArrayList<>(4);
                    for (int c = 0; c < 4; c++) {
                        var p = face.quad.position(c);
                        long uv = face.quad.packedUV(c);
                        vertices.add(new Vertex(p.x(), p.y(), p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv), face.tint));
                    }
                    surfaces.add(new Surface(face.texture, List.copyOf(vertices)));
                }
                return createGeometry(surfaces);
            });
            // Submission slot remains stable even when the held item changes.
            submitGeometry(geo, display, null, poses);
            if (geo.handle != 0) itemInstances++;
        }

        @Override
        public void submitCustomGeometry(PoseStack poses, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            if (type.isOutline()) return;
            if (type.primitiveTopology() != PrimitiveTopology.QUADS) {
                super.submitCustomGeometry(poses, type, renderer);
                return;
            }
            // Emit original local vertices, never camera-relative vertices: moving
            // a billboard/item must change its pose, not rebuild its GPU DAG.
            SurfaceConsumer consumer = new SurfaceConsumer(texture(type));
            renderer.render(new PoseStack().last(), consumer);
            List<Surface> surfaces = consumer.finish();
            if (surfaces.isEmpty()) return;
            CustomKey key = new CustomKey(type, surfaces);
            Geometry geo = GEOMETRIES.computeIfAbsent(key, k -> createGeometry(surfaces));
            submitGeometry(geo, type, type, poses);
            if (geo.handle != 0) customInstances++;
        }

        private void submitGeometry(Geometry geo, Object part, RenderType type, PoseStack poses) {
            geo.seen = frame;
            int occurrence = occurrences.merge(part, 1, Integer::sum);
            if (geo.handle == 0) return;
            InstanceKey instance = new InstanceKey(blockEntity, owner, part, type, occurrence);
            Identity identity = IDENTITIES.computeIfAbsent(instance, k -> new Identity());
            identity.seen = frame;
            Matrix4f transform = new Matrix4f(poses.last().pose()).translate(geo.x / 32, geo.y / 32, geo.z / 32).scale(1f / 32);
            double ox = Math.floor(renderPosition.x / 1024) * 1024;
            double oz = Math.floor(renderPosition.z / 1024) * 1024;
            transform.m30(transform.m30() + (float)(camera.pos.x - ox));
            transform.m31(transform.m31() + (float)camera.pos.y);
            transform.m32(transform.m32() + (float)(camera.pos.z - oz));
            append(identity.id, geo.handle, transform);
        }
    }

    private static List<Surface> cubeSurfaces(CubeKey key, UvMapping uv) {
        List<Surface> surfaces = new ArrayList<>(key.cube.polygons.length);
        for (var polygon : key.cube.polygons) {
            List<Vertex> vertices = new ArrayList<>(4);
            for (var v : polygon.vertices()) vertices.add(new Vertex(v.x() / 16, v.y() / 16, v.z() / 16,
                uv == null ? v.u() : uv.getU(v.u()), uv == null ? v.v() : uv.getV(v.v()), key.tint));
            surfaces.add(new Surface(key.texture, List.copyOf(vertices)));
        }
        return surfaces;
    }

    /** Captures metadata only. Sampling, texturing, alpha and DAG construction run on CUDA. */
    private static final class SurfaceConsumer implements VertexConsumer {
        final PydTexture texture;
        final List<Surface> faces = new ArrayList<>();
        final List<Vertex> vertices = new ArrayList<>(4);
        float x, y, z, u, v;
        int color;
        boolean pending;
        SurfaceConsumer(PydTexture texture) { this.texture = texture; }
        private void flush() {
            if (!pending) return;
            vertices.add(new Vertex(x, y, z, u, v, color));
            if (vertices.size() == 4) {
                faces.add(new Surface(texture, List.copyOf(vertices)));
                vertices.clear();
            }
            pending = false;
        }
        List<Surface> finish() {
            flush();
            if (!vertices.isEmpty()) throw new IllegalArgumentException("Unvollständige Custom-Quad-Geometrie");
            return List.copyOf(faces);
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            flush(); this.x = x; this.y = y; this.z = z; u = v = 0; color = -1; pending = true; return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { color = a << 24 | r << 16 | g << 8 | b; return this; }
        @Override public VertexConsumer setColor(int argb) { color = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    private static Geometry createGeometry(List<Surface> surfaces) {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (Surface face : surfaces) for (Vertex v : face.vertices) {
            float[] p = {v.x * 32, v.y * 32, v.z * 32};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], p[a]); max[a] = Math.max(max[a], p[a]); }
        }
        int size = 8, log2 = 3;
        float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        while (size < extent && log2 < 9) { size *= 2; log2++; }
        if (!Float.isFinite(extent) || extent > size || surfaces.size() > 8192) {
            LOG.warn("Pyridium: Geometrie überschreitet DAG-Grenzen: {} Voxel, {} Quads", extent, surfaces.size());
            return new Geometry(0, 0, 0, 0);
        }
        ByteBuffer faces = MemoryUtil.memCalloc(surfaces.size() * 128);
        int count = 0, fi = 0;
        try {
            for (Surface surface : surfaces) {
                var vs = surface.vertices;
                int offset = fi++ * 128;
                faces.putLong(offset, surface.texture == null ? 0 : surface.texture.ptr);
                faces.putInt(offset + 8, surface.texture == null ? 1 : surface.texture.getWidth(0));
                faces.putInt(offset + 12, surface.texture == null ? 1 : surface.texture.getHeight(0));
                faces.putInt(offset + 16, count);
                for (int c = 0; c < 4; c++) {
                    var v = vs.get(c);
                    faces.putFloat(offset + 32 + c * 12, v.x * 32 - min[0]);
                    faces.putFloat(offset + 36 + c * 12, v.y * 32 - min[1]);
                    faces.putFloat(offset + 40 + c * 12, v.z * 32 - min[2]);
                    faces.putFloat(offset + 80 + c * 8, v.u);
                    faces.putFloat(offset + 84 + c * 8, v.v);
                    faces.putInt(offset + 112 + c * 4, v.color);
                }
                // Half-voxel spacing reduces holes on diagonal/non-axis-aligned surfaces.
                int nu = Math.clamp((int)Math.ceil(Math.max(distance(vs.get(0), vs.get(1)), distance(vs.get(3), vs.get(2))) * 64), 1, 512);
                int nv = Math.clamp((int)Math.ceil(Math.max(distance(vs.get(0), vs.get(3)), distance(vs.get(1), vs.get(2))) * 64), 1, 512);
                faces.putInt(offset + 20, nu);
                faces.putInt(offset + 24, nv);
                count = Math.addExact(count, nu * nv);
            }
            if (count > 1 << 20) {
                LOG.warn("Pyridium: Geometrie überschreitet Samplebudget: {}", count);
                return new Geometry(0, 0, 0, 0);
            }
            long handle = Native.modelCreate(MemoryUtil.memAddress(faces), fi, count, log2);
            if (handle == 0) LOG.warn("Pyridium: leere oder fehlgeschlagene Modell-DAG: {}", Native.error());
            return new Geometry(handle, min[0], min[1], min[2]);
        } finally { MemoryUtil.memFree(faces); }
    }

    private static float distance(Vertex a, Vertex b) {
        float x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    /** Strukturschicht: Instanz mit fertiger Zeilen-3x4-Matrix (Render-Ursprung bereits abgezogen) */
    static void appendExternal(long id, long geometry, float[] m) {
        if (instanceCount >= MAX_MODEL_PARTS) return;
        if (instances == null) instances = MemoryUtil.memAlloc(64 * 256);
        if ((instanceCount + 1) * 64 > instances.capacity()) instances = MemoryUtil.memRealloc(instances, instances.capacity() * 2);
        int o = instanceCount++ * 64;
        instances.putLong(o, id);
        instances.putLong(o + 8, geometry);
        for (int i = 0; i < 12; i++) instances.putFloat(o + 16 + i * 4, m[i]);
    }

    private static void append(long id, long geometry, Matrix4f m) {
        if (instanceCount >= MAX_MODEL_PARTS) throw new IllegalStateException("Entity-Modellteilkapazität überschritten: " + MAX_MODEL_PARTS);
        if (instances == null) instances = MemoryUtil.memAlloc(64 * 256);
        if ((instanceCount + 1) * 64 > instances.capacity()) instances = MemoryUtil.memRealloc(instances, instances.capacity() * 2);
        int o = instanceCount++ * 64;
        instances.putLong(o, id);
        instances.putLong(o + 8, geometry);
        // JOML columns -> Pyrit row-major 3x4.
        instances.putFloat(o + 16, m.m00()).putFloat(o + 20, m.m10()).putFloat(o + 24, m.m20()).putFloat(o + 28, m.m30());
        instances.putFloat(o + 32, m.m01()).putFloat(o + 36, m.m11()).putFloat(o + 40, m.m21()).putFloat(o + 44, m.m31());
        instances.putFloat(o + 48, m.m02()).putFloat(o + 52, m.m12()).putFloat(o + 56, m.m22()).putFloat(o + 60, m.m32());
    }
}
