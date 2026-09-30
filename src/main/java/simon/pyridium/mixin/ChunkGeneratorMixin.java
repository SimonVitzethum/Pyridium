package simon.pyridium.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.worldgen.GpuTerrain;

/** BIOMES-Stufe (createBiomes) ueber die exakte GPU-Biomstufe, wenn moeglich; sonst Vanilla. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
    private void pyridium$gpuBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess protoChunk,
                                      CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!((Object) this instanceof NoiseBasedChunkGenerator gen)) return;
        CompletableFuture<ChunkAccess> f = GpuTerrain.tryCreateBiomes(gen, randomState, blender, structureManager, protoChunk);
        if (f != null) cir.setReturnValue(f);
    }
}
