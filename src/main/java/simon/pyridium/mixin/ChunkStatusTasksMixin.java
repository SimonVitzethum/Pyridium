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
    /** Diagnosis of the rare 'Requested chunk unavailable during world generation' (-Dpyridium.fullDebug=true). */
    @Inject(method = "buildTerrain", at = @At("HEAD"))
    private static void pyridium$diagTerrain(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!Boolean.getBoolean("pyridium.fullDebug")) return;
        chunks.forEach(h -> {
            if (h.getChunkIfPresentUnchecked(net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY) == null)
                org.slf4j.LoggerFactory.getLogger("pyridium/full-debug").error("buildTerrain {}: Nachbar {} ohne Chunk (hoechster Status {}, Ticketlevel {}, alle Futures {})",
                        chunk.getPos(), h.getPos(), h.getLatestStatus(), h.getTicketLevel(),
                        h.getAllFutures().stream().map(p -> p.getFirst() + ":" + (p.getSecond().isDone() ? (p.getSecond().isCompletedExceptionally() ? "fail" : "done") : "pending")).toList());
        });
    }

    /**
     * INITIALIZE_LIGHT for GPU chunks: sky-light layers come from the GPU (queued like chunks loaded from disk), the light engine only
     * registers sections; LIGHT then only marks the chunk lit (isLighted). Without GPU light data Vanilla runs unchanged.
     */
    @Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
    private static void pyridium$initLight(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        var sky = simon.pyridium.worldgen.full.FullChunkService.takeSkyLight(chunk);
        if (sky == null) return;
        var engine = context.lightEngine();
        chunk.initializeLightSources();
        ((net.minecraft.world.level.chunk.ProtoChunk) chunk).setLightEngine(engine);
        var pos = chunk.getPos();
        for (int i = 0; i < sky.length; i++)
            if (sky[i] != null)
                engine.queueSectionData(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), sky[i]);
        cir.setReturnValue(engine.initializeLight(chunk, true));
    }

    @Inject(method = "isLighted", at = @At("HEAD"), cancellable = true)
    private static void pyridium$isLighted(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (simon.pyridium.worldgen.full.FullChunkService.isGpuLit(chunk)) cir.setReturnValue(true);
    }

    /** Structure starts: the GPU path has no structure stage yet (hook), so Vanilla's CPU structure generation is skipped there (logged once). */
    @Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
    private static void pyridium$structureStarts(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (simon.pyridium.worldgen.full.FullChunkService.skipStructures(context.generator())) cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private static void pyridium$parallel(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (simon.pyridium.worldgen.full.FullChunkService.isGpuChunk(chunk)) {
            // GPU full chunk: features (ores, vegetation, caves) came from the GPU stages; structure hook: docs/FULL_CHUNK_GEN.md
            cir.setReturnValue(java.util.concurrent.CompletableFuture.completedFuture(chunk));
            return;
        }
        if (FeatureScheduler.bypassed()) return;
        cir.setReturnValue(FeatureScheduler.submit(chunk, () -> ChunkStatusTasks.generateFeatures(context, step, chunks, chunk).join()));
    }
}
