package simon.pyridium.client.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.pyridium.client.world.PyritWorld;

/** Die Welt zeichnet Pyrit; Minecrafts Terrain-, Himmel- und Entity-Pfad entfällt. */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void pyridium$render(GraphicsResourceAllocator alloc, boolean renderOutline, CameraRenderState camera, GpuBufferSlice terrainFog,
		Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		PyritWorld.render(camera);
		ci.cancel();
	}
}
