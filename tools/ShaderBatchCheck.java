package simon.pyridium.worldgen.exact;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/** Windowless CUDA regression: mixed point/volume compilation, shared modules,
 * independent bindings, rebinding, and idempotent shared-module destruction. */
public final class ShaderBatchCheck {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("pyridium.cacheDir") == null)
            System.setProperty("pyridium.cacheDir", java.nio.file.Files.createTempDirectory("pyridium-shader-cache.").toString());
        try (CudaCompute gpu = CudaCompute.create()) {
            if (gpu == null) throw new AssertionError(CudaCompute.lastError);
            try (ExactCudaGen gen = ExactCudaGen.create(gpu.computeMajor(), gpu.computeMinor())) {
                if (gen == null) throw new AssertionError("NVRTC unavailable");
                int[] tasks = new int[3 * ExactPlan.TASK_WORDS];
                for (int t = 0; t < 3; t++) {
                    int b = t * ExactPlan.TASK_WORDS;
                    tasks[b + 1] = t * 128;
                    tasks[b + 2] = 128;
                    tasks[b + 3] = 128;
                    tasks[b + 4] = tasks[b + 5] = 1;
                    tasks[b + 12] = ExactPlan.LIVE_OUT;
                    tasks[b + 13] = tasks[b + 14] = tasks[b + 15] = -1;
                    tasks[b + 16] = Float.floatToRawIntBits(2 + t);
                    tasks[b + 20] = -1;
                    tasks[b + 21] = t;
                }
                List<int[]> groups = List.of(new int[]{0, 1}, new int[]{1, 1}, new int[]{2, 1});
                byte[] ptx = gen.compile(ExactCudaGen.batchSource(tasks, groups, List.of(false, true, false)), "batch_check");
                long[] table = gpu.upload(tasks), zero = gpu.deviceBuffer(16);
                long[] a = gpu.deviceBuffer(384 * 4L), b = gpu.deviceBuffer(384 * 4L);
                long[] outA = gpu.readbackBuffer(384 * 4L), outB = gpu.readbackBuffer(384 * 4L);
                GpuCompute.Kernel k0 = gpu.kernel(n -> ptx, "wg_group_0", 11, 128);
                GpuCompute.Kernel k1 = gpu.kernel(n -> ptx, "wg_group_1", 11, 128);
                GpuCompute.Kernel k2 = gpu.kernel(n -> ptx, "wg_group_2", 11, 128);
                if (((CudaCompute.CudaKernel) k0).module != ((CudaCompute.CudaKernel) k1).module)
                    throw new AssertionError("PTX module was not shared");
                k0.bind(table, zero, zero, zero, zero, zero, zero, a, zero, zero, zero);
                k1.bind(table, zero, zero, zero, zero, zero, zero, b, zero, zero, zero);
                k2.bind(table, zero, zero, zero, zero, zero, zero, a, zero, zero, zero);
                int[] push = {0, 1, 384, 0, 0, 0, 1, 0};
                gpu.run(List.of(new GpuCompute.Dispatch(k0, push, 1, 1), new GpuCompute.Dispatch(k1, push, 1, 1),
                        new GpuCompute.Dispatch(k2, push, 1, 1), new GpuCompute.Dispatch(k0, push, 1, 1)),
                        List.of(new long[]{a[0], outA[1], a[2]}, new long[]{b[0], outB[1], b[2]}));
                verify(outA, new float[]{2, 0, 4});
                verify(outB, new float[]{0, 3, 0});
                gpu.runAsync(List.of(new GpuCompute.Dispatch(k0, push, 1, 1)), List.of());
                k0.close();
                k0.close();
                k1.bind(table, zero, zero, zero, zero, zero, zero, a, zero, zero, zero);
                gpu.runAsync(List.of(new GpuCompute.Dispatch(k1, push, 1, 1)), List.of());
                gpu.downloadAsync(outA[1], a[0], a[2]);
                gpu.sync();
                verify(outA, new float[]{2, 3, 4});
                k1.close();
                k2.bind(table, zero, zero, zero, zero, zero, zero, b, zero, zero, zero);
                gpu.runAsync(List.of(new GpuCompute.Dispatch(k2, push, 1, 1)), List.of());
                k2.close();
                gpu.downloadAsync(outB[1], b[0], b[2]);
                gpu.sync();
                verify(outB, new float[]{0, 3, 4});
                System.out.println("PASS: mixed shader batch, one shared module, independent bindings, rebind and repeated close");
            }
        }
    }

    private static void verify(long[] buffer, float[] expected) {
        MemorySegment memory = MemorySegment.ofAddress(buffer[1]).reinterpret(buffer[2]);
        for (int t = 0; t < expected.length; t++) for (int i = 0; i < 128; i++) {
            float actual = memory.getAtIndex(ValueLayout.JAVA_FLOAT, t * 128L + i);
            if (Float.floatToRawIntBits(actual) != Float.floatToRawIntBits(expected[t]))
                throw new AssertionError("task " + t + " pixel " + i + ": " + actual + " != " + expected[t]);
        }
    }
}
