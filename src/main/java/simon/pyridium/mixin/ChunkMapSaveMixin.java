package simon.pyridium.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.worldgen.full.GpuGenerated;

/** Unedited GPU chunks are regenerated from the seed on demand: do not serialize them (saves ~30 % of generation CPU time). */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void pyridium$skipUnedited(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (chunk instanceof GpuGenerated g && g.pyridium$skipSave()) {
            chunk.tryMarkSaved();
            cir.setReturnValue(false);
        }
    }
}
