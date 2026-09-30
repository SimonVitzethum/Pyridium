package simon.pyridium.client.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.client.world.SectionSync;

/**
 * Jede geaenderte Sektion (Chunk geladen, Block gesetzt) geht an Pyrits
 * Sektionsspeicher. Vanillas Chunk-Mesher bekommt nichts mehr zu tun.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void pyridium$entityVisible(Entity entity, Frustum frustum, double x, double y, double z,
        float partialTicks, long fade, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        // Preserve Vanilla distance/frustum/passenger checks, not its terrain-mesh prerequisite.
        cir.setReturnValue(mc.level != null && (mc.levelRenderer.entityRenderDispatcher().shouldRender(entity, frustum, x, y, z, partialTicks)
            || mc.player != null && entity.hasIndirectPassenger(mc.player)));
    }

    @Inject(method = "extractVisibleBlockEntities", at = @At("HEAD"), cancellable = true)
    private void pyridium$blockEntities(Camera camera, float partialTicks, LevelRenderState output, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { ci.cancel(); return; }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (var chunk : SectionSync.loadedChunks()) for (var block : chunk.getBlockEntities().values()) {
            if (block.isRemoved() || block.getBlockPos().distToCenterSqr(camera.position()) > 256.0 * 256) continue;
            if (!output.cameraRenderState.cullFrustum.isVisible(new AABB(block.getBlockPos()))) continue;
            var state = mc.levelRenderer.blockEntityRenderDispatcher().tryExtractRenderState(block, partialTicks, null, false);
            if (state != null) { output.blockEntityRenderStates.add(state); seen.add(block.getBlockPos().asLong()); }
        }
        for (var block : mc.level.getGloballyRenderedBlockEntities()) {
            if (block.isRemoved() || seen.contains(block.getBlockPos().asLong())) continue;
            var state = mc.levelRenderer.blockEntityRenderDispatcher().tryExtractRenderState(block, partialTicks, null, true);
            if (state != null) output.blockEntityRenderStates.add(state);
        }
        ci.cancel();
    }

	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void pyridium$dirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
		SectionSync.markDirty(x, y, z);
		ci.cancel();
	}
}
