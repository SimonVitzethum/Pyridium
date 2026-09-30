package simon.pyridium.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
	/** Die Ausgabe geht direkt an die wl_surface: SDL muss Wayland benutzen. */
	@Inject(method = "initBackendSystem", at = @At("HEAD"))
	private static void pyridium$wayland(CallbackInfoReturnable<?> cir) {
		if (Boolean.getBoolean("pyridium.headless")) {
			// Tests ohne sichtbares Fenster: SDL ohne Bildschirm, Bilder nur als Aufnahmen
			SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "offscreen");
		} else if (System.getenv("WAYLAND_DISPLAY") != null) {
			SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "wayland");
		}
	}
}
