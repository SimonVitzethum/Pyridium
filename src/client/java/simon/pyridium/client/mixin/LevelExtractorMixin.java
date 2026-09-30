package simon.pyridium.client.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.pyridium.client.world.SectionSync;

/**
 * Jede geaenderte Sektion (Chunk geladen, Block gesetzt) geht an Pyrits
 * Sektionsspeicher. Vanillas Chunk-Mesher bekommt nichts mehr zu tun.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void pyridium$dirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
		SectionSync.markDirty(x, y, z);
		ci.cancel();
	}
}
