package simon.pyridium.worldgen.exact;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * CUDA-Backend der exakten Worldgen ueber die Driver API (libcuda / nvcuda.dll) per Java FFM, ohne
 * weitere Abhaengigkeiten. Kernels sind PTX (build.gradle compileCuda, im Jar unter shaders/ptx),
 * Codegen-Gruppen werden mit NVRTC uebersetzt, falls vorhanden (ExactCudaGen).
 *
 * <p>Puffer: host-beschreibbare Puffer sind gepinnter Host-Speicher plus Geraetekopie; vor jedem
 * Lauf wird der Host-Stand hochgeladen ({@link #upload} einmalig). Readback-Puffer sind gepinnter
 * Host-Speicher, Kopien landen per cuMemcpyDtoHAsync darin. Bindungen setzt {@link Kernel#bind}
 * in das Modul-Global vf_b (wg_prelude.cuh). Ein Primary Context, ein Stream; nicht threadsicher.
 */
public final class CudaCompute implements GpuCompute {
    /** Grund, warum {@link #create()} zuletzt null lieferte. */
    public static volatile String lastError = "";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final MemoryLayout PTR = ValueLayout.ADDRESS;

    private final Api cu;
    private final int device;
    private final MemorySegment ctx, stream;
    private final String name;
    private final int ccMajor, ccMinor;
    private final List<long[]> hostVisible = new ArrayList<>();   // {dptr, host, size}, dynamisch
    private final List<Long> deviceAllocs = new ArrayList<>(), hostAllocs = new ArrayList<>();
    private final Set<Long> readback = new HashSet<>();
    private final List<CudaKernel> kernels = new ArrayList<>();
    private @Nullable ExactCudaGen gen;
    private boolean genTried;

    /** Driver-API-Funktionen (Symbole mit _v2 wie in cuda.h von CUDA 12/13). */
    private static final class Api {
        final MethodHandle init, deviceGetCount, deviceGet, deviceGetName, deviceGetAttribute, primaryCtxRetain, primaryCtxRelease,
                ctxSetCurrent, streamCreate, streamSynchronize, streamDestroy, moduleLoadData, moduleGetFunction, moduleGetGlobal,
                moduleUnload, memAlloc, memFree, memsetD8, memAllocHost, memFreeHost, memcpyHtoD, memcpyHtoDAsync, memcpyDtoHAsync,
                memcpyDtoDAsync, memcpy2DAsync, memsetD8Async, launchKernel, eventCreate, eventRecord, eventSynchronize, eventElapsedTime, eventDestroy, getErrorName;

        Api(SymbolLookup lib) {
            init = fn(lib, "cuInit", INT);
            deviceGetCount = fn(lib, "cuDeviceGetCount", PTR);
            deviceGet = fn(lib, "cuDeviceGet", PTR, INT);
            deviceGetName = fn(lib, "cuDeviceGetName", PTR, INT, INT);
            deviceGetAttribute = fn(lib, "cuDeviceGetAttribute", PTR, INT, INT);
            primaryCtxRetain = fn(lib, "cuDevicePrimaryCtxRetain", PTR, INT);
            primaryCtxRelease = fn(lib, "cuDevicePrimaryCtxRelease_v2", INT);
            ctxSetCurrent = fn(lib, "cuCtxSetCurrent", PTR);
            streamCreate = fn(lib, "cuStreamCreateWithPriority", PTR, INT, INT);
            streamSynchronize = fn(lib, "cuStreamSynchronize", PTR);
            streamDestroy = fn(lib, "cuStreamDestroy_v2", PTR);
            moduleLoadData = fn(lib, "cuModuleLoadData", PTR, PTR);
            moduleGetFunction = fn(lib, "cuModuleGetFunction", PTR, PTR, PTR);
            moduleGetGlobal = fn(lib, "cuModuleGetGlobal_v2", PTR, PTR, PTR, PTR);
            moduleUnload = fn(lib, "cuModuleUnload", PTR);
            memAlloc = fn(lib, "cuMemAlloc_v2", PTR, LONG);
            memFree = fn(lib, "cuMemFree_v2", LONG);
            memsetD8 = fn(lib, "cuMemsetD8_v2", LONG, ValueLayout.JAVA_BYTE, LONG);
            memAllocHost = fn(lib, "cuMemAllocHost_v2", PTR, LONG);
            memFreeHost = fn(lib, "cuMemFreeHost", PTR);
            memcpyHtoD = fn(lib, "cuMemcpyHtoD_v2", LONG, PTR, LONG);
            memcpyHtoDAsync = fn(lib, "cuMemcpyHtoDAsync_v2", LONG, PTR, LONG, PTR);
            memcpyDtoHAsync = fn(lib, "cuMemcpyDtoHAsync_v2", PTR, LONG, LONG, PTR);
            memcpyDtoDAsync = fn(lib, "cuMemcpyDtoDAsync_v2", LONG, LONG, LONG, PTR);
            memcpy2DAsync = fn(lib, "cuMemcpy2DAsync_v2", PTR, PTR);
            memsetD8Async = fn(lib, "cuMemsetD8Async", LONG, ValueLayout.JAVA_BYTE, LONG, PTR);
            launchKernel = fn(lib, "cuLaunchKernel", PTR, INT, INT, INT, INT, INT, INT, INT, PTR, PTR, PTR);
            eventCreate = fn(lib, "cuEventCreate", PTR, INT);
            eventRecord = fn(lib, "cuEventRecord", PTR, PTR);
            eventSynchronize = fn(lib, "cuEventSynchronize", PTR);
            eventElapsedTime = lib.find("cuEventElapsedTime_v2").isPresent()
                    ? fn(lib, "cuEventElapsedTime_v2", PTR, PTR, PTR) : fn(lib, "cuEventElapsedTime", PTR, PTR, PTR);
            eventDestroy = fn(lib, "cuEventDestroy_v2", PTR);
            getErrorName = fn(lib, "cuGetErrorName", INT, PTR);
        }

        private static MethodHandle fn(SymbolLookup lib, String name, MemoryLayout... args) {
            MemorySegment sym = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("CUDA-Funktion fehlt: " + name));
            return LINKER.downcallHandle(sym, FunctionDescriptor.of(INT, args));
        }
    }

    private CudaCompute(Api cu, int device, MemorySegment ctx, MemorySegment stream, String name, int ccMajor, int ccMinor) {
        this.cu = cu;
        this.device = device;
        this.ctx = ctx;
        this.stream = stream;
        this.name = name;
        this.ccMajor = ccMajor;
        this.ccMinor = ccMinor;
    }

    /** CUDA-Treiberbibliothek dieses Systems (null: nicht vorhanden). */
    static @Nullable SymbolLookup driverLibrary() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String n : windows ? new String[]{"nvcuda.dll"} : new String[]{"libcuda.so.1", "libcuda.so"}) {
            try {
                return SymbolLookup.libraryLookup(n, Arena.global());
            } catch (IllegalArgumentException e) {
                // naechster Name
            }
        }
        return null;
    }

    /** Erstes CUDA-Geraet (oder -Dpyridium.worldgenGpu=&lt;Namensteil&gt;); null ohne CUDA. */
    /** Pyridium: Codegen mit schneller Mathematik (nur fuer den LOD-Fernbereich) */
    public boolean fastMath;

    public static @Nullable CudaCompute create() {
        SymbolLookup lib = driverLibrary();
        if (lib == null) {
            lastError = "keine CUDA-Treiberbibliothek (libcuda/nvcuda)";
            return null;
        }
        try (Arena a = Arena.ofConfined()) {
            Api cu = new Api(lib);
            int r = (int) cu.init.invoke(0);
            if (r != 0) {
                lastError = "cuInit: " + errorName(cu, r);
                return null;
            }
            MemorySegment n = a.allocate(INT);
            check(cu, (int) cu.deviceGetCount.invoke(n), "cuDeviceGetCount");
            String want = System.getProperty("pyridium.worldgenGpu", "");
            for (int i = 0; i < n.get(INT, 0); i++) {
                MemorySegment dev = a.allocate(INT);
                check(cu, (int) cu.deviceGet.invoke(dev, i), "cuDeviceGet");
                int d = dev.get(INT, 0);
                MemorySegment nameBuf = a.allocate(256);
                check(cu, (int) cu.deviceGetName.invoke(nameBuf, 256, d), "cuDeviceGetName");
                String devName = nameBuf.getString(0);
                if (!want.isEmpty() && !devName.contains(want)) continue;
                MemorySegment v = a.allocate(INT);
                check(cu, (int) cu.deviceGetAttribute.invoke(v, 75, d), "cc major");   // CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR
                int major = v.get(INT, 0);
                check(cu, (int) cu.deviceGetAttribute.invoke(v, 76, d), "cc minor");
                int minor = v.get(INT, 0);
                if (major < 7 || (major == 7 && minor < 5)) continue;          // PTX compute_75
                MemorySegment c = a.allocate(PTR);
                check(cu, (int) cu.primaryCtxRetain.invoke(c, d), "cuDevicePrimaryCtxRetain");
                MemorySegment ctx = c.get(ValueLayout.ADDRESS, 0);
                check(cu, (int) cu.ctxSetCurrent.invoke(ctx), "cuCtxSetCurrent");
                MemorySegment s = a.allocate(PTR);
                // Pyridium: niedrigste Prioritaet (Zahl 0), das Rendern verdraengt die Generierung
                check(cu, (int) cu.streamCreate.invoke(s, 1, 0), "cuStreamCreate"); // CU_STREAM_NON_BLOCKING
                return new CudaCompute(cu, d, ctx, s.get(ValueLayout.ADDRESS, 0), devName, major, minor);
            }
            lastError = "kein CUDA-Geraet ab Compute Capability 7.5" + (want.isEmpty() ? "" : " mit '" + want + "'");
            return null;
        } catch (Throwable e) {
            lastError = "CUDA nicht nutzbar: " + e;
            return null;
        }
    }

    private static String errorName(Api cu, int r) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(PTR);
            if ((int) cu.getErrorName.invoke(r, p) == 0) return p.get(ValueLayout.ADDRESS, 0).reinterpret(128).getString(0);
        } catch (Throwable ignored) {
        }
        return "CUresult " + r;
    }

    private static void check(Api cu, int r, String what) {
        if (r != 0) throw new IllegalStateException(what + ": " + errorName(cu, r));
    }

    private void check(int r, String what) {
        check(cu, r, what);
    }

    private void current() {
        try {
            check((int) cu.ctxSetCurrent.invoke(ctx), "cuCtxSetCurrent");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Aufruf mit Pruefung (MethodHandle.invoke wirft Throwable). */
    private void call(MethodHandle h, String what, Object... args) {
        try {
            check((int) h.invokeWithArguments(args), what);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(what, e);
        }
    }

    public int computeMajor() {
        return ccMajor;
    }

    public int computeMinor() {
        return ccMinor;
    }

    @Override
    public String deviceName() {
        return name;
    }

    // ---------------------------------------------------------------------------------------
    // Puffer
    // ---------------------------------------------------------------------------------------

    private long allocDevice(long size) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(LONG);
            call(cu.memAlloc, "cuMemAlloc " + size, p, size);
            long d = p.get(LONG, 0);
            call(cu.memsetD8, "cuMemsetD8", d, (byte) 0, size);
            deviceAllocs.add(d);
            return d;
        }
    }

    private long allocHost(long size) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(PTR);
            call(cu.memAllocHost, "cuMemAllocHost " + size, p, size);
            long h = p.get(ValueLayout.ADDRESS, 0).address();
            MemorySegment.ofAddress(h).reinterpret(size).fill((byte) 0);
            hostAllocs.add(h);
            return h;
        }
    }

    @Override
    public long[] buffer(long size) {
        current();
        size = Math.max(16, size);
        long[] b = {allocDevice(size), allocHost(size), size};
        hostVisible.add(b);
        return b;
    }

    @Override
    public long[] deviceBuffer(long size) {
        current();
        size = Math.max(16, size);
        return new long[]{allocDevice(size), 0, size};
    }

    @Override
    public long[] readbackBuffer(long size) {
        current();
        size = Math.max(16, size);
        long h = allocHost(size);
        readback.add(h);
        return new long[]{h, h, size};
    }

    @Override
    public long[] upload(int[] data) {
        current();
        long size = Math.max(16, data.length * 4L);
        long d = allocDevice(size);
        long h = allocHost(size);
        MemorySegment.ofAddress(h).reinterpret(size).copyFrom(MemorySegment.ofArray(data));
        call(cu.memcpyHtoD, "cuMemcpyHtoD", d, MemorySegment.ofAddress(h), size);
        return new long[]{d, h, size};
    }

    // ---------------------------------------------------------------------------------------
    // Kernel
    // ---------------------------------------------------------------------------------------

    /** Ein geladenes PTX-Modul mit Einstiegsfunktion und vf_b-Global. */
    final class CudaKernel implements GpuCompute.Kernel {
        final MemorySegment module, function;
        final long vfb;
        final int block, bindings;

        CudaKernel(byte[] ptx, String entry, int bindings, int block) {
            this.block = block;
            this.bindings = bindings;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment image = a.allocate(ptx.length + 1L);
                image.copyFrom(MemorySegment.ofArray(ptx));
                image.set(ValueLayout.JAVA_BYTE, ptx.length, (byte) 0);
                MemorySegment m = a.allocate(PTR);
                call(cu.moduleLoadData, "cuModuleLoadData " + entry, m, image);
                module = m.get(ValueLayout.ADDRESS, 0);
                MemorySegment f = a.allocate(PTR);
                call(cu.moduleGetFunction, "cuModuleGetFunction " + entry, f, module, a.allocateFrom(entry));
                function = f.get(ValueLayout.ADDRESS, 0);
                MemorySegment g = a.allocate(LONG), gs = a.allocate(LONG);
                call(cu.moduleGetGlobal, "vf_b in " + entry, g, gs, module, a.allocateFrom("vf_b"));
                vfb = g.get(LONG, 0);
                if (gs.get(LONG, 0) < 8L * bindings) throw new IllegalStateException("vf_b zu klein fuer " + bindings + " Bindungen");
            }
        }

        @Override
        public void bind(long[]... bufs) {
            current();
            if (bufs.length > bindings) throw new IllegalArgumentException(bufs.length + " Bindungen > " + bindings);
            long[] ptrs = new long[bufs.length];
            for (int i = 0; i < bufs.length; i++) ptrs[i] = bufs[i][0];
            try (Arena a = Arena.ofConfined()) {
                MemorySegment src = a.allocate(8L * ptrs.length);
                src.copyFrom(MemorySegment.ofArray(ptrs));
                call(cu.memcpyHtoD, "vf_b setzen", vfb, src, 8L * ptrs.length);
            }
        }

        @Override
        public void close() {
            try {
                cu.moduleUnload.invoke(module);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public GpuCompute.Kernel kernel(BinarySource src, String name, int bindings, int block) throws Exception {
        current();
        CudaKernel k = new CudaKernel(src.load(name + ".ptx"), name, bindings, block);
        kernels.add(k);
        return k;
    }

    @Override
    public GpuCompute.@Nullable Kernel groupKernel(int[] tasks, int[] group, boolean point, int bindings) throws Exception {
        if (!genTried) {
            genTried = true;
            gen = ExactCudaGen.create(ccMajor, ccMinor);
            if (gen != null) gen.fast = fastMath;
        }
        if (gen == null) return null;
        current();
        byte[] ptx = gen.compile(ExactCudaGen.groupSource(tasks, group, point), "grp" + group[0]);
        CudaKernel k = new CudaKernel(ptx, point ? "wg_exact_task_pt" : "wg_exact_task", bindings, 128);
        kernels.add(k);
        return k;
    }

    // ---------------------------------------------------------------------------------------
    // Ausfuehrung
    // ---------------------------------------------------------------------------------------

    private void record(Arena a, List<Dispatch> list, List<MemorySegment> events) {
        for (long[] b : hostVisible) call(cu.memcpyHtoDAsync, "Upload", b[0], MemorySegment.ofAddress(b[1]), b[2], stream);
        if (events != null) events.add(event(a));
        for (Dispatch d : list) {
            CudaKernel k = (CudaKernel) d.kernel();
            if (d.gx() > 0 && d.gy() > 0) {
                MemorySegment push = a.allocate(32);
                push.copyFrom(MemorySegment.ofArray(d.push()));
                MemorySegment params = a.allocate(PTR);
                params.set(ValueLayout.ADDRESS, 0, push);
                call(cu.launchKernel, "cuLaunchKernel", k.function, d.gx(), d.gy(), 1, k.block, 1, 1, 0, stream, params, MemorySegment.NULL);
            }
            if (events != null) events.add(event(a));
        }
    }

    private MemorySegment event(Arena a) {
        MemorySegment e = a.allocate(PTR);
        call(cu.eventCreate, "cuEventCreate", e, 0);
        MemorySegment ev = e.get(ValueLayout.ADDRESS, 0);
        call(cu.eventRecord, "cuEventRecord", ev, stream);
        return ev;
    }

    /**
     * Pyridium: Dispatches einreihen ohne zu warten; hochgeladen werden nur die angegebenen
     * Host-Puffer (statt aller). Mit {@link #sync} abschliessen.
     */
    public void runAsync(List<Dispatch> list, List<long[]> uploads) {
        current();
        try (Arena a = Arena.ofConfined()) {
            // je Eintrag {Geraet, Host-Quelle, Bytes}
            for (long[] b : uploads) call(cu.memcpyHtoDAsync, "Upload", b[0], MemorySegment.ofAddress(b[1]), b[2], stream);
            for (Dispatch d : list) {
                CudaKernel k = (CudaKernel) d.kernel();
                if (d.gx() > 0 && d.gy() > 0) {
                    MemorySegment push = a.allocate(32);
                    push.copyFrom(MemorySegment.ofArray(d.push()));
                    MemorySegment params = a.allocate(PTR);
                    params.set(ValueLayout.ADDRESS, 0, push);
                    call(cu.launchKernel, "cuLaunchKernel", k.function, d.gx(), d.gy(), 1, k.block, 1, 1, 0, stream, params, MemorySegment.NULL);
                }
            }
        }
    }

    /**
     * Pyridium: n Abschnitte (je len Bytes ab src + i * stride + off) nach dst (dicht) lesen,
     * stream-geordnet; wartet.
     */
    public void readStrided(long dst, long src, long stride, long off, long len, int n) {
        readStridedAsync(dst, src, stride, off, len, n);
        call(cu.streamSynchronize, "cuStreamSynchronize", stream);
    }

    /** wie {@link #readStrided}, ohne zu warten (Zielspeicher gepinnt) */
    public void readStridedAsync(long dst, long src, long stride, long off, long len, int n) {
        current();
        // ein 2D-Kopierauftrag statt n einzelner (CUDA_MEMCPY2D, 128 Bytes)
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(128, 8);
            m.set(ValueLayout.JAVA_LONG, 16, 2L);           // srcMemoryType = DEVICE
            m.set(ValueLayout.JAVA_LONG, 32, src + off);     // srcDevice
            m.set(ValueLayout.JAVA_LONG, 48, stride);        // srcPitch
            m.set(ValueLayout.JAVA_LONG, 72, 1L);            // dstMemoryType = HOST
            m.set(ValueLayout.JAVA_LONG, 80, dst);           // dstHost
            m.set(ValueLayout.JAVA_LONG, 104, len);          // dstPitch
            m.set(ValueLayout.JAVA_LONG, 112, len);          // WidthInBytes
            m.set(ValueLayout.JAVA_LONG, 120, (long) n);     // Height
            call(cu.memcpy2DAsync, "Readback 2D", m, stream);
        }
    }

    /** Pyridium: Geraetespeicher nullen, stream-geordnet */
    public void zeroAsync(long dev, long bytes) {
        current();
        if (bytes > 0) call(cu.memsetD8Async, "cuMemsetD8Async", dev, (byte) 0, bytes, stream);
    }

    /** Pyridium: Geraet -> Geraet, stream-geordnet */
    public void copyAsync(long dst, long src, long bytes) {
        current();
        if (bytes > 0) call(cu.memcpyDtoDAsync, "Kopie", dst, src, bytes, stream);
    }

    /** Pyridium: gepinnter Host -> Geraet, stream-geordnet */
    public void uploadAsync(long dev, long host, long bytes) {
        current();
        if (bytes > 0) call(cu.memcpyHtoDAsync, "Upload", dev, MemorySegment.ofAddress(host), bytes, stream);
    }

    /** Pyridium: Geraet -> gepinnter Host, stream-geordnet */
    public void downloadAsync(long host, long dev, long bytes) {
        current();
        if (bytes > 0) call(cu.memcpyDtoHAsync, "Download", MemorySegment.ofAddress(host), dev, bytes, stream);
    }

    public void sync() {
        current();
        call(cu.streamSynchronize, "cuStreamSynchronize", stream);
    }

    @Override
    public double run(List<Dispatch> list, List<long[]> copies) {
        current();
        try (Arena a = Arena.ofConfined()) {
            long t0 = System.nanoTime();
            record(a, list, null);
            for (long[] c : copies) {
                if (readback.contains(c[1])) call(cu.memcpyDtoHAsync, "Readback", MemorySegment.ofAddress(c[1]), c[0], c[2], stream);
                else call(cu.memcpyDtoDAsync, "Kopie", c[1], c[0], c[2], stream);
            }
            call(cu.streamSynchronize, "cuStreamSynchronize", stream);
            return (System.nanoTime() - t0) / 1e6;
        }
    }

    @Override
    public double[] runTimed(List<Dispatch> list) {
        current();
        try (Arena a = Arena.ofConfined()) {
            List<MemorySegment> events = new ArrayList<>();
            record(a, list, events);
            call(cu.streamSynchronize, "cuStreamSynchronize", stream);
            double[] out = new double[list.size()];
            MemorySegment ms = a.allocate(ValueLayout.JAVA_FLOAT);
            for (int i = 0; i < out.length; i++) {
                call(cu.eventElapsedTime, "cuEventElapsedTime", ms, events.get(i), events.get(i + 1));
                out[i] = ms.get(ValueLayout.JAVA_FLOAT, 0);
            }
            for (MemorySegment e : events) call(cu.eventDestroy, "cuEventDestroy", e);
            return out;
        }
    }

    @Override
    public void close() {
        try {
            current();
            cu.streamSynchronize.invoke(stream);
            for (CudaKernel k : kernels) k.close();
            kernels.clear();
            for (long d : deviceAllocs) cu.memFree.invoke(d);
            for (long h : hostAllocs) cu.memFreeHost.invoke(MemorySegment.ofAddress(h));
            deviceAllocs.clear();
            hostAllocs.clear();
            cu.streamDestroy.invoke(stream);
            if (gen != null) gen.close();
            cu.primaryCtxRelease.invoke(device);
        } catch (Throwable ignored) {
        }
    }

    /** Diagnose: Name und Faehigkeit. */
    @Override
    public String toString() {
        return name + " (sm_" + ccMajor + ccMinor + ")";
    }
}
