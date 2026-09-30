package simon.pyridium.mixin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.worldgen.GpuTerrain;

/**
 * buildTerrain (Noise, Oberflaeche, Carver) ueber die exakte GPU-Stufe, wenn der Chunk dafuer
 * geeignet ist (keine Struktur-Anpassung, kein Blending, unterstuetzter Generator); sonst Vanilla.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "buildTerrain", at = @At("HEAD"), cancellable = true)
    private void pyridium$gpuTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
                                       BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes,
                                       CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> f = GpuTerrain.tryBuildTerrain((NoiseBasedChunkGenerator) (Object) this, chunk, blender, randomState,
                structureManager, biomeManager, carverBiomeRegion);
        if (f != null) cir.setReturnValue(f);
    }
}
