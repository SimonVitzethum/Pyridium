package simon.pyridium.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.util.StaticCache2D;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.worldgen.FeatureScheduler;

/** FEATURES-Stufe ueber den parallelen FeatureScheduler statt synchron auf dem Dispatcher. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private static void pyridium$parallel(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (FeatureScheduler.bypassed()) return;
        cir.setReturnValue(FeatureScheduler.submit(chunk, () -> ChunkStatusTasks.generateFeatures(context, step, chunks, chunk).join()));
    }
}
