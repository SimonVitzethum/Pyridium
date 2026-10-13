package simon.pyridium.worldgen.full;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import simon.pyridium.worldgen.exact.GpuCompute;

/** Terrain stage of the full-chunk pipeline: exact (density) or fast (heightfield) implementation. */
public interface TerrainSource extends AutoCloseable {
    int minY();

    int height();

    int qh();

    /** Dispatches writing the final block states of n chunks into {@link #stateBuffer()}. */
    List<GpuCompute.Dispatch> prepare(int n, int[] chunkXZ);

    long[] stateBuffer();

    long[] windowBuffer();

    long[] chunkBuffer();

    int biomeIndex(Holder<Biome> b);

    List<String> profileLast();

    @Override
    void close();
}
