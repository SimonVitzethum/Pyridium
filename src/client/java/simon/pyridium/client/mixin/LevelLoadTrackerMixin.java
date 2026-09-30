package simon.pyridium.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.client.world.PyritWorld;

/**
 * Vanilla wartet auf die kompilierte Sektion des Spielers (Chunk-Mesher). Den
 * gibt es hier nicht: bereit ist die Welt, sobald Pyrit sie um die Kamera
 * geladen und einmal gezeichnet hat.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public class LevelLoadTrackerMixin {
	@Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
	private void pyridium$ready(CallbackInfoReturnable<Boolean> cir) {
		if (PyritWorld.isReady()) {
			cir.setReturnValue(true);
		}
	}
}
