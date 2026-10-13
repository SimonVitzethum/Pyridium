package simon.pyridium.mixin;

import java.util.Set;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.pyridium.worldgen.full.FullChunkService;

/** GPU full chunks already carry all heightmaps (computed on the GPU): skip Vanilla's per-column block scan. */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin {
    @Inject(method = "primeHeightmaps", at = @At("HEAD"), cancellable = true)
    private static void pyridium$skipPriming(ChunkAccess chunk, Set<Heightmap.Types> types, CallbackInfo ci) {
        if (FullChunkService.isGpuChunk(chunk)) {
            for (Heightmap.Types t : types) if (!chunk.hasPrimedHeightmap(t)) return;
            ci.cancel();
        }
    }
}
