package simon.pyridium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.worldgen.full.FullChunkService;
import simon.pyridium.worldgen.full.GpuGenerated;

/**
 * Marks LevelChunks that came from a GPU full chunk and tracks whether blocks were edited afterwards. Unedited GPU chunks are pure
 * functions of the seed, so ChunkMap does not write them to disk (see ChunkMapMixin).
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkGpuMixin implements GpuGenerated {
    @Unique
    private boolean pyridium$gpu;
    @Unique
    private boolean pyridium$edited;

    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void pyridium$fromProto(ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor post, CallbackInfo ci) {
        pyridium$gpu = FullChunkService.isGpuChunk(proto);
    }

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void pyridium$edit(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        pyridium$edited = true;
    }

    @Override
    public boolean pyridium$skipSave() {
        return pyridium$gpu && !pyridium$edited && !Boolean.getBoolean("pyridium.fullSaveAll");
    }
}
