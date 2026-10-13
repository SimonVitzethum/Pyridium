package simon.pyridium.worldgen.full;

/** Implemented (by mixin) by LevelChunk: true while an unedited GPU-generated chunk can be dropped instead of saved. */
public interface GpuGenerated {
    boolean pyridium$skipSave();
}
