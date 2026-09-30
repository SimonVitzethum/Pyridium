package simon.pyridium.client.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.client.backend.PydBackend;

/** Einziges Backend: Pyridium. OpenGL und Vulkan werden nie angelegt. */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
	private void pyridium$backends(CallbackInfoReturnable<GpuBackend[]> cir) {
		cir.setReturnValue(new GpuBackend[]{new PydBackend()});
	}
}
