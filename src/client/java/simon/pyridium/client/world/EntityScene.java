package simon.pyridium.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
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
 * covers Model submissions (including armor layers), not every feature submit.
 */
public final class EntityScene {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<GeometryKey, Geometry> GEOMETRIES = new HashMap<>();
    private static final Map<InstanceKey, Identity> IDENTITIES = new HashMap<>();
    private static ByteBuffer instances;
    private static long nextIdentity = 1, frame, nextLog;
    private static int instanceCount;
    private record GeometryKey(ModelPart.Cube cube, PydTexture texture, int tint, float u0, float u1, float v0, float v1) {}
    private record Geometry(long handle, float x, float y, float z) {}
    private record InstanceKey(boolean blockEntity, long owner, ModelPart.Cube cube, RenderType renderType, int occurrence) {}
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
        Native.modelsFrame(MemoryUtil.memAddress(instances), instanceCount);
        IDENTITIES.entrySet().removeIf(e -> frame - e.getValue().seen > 120);
        if (System.currentTimeMillis() >= nextLog) {
            nextLog = System.currentTimeMillis() + 10000;
            LOG.info("Pyridium: {} Entities, {} Block-Entities, {} Modellteile, {} gecachte DAGs",
                state.entityRenderStates.size(), state.blockEntityRenderStates.size(), instanceCount, GEOMETRIES.size());
        }
        // These snapshots are consumed exactly once, just as Vanilla submitFeatures does.
        state.entityRenderStates.clear();
        state.blockEntityRenderStates.clear();
    }

    private static final class Collector extends SubmitNodeCollection implements SubmitNodeCollector {
        final CameraRenderState camera;
        final Vec3 renderPosition;
        final Map<ModelPart.Cube, Integer> occurrences = new HashMap<>();
        boolean blockEntity;
        long owner;

        Collector(CameraRenderState camera, Vec3 renderPosition) {
            super(false, new TranslucentFeatureRenderPhase());
            this.camera = camera;
            this.renderPosition = renderPosition;
        }

        @Override public OrderedSubmitNodeCollector order(int order) { return this; }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poses, RenderType renderType,
            int light, int overlay, int tint, UvMapping uv, int outline) {
            if (renderType.isOutline()) return;
            PydTexture texture = null;
            for (var binding : renderType.prepare().textures()) {
                if (binding.name().equals("Sampler0") && binding.textureView() instanceof PydTextureView view) {
                    texture = view.pyd();
                    break;
                }
            }
            if (texture == null || texture.bpp != 4 || texture.isClosed()) return;
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
                GeometryKey key = new GeometryKey(cube, texture, tint,
                    uv == null ? 0 : uv.getU(0), uv == null ? 1 : uv.getU(1),
                    uv == null ? 0 : uv.getV(0), uv == null ? 1 : uv.getV(1));
                Geometry geo = GEOMETRIES.computeIfAbsent(key, k -> createGeometry(k, uv));
                if (geo.handle == 0) continue; // fully transparent model surface
                int occurrence = occurrences.merge(cube, 1, Integer::sum);
                InstanceKey instance = new InstanceKey(blockEntity, owner, cube, type, occurrence);
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
            for (ModelPart child : access.pyridium$children().values()) visit(child, poses, type, texture, tint, uv);
            poses.popPose();
        }
    }

    private static Geometry createGeometry(GeometryKey key, UvMapping uv) {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var polygon : key.cube.polygons) for (var v : polygon.vertices()) {
            float[] p = {v.x() * 2, v.y() * 2, v.z() * 2};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], p[a]); max[a] = Math.max(max[a], p[a]); }
        }
        int size = 8, log2 = 3;
        float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        while (size < extent && log2 < 9) { size *= 2; log2++; }
        if (!Float.isFinite(extent) || extent > size) throw new IllegalArgumentException("Entity-Modellteil zu groß: " + extent);
        ByteBuffer faces = MemoryUtil.memCalloc(key.cube.polygons.length * 96);
        int count = 0, fi = 0;
        try {
            for (var polygon : key.cube.polygons) {
                var vs = polygon.vertices();
                int[] corners = {0, 1, 3};
                int offset = fi++ * 96;
                faces.putLong(offset, key.texture.ptr);
                faces.putInt(offset + 8, key.texture.getWidth(0));
                faces.putInt(offset + 12, key.texture.getHeight(0));
                faces.putInt(offset + 16, key.tint);
                faces.putInt(offset + 20, count);
                for (int c = 0; c < 3; c++) {
                    var v = vs[corners[c]];
                    faces.putFloat(offset + 32 + c * 12, v.x() * 2 - min[0]);
                    faces.putFloat(offset + 36 + c * 12, v.y() * 2 - min[1]);
                    faces.putFloat(offset + 40 + c * 12, v.z() * 2 - min[2]);
                    faces.putFloat(offset + 68 + c * 8, uv == null ? v.u() : uv.getU(v.u()));
                    faces.putFloat(offset + 72 + c * 8, uv == null ? v.v() : uv.getV(v.v()));
                }
                int nu = Math.max(1, (int)Math.ceil(distance(vs[0], vs[1]) * 2));
                int nv = Math.max(1, (int)Math.ceil(distance(vs[0], vs[3]) * 2));
                faces.putInt(offset + 24, nu);
                faces.putInt(offset + 28, nv);
                count = Math.addExact(count, nu * nv);
            }
            long handle = Native.modelCreate(MemoryUtil.memAddress(faces), fi, count, log2);
            if (handle == 0) LOG.warn("Pyridium: leere oder fehlgeschlagene Modell-DAG: {}", Native.error());
            return new Geometry(handle, min[0], min[1], min[2]);
        } finally { MemoryUtil.memFree(faces); }
    }

    private static float distance(ModelPart.Vertex a, ModelPart.Vertex b) {
        float x = a.x() - b.x(), y = a.y() - b.y(), z = a.z() - b.z();
        return (float)Math.sqrt(x * x + y * y + z * z);
    }

    private static void append(long id, long geometry, Matrix4f m) {
        if (instanceCount >= 32768) throw new IllegalStateException("Zu viele Entity-Modellteile");
        if (instances == null) instances = MemoryUtil.memAlloc(64 * 256);
        if ((instanceCount + 1) * 64 > instances.capacity()) instances = MemoryUtil.memRealloc(instances, instances.capacity() * 2);
        int o = instanceCount++ * 64;
        instances.putLong(o, id);
        instances.putLong(o + 8, geometry);
        // JOML columns -> Pyrit row-major 3x4.
        float[] rows = {m.m00(), m.m10(), m.m20(), m.m30(), m.m01(), m.m11(), m.m21(), m.m31(), m.m02(), m.m12(), m.m22(), m.m32()};
        for (int k = 0; k < 12; k++) instances.putFloat(o + 16 + k * 4, rows[k]);
    }
}
