package simon.pyridium.worldgen.exact;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Laufzeit-Codegen des CUDA-Pfads: je fusionierter Gruppe ein Kernel aus wg_exact_task(_pt).cu
 * mit WG_CODEGEN, CG_START und CG_BODY ({@link #groupBody}: gleicher Quelltext wie der
 * Interpreter, daher bitgleich), uebersetzt mit NVRTC zu PTX. NVRTC gehoert
 * zum CUDA-Toolkit und fehlt auf reinen Treiber-Installationen: dann null, der Interpreter-Kernel
 * rechnet (gleiches Ergebnis, langsamer).
 */
public final class ExactCudaGen implements AutoCloseable {
    private static final String BASE = "/assets/pyridium/cuda/";
    private static final String[] HEADERS = {"wg_prelude.cuh", "wg_exact_common.cuh", "wg_exact_point.cuh", "wg_exact_random.cuh",
            "wg_exact_task_body.cuh"};
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout PTR = ValueLayout.ADDRESS;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;

    private final MethodHandle create, compile, ptxSize, getPtx, logSize, getLog, destroy;
    private final String arch;
    private final String[] headerSources = new String[HEADERS.length];

    private ExactCudaGen(SymbolLookup lib, int major, int minor) throws IOException {
        create = fn(lib, "nvrtcCreateProgram", PTR, PTR, PTR, INT, PTR, PTR);
        compile = fn(lib, "nvrtcCompileProgram", PTR, INT, PTR);
        ptxSize = fn(lib, "nvrtcGetPTXSize", PTR, PTR);
        getPtx = fn(lib, "nvrtcGetPTX", PTR, PTR);
        logSize = fn(lib, "nvrtcGetProgramLogSize", PTR, PTR);
        getLog = fn(lib, "nvrtcGetProgramLog", PTR, PTR);
        destroy = fn(lib, "nvrtcDestroyProgram", PTR);
        arch = "compute_" + major + minor;
        for (int i = 0; i < HEADERS.length; i++) headerSources[i] = resource(HEADERS[i]);
    }

    private static MethodHandle fn(SymbolLookup lib, String name, MemoryLayout... args) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), FunctionDescriptor.of(INT, args));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ExactCudaGen.class.getResourceAsStream(BASE + name)) {
            if (in == null) throw new IOException("CUDA-Quelle fehlt: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** NVRTC suchen (System, CUDA_PATH, /opt/cuda, /usr/local/cuda); null ohne NVRTC. */
    /** Pyridium: schnelle Mathematik (LOD) statt JVM-genauer Rundung */
    public boolean fast;

    static @Nullable ExactCudaGen create(int major, int minor) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> names = new ArrayList<>();
        if (windows) {
            for (String v : new String[]{"130_0", "120_0"}) names.add("nvrtc64_" + v + ".dll");
        } else {
            for (String n : new String[]{"libnvrtc.so", "libnvrtc.so.13", "libnvrtc.so.12"}) names.add(n);
            for (String dir : new String[]{System.getenv("CUDA_PATH"), "/opt/cuda", "/usr/local/cuda"}) {
                if (dir == null) continue;
                Path p = Path.of(dir, "lib64", "libnvrtc.so");
                if (Files.exists(p)) names.add(p.toString());
            }
        }
        for (String n : names) {
            try {
                SymbolLookup lib = SymbolLookup.libraryLookup(n, Arena.global());
                return new ExactCudaGen(lib, major, minor);
            } catch (Throwable e) {
                // naechster Kandidat
            }
        }
        return null;
    }

    /**
     * Lohnt Codegen? Nur Gruppen aus leichten Ops: Rauschen, Splines, Intervalle und Punktsuche
     * blaehen den Kernel auf (grosse Funktionen je Aufgabe inline) und liegen auf kleinen Volumen.
     */
    /** Pyridium (LOD): alle Gruppen uebersetzen, auch Rauschen/Splines (Punktsuche bleibt Interpreter) */
    public static boolean worthCodegenAll(ExactPlan plan, int[] g) {
        for (int k = 0; k < g[1]; k++) {
            if (plan.tasks.get(g[0] + k).op == ExactPlan.OP_FINDTOP) return false;
        }
        return true;
    }

    public static boolean worthCodegen(ExactPlan plan, int[] g) {
        for (int k = 0; k < g[1]; k++) {
            int op = plan.tasks.get(g[0] + k).op;
            if (op == ExactPlan.OP_NOISE || op == ExactPlan.OP_SHIFTED || op == ExactPlan.OP_SPLINE
                    || op == ExactPlan.OP_INTERVAL || op == ExactPlan.OP_FINDTOP) return false;
        }
        return true;
    }

    /**
     * Anweisungen einer Gruppe: je Aufgabe ein Aufruf der Opcode-Funktion mit literal aufgeloesten
     * Eingaengen (lokaler Slot oder Scratch-Offset), Slot- und Scratch-Schreiben. zero = Literal fuer
     * nicht belegte Eingaenge ("0.0f" in CUDA). Aus dem frueheren GLSL-Codegen von Vulkanfish.
     */
    public static String groupBody(int[] tasks, int[] g, String zero) {
        final int W = ExactPlan.TASK_WORDS;
        StringBuilder body = new StringBuilder();
        for (int k = 0; k < g[1]; k++) {
            int t = g[0] + k, b = t * W, op = tasks[b];
            boolean pos = op == ExactPlan.OP_INTERP || op == ExactPlan.OP_GATHER || op == ExactPlan.OP_BROADCAST;
            String[] in = new String[3];
            for (int i = 0; i < 3; i++) {
                int src = tasks[b + 13 + i];
                if (src < 0 || pos) in[i] = zero;
                else if (tasks[src * W + 21] == g[0] && tasks[src * W + 20] >= 0) in[i] = "gLocal[" + tasks[src * W + 20] + "]";
                else in[i] = "gScratch[chunkBase + " + tasks[src * W + 1] + " + idx]";
            }
            body.append("    { float r = ").append(op == ExactPlan.OP_ROUND ? "evalTask(" + t + ", " + op + ", " : "op" + op + "(" + t + ", ")
                    .append(in[0]).append(", ").append(in[1]).append(", ").append(in[2])
                    .append(", chunk, chunkBase, idx, ix, iy, iz);");
            int slot = tasks[b + 20];
            if (slot >= 0) body.append(" gLocal[").append(slot).append("] = r;");
            if ((tasks[b + 12] & ExactPlan.LIVE_OUT) != 0) body.append(" gScratch[chunkBase + ").append(tasks[b + 1]).append(" + idx] = r;");
            body.append(" }\n");
        }
        return body.toString();
    }

    /** CUDA-Quelle einer Gruppe; tasks = {@link ExactPlan#encodeTasks()}. */
    public static String groupSource(int[] tasks, int[] g, boolean point) throws IOException {
        String body = groupBody(tasks, g, "0.0f").replace("\n", " ");
        StringBuilder sb = new StringBuilder();
        sb.append("#define WG_CODEGEN 1\n");
        sb.append("#define CG_START ").append(g[0]).append('\n');
        sb.append("#define CG_BODY ").append(body).append('\n');
        sb.append(resource(point ? "wg_exact_task_pt.cu" : "wg_exact_task.cu"));
        return sb.toString();
    }

    /** CUDA-Quelle -> PTX mit den Exaktheits-Optionen (wie compileCuda im Build). */
    public byte[] compile(String source, String name) {
        // Pyridium: PTX-Cache auf Platte (Quelle + Architektur als Schluessel): spaetere Starts laden sofort
        java.nio.file.Path cached = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(arch.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) (fast ? 1 : 0));
            md.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String key = java.util.HexFormat.of().formatHex(md.digest());
            cached = java.nio.file.Path.of(System.getProperty("pyridium.cacheDir", "pyridium-cache"), "ptx", key + ".ptx");
            if (java.nio.file.Files.isRegularFile(cached)) return java.nio.file.Files.readAllBytes(cached);
        } catch (Exception ignored) {
            cached = null;
        }
        byte[] ptx = compileUncached(source, name);
        if (cached != null) {
            try {
                java.nio.file.Files.createDirectories(cached.getParent());
                java.nio.file.Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp" + Thread.currentThread().threadId());
                java.nio.file.Files.write(tmp, ptx);
                java.nio.file.Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
            }
        }
        return ptx;
    }

    private byte[] compileUncached(String source, String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment prog = a.allocate(PTR);
            MemorySegment hdrSrc = a.allocate(PTR, HEADERS.length), hdrNames = a.allocate(PTR, HEADERS.length);
            for (int i = 0; i < HEADERS.length; i++) {
                hdrSrc.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(headerSources[i]));
                hdrNames.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(HEADERS[i]));
            }
            check((int) create.invoke(prog, a.allocateFrom(source), a.allocateFrom(name + ".cu"), HEADERS.length, hdrSrc, hdrNames),
                    "nvrtcCreateProgram");
            MemorySegment p = prog.get(ValueLayout.ADDRESS, 0);
            // Pyridium: fast = LOD-Fernbereich (nicht bitgenau noetig, schneller); sonst exakt wie die JVM
            String[] opts = fast
                    ? new String[]{"--gpu-architecture=" + arch, "-std=c++17", "--use_fast_math", "-default-device"}
                    : new String[]{"--gpu-architecture=" + arch, "-std=c++17", "-fmad=false", "-prec-div=true", "-prec-sqrt=true", "-ftz=false",
                    "-default-device"};
            MemorySegment o = a.allocate(PTR, opts.length);
            for (int i = 0; i < opts.length; i++) o.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(opts[i]));
            int r = (int) compile.invoke(p, opts.length, o);
            if (r != 0) {
                MemorySegment ls = a.allocate(ValueLayout.JAVA_LONG);
                logSize.invoke(p, ls);
                MemorySegment log = a.allocate(Math.max(1, ls.get(ValueLayout.JAVA_LONG, 0)));
                getLog.invoke(p, log);
                destroy.invoke(prog);
                throw new IllegalStateException("NVRTC " + name + ": " + log.getString(0));
            }
            MemorySegment size = a.allocate(ValueLayout.JAVA_LONG);
            check((int) ptxSize.invoke(p, size), "nvrtcGetPTXSize");
            long n = size.get(ValueLayout.JAVA_LONG, 0);
            MemorySegment ptx = a.allocate(n);
            check((int) getPtx.invoke(p, ptx), "nvrtcGetPTX");
            destroy.invoke(prog);
            // ohne abschliessende Null (CudaCompute haengt sie selbst an)
            byte[] out = new byte[(int) Math.max(0, n - 1)];
            MemorySegment.copy(ptx, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("NVRTC " + name, e);
        }
    }

    private static void check(int r, String what) {
        if (r != 0) throw new IllegalStateException(what + ": nvrtcResult " + r);
    }

    @Override
    public void close() {
    }
}
