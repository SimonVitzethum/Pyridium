package simon.pyridium.worldgen.structures;

import java.util.List;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.GpuCompute;

/**
 * Referenz-Anbieter der Umgebungs-Kacheln fuer den Test: fuellt Hoehen und Biome aus der analytischen Umgebung (Modul
 * wg_struct_tilesynth). Zeigt den Vertrag eines Anbieters; die Produktions-Anbieter (aus der Terrain-/LOD-Pipeline)
 * schreiben dasselbe Kachelformat (wg_struct_env_tile.cuh).
 */
public final class SynthEnvProvider implements StructureGpu.EnvProvider, AutoCloseable {
    private final GpuCompute.Kernel fillTiles, fillRings;
    private final CudaCompute gpu;

    public SynthEnvProvider(StructureGpu sg, GpuCompute.BinarySource src, String module) throws Exception {
        this.gpu = sg.gpu();
        GpuCompute.BinarySource mapped = name -> src.load(module + ".ptx");
        fillTiles = gpu.kernel(mapped, "wg_struct_envfill_synth", 13, 256);
        fillRings = gpu.kernel(mapped, "wg_struct_ringfill_synth", 13, 256);
        fillTiles.bind(sg.bindings());
        fillRings.bind(sg.bindings());
    }

    @Override
    public void fillTiles(StructureGpu sg) {
        int[] push = {0, 0, 0, 0, sg.capCand, 0, 0, 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(fillTiles, push, Math.min(sg.capCand, 4096), 1)), List.of());
    }

    @Override
    public void fillRingPlanes(StructureGpu sg) {
        int[] push = {0, 0, 0, 0, 0, 0, 0, 0};
        gpu.runAsync(List.of(new GpuCompute.Dispatch(fillRings, push, 128, 1)), List.of());
    }

    @Override
    public void close() {
        fillTiles.close();
        fillRings.close();
    }
}
