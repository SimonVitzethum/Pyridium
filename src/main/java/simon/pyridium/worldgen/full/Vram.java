package simon.pyridium.worldgen.full;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** cuMemGetInfo of the current CUDA context (call on a thread that already created a CudaCompute). */
public final class Vram {
    private static MethodHandle handle;

    private Vram() {
    }

    /** {free, total} in bytes, or null when unavailable. */
    public static synchronized long[] info() {
        try {
            if (handle == null) {
                SymbolLookup lib = SymbolLookup.libraryLookup("libcuda.so.1", Arena.global());
                MemorySegment sym = lib.find("cuMemGetInfo_v2").orElseThrow();
                handle = Linker.nativeLinker().downcallHandle(sym, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment f = a.allocate(ValueLayout.JAVA_LONG), t = a.allocate(ValueLayout.JAVA_LONG);
                int r = (int) handle.invoke(f, t);
                if (r != 0) return null;
                return new long[]{f.get(ValueLayout.JAVA_LONG, 0), t.get(ValueLayout.JAVA_LONG, 0)};
            }
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean isOom(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("OUT_OF_MEMORY") || m.contains("cuMemAlloc") || m.contains("cuMemAllocHost")) return true;
        }
        return false;
    }
}
