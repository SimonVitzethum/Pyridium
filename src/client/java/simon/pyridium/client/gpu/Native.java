package simon.pyridium.client.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * FFM-Anbindung an libpyridium.so (native/pyrit/pyridium). Zeiger werden als
 * long übergeben (x86-64: gleiche Aufrufkonvention wie Adressen).
 */
public final class Native {
	private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private static SymbolLookup lib;
    private static MethodHandle hModelCreate, hModelDestroy, hModelsFrame;
	private static final Linker LINKER = Linker.nativeLinker();

	private static MethodHandle hInit, hError, hAlloc, hFree, hUpload, hUpload2d, hDownload2d, hCopy, hCopy2d, hSync, hFill,
		hComposite, hWorldConfig, hWorldDestroy, hSetLod, hMaterial, hSecStates, hSecPut, hSecRemove, hColSet, hPresentInit, hPresent, hWorldCreate, hWorldFrame, hWorldSetMode, hMemInfo, hDeviceName, hWorldStats, hPlanInfo, hEvRecord, hEvWait, hEvFree;

	private Native() {
	}

	public static synchronized void load() {
		if (lib != null) {
			return;
		}
		Path path = locate();
		lib = SymbolLookup.libraryLookup(path, Arena.global());
        hInit = fn("pyd_init", FunctionDescriptor.of(I));
        hModelCreate = fn("pyd_model_create", FunctionDescriptor.of(L, L, I, I, I));
        hModelDestroy = fn("pyd_model_destroy", FunctionDescriptor.of(I, L));
        hModelsFrame = fn("pyd_models_frame", FunctionDescriptor.of(I, L, I));
		hError = fn("pyd_error", FunctionDescriptor.of(ValueLayout.ADDRESS));
		hAlloc = fn("pyd_alloc", FunctionDescriptor.of(L, L));
		hFree = fn("pyd_free", FunctionDescriptor.ofVoid(L));
		hUpload = fn("pyd_upload", FunctionDescriptor.of(I, L, L, L));
		hUpload2d = fn("pyd_upload2d", FunctionDescriptor.of(I, L, L, L, L, L, L));
		hDownload2d = fn("pyd_download2d", FunctionDescriptor.of(I, L, L, L, L, L, L));
		hCopy = fn("pyd_copy", FunctionDescriptor.of(I, L, L, L));
		hCopy2d = fn("pyd_copy2d", FunctionDescriptor.of(I, L, L, L, L, L, L));
		hSync = fn("pyd_sync", FunctionDescriptor.of(I));
		hFill = fn("pyd_fill", FunctionDescriptor.of(I, L, I, I, I, I, I, I, L));
		hComposite = fn("pyd_composite", FunctionDescriptor.of(I, L, I, I, L, I, L, I, L));
		hWorldConfig = fn("pyd_world_configure", FunctionDescriptor.of(I, ValueLayout.JAVA_FLOAT, I, ValueLayout.JAVA_FLOAT));
		hWorldDestroy = fn("pyd_world_destroy", FunctionDescriptor.ofVoid());
		hSetLod = fn("pyd_set_lod_generator", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		hMaterial = fn("pyd_material_define", FunctionDescriptor.of(I, I, L, L, I, I, I, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
		hSecStates = fn("pyd_sec_states", FunctionDescriptor.of(I, L, L, I));
		hSecPut = fn("pyd_sec_put", FunctionDescriptor.of(I, I, I, I, L, I));
		hSecRemove = fn("pyd_sec_remove", FunctionDescriptor.ofVoid(I, I, I));
		hColSet = fn("pyd_col_set", FunctionDescriptor.ofVoid(I, I, I));
		hPresentInit = fn("pyd_present_init", FunctionDescriptor.of(I, L, L));
		hPresent = fn("pyd_present", FunctionDescriptor.of(I, L, I, I, I, I, I));
		hWorldCreate = fn("pyd_world_create", FunctionDescriptor.of(I, L, I));
		hWorldFrame = fn("pyd_world_frame", FunctionDescriptor.of(I, L));
		hWorldSetMode = fn("pyd_world_set_mode", FunctionDescriptor.ofVoid(I));
		hWorldStats = fn("pyd_world_stats", FunctionDescriptor.ofVoid(L));
		hPlanInfo = fn("pyd_world_plan_info", FunctionDescriptor.of(I, L));
		hEvRecord = fn("pyd_event_record", FunctionDescriptor.of(L));
		hEvWait = fn("pyd_event_wait", FunctionDescriptor.of(I, L, I));
		hEvFree = fn("pyd_event_free", FunctionDescriptor.ofVoid(L));
		hMemInfo = fn("pyd_mem_info", FunctionDescriptor.ofVoid(L, L));
		hDeviceName = fn("pyd_device_name", FunctionDescriptor.of(I, L, I));
	}

    private static MethodHandle fn(String name, FunctionDescriptor d) {
		MemorySegment s = lib.find(name).orElseThrow(() -> new IllegalStateException("libpyridium: " + name + " fehlt"));
		// CUDA calls can block, and worldFrame can call the Java LOD generator
		// on this thread. Critical downcalls forbid upcalls and caused a fatal
		// JVM thread-state assertion in the synchronous headless test path.
		return LINKER.downcallHandle(s, d);
    }

    public static long modelCreate(long faces, int faceCount, int voxelCount, int log2Size) {
        try {
            return (long)hModelCreate.invokeExact(faces, faceCount, voxelCount, log2Size);
        } catch (Throwable t) { throw wrap(t); }
    }

    public static void modelsFrame(long instances, int count) {
        try {
            check((int)hModelsFrame.invokeExact(instances, count), "modelsFrame");
        } catch (Throwable t) { throw wrap(t); }
    }

    public static void modelDestroy(long geometry) {
        try {
            check((int)hModelDestroy.invokeExact(geometry), "modelDestroy");
        } catch (Throwable t) { throw wrap(t); }
    }

	/** Entwicklung: -Dpyridium.nativeDir; sonst aus dem Jar entpacken. */
	private static Path locate() {
		String dir = System.getProperty("pyridium.nativeDir");
		if (dir != null) {
			Path p = Path.of(dir, "libpyridium.so");
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		try (InputStream in = Native.class.getResourceAsStream("/natives/linux-x86_64/libpyridium.so")) {
			if (in == null) {
				throw new IllegalStateException("libpyridium.so weder in pyridium.nativeDir noch im Jar");
			}
			Path tmp = Files.createTempFile("libpyridium", ".so");
			Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
			tmp.toFile().deleteOnExit();
			return tmp;
		} catch (IOException e) {
			throw new IllegalStateException("libpyridium.so nicht entpackbar", e);
		}
	}

	private static RuntimeException wrap(Throwable t) {
		return t instanceof RuntimeException r ? r : new RuntimeException(t);
	}

	public static String error() {
		try {
			MemorySegment s = (MemorySegment)hError.invokeExact();
			return s.reinterpret(4096).getString(0);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	private static void check(int r, String what) {
		if (r != 0) {
			throw new IllegalStateException("Pyridium " + what + ": " + error());
		}
	}

	public static void init() {
		try {
			check((int)hInit.invokeExact(), "init");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static long alloc(long bytes) {
		try {
			long p = (long)hAlloc.invokeExact(bytes);
			if (p == 0L) {
				throw new IllegalStateException("Pyridium: kein Grafikspeicher fuer " + bytes + " Bytes: " + error());
			}
			return p;
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void free(long p) {
		try {
			hFree.invokeExact(p);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void upload(long dst, long src, long n) {
		try {
			check((int)hUpload.invokeExact(dst, src, n), "upload");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void upload2d(long dst, long dpitch, long src, long spitch, long wbytes, long rows) {
		try {
			check((int)hUpload2d.invokeExact(dst, dpitch, src, spitch, wbytes, rows), "upload2d");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void download2d(long dst, long dpitch, long src, long spitch, long wbytes, long rows) {
		try {
			check((int)hDownload2d.invokeExact(dst, dpitch, src, spitch, wbytes, rows), "download2d");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void copy(long dst, long src, long n) {
		try {
			check((int)hCopy.invokeExact(dst, src, n), "copy");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void copy2d(long dst, long dpitch, long src, long spitch, long wbytes, long rows) {
		try {
			check((int)hCopy2d.invokeExact(dst, dpitch, src, spitch, wbytes, rows), "copy2d");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void sync() {
		try {
			check((int)hSync.invokeExact(), "sync");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void fill(long dst, int w, int bpp, int x0, int y0, int rw, int rh, long value4) {
		try {
			check((int)hFill.invokeExact(dst, w, bpp, x0, y0, rw, rh, value4), "fill");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void composite(long target, int tw, int th, long sprites, int count, long texs, int ntex, long region) {
		try {
			check((int)hComposite.invokeExact(target, tw, th, sprites, count, texs, ntex, region), "composite");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void worldConfigure(float voxelPixels, int viewChunks, float targetFps) {
		try {
			check((int)hWorldConfig.invokeExact(voxelPixels, viewChunks, targetFps), "worldConfigure");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void worldDestroy() {
		try {
			hWorldDestroy.invokeExact();
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void setLodGenerator(MemorySegment f) {
		try {
			hSetLod.invokeExact(f);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void materialDefine(int index, long top, long side, int w, int h, int flags, float roughness, float emission) {
		try {
			check((int)hMaterial.invokeExact(index, top, side, w, h, flags, roughness, emission), "material");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void secStates(long attr, long flags, int count) {
		try {
			check((int)hSecStates.invokeExact(attr, flags, count), "sec_states");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void secPut(int sx, int sy, int sz, long data, int uniform) {
		try {
			check((int)hSecPut.invokeExact(sx, sy, sz, data, uniform), "sec_put");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void secRemove(int sx, int sy, int sz) {
		try {
			hSecRemove.invokeExact(sx, sy, sz);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void colSet(int cx, int cz, boolean loaded) {
		try {
			hColSet.invokeExact(cx, cz, loaded ? 1 : 0);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static int presentInit(long display, long surface) {
		try {
			return (int)hPresentInit.invokeExact(display, surface);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static int present(long src, int sw, int sh, int dw, int dh, boolean vsync) {
		try {
			return (int)hPresent.invokeExact(src, sw, sh, dw, dh, vsync ? 1 : 0);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void worldCreate(long seed, int mode) {
		try {
			check((int)hWorldCreate.invokeExact(seed, mode), "world_create");
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static int worldFrame(long cam) {
		try {
			return (int)hWorldFrame.invokeExact(cam);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void worldSetMode(int mode) {
		try {
			hWorldSetMode.invokeExact(mode);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	/** Ereignis am Ende der eingereihten Arbeit (0 = Fehler) */
	public static long eventRecord() {
		try {
			return (long)hEvRecord.invokeExact();
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	/** true = fertig; block = false nur nachsehen */
	public static boolean eventWait(long ev, boolean block) {
		try {
			return (int)hEvWait.invokeExact(ev, block ? 1 : 0) != 0;
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void eventFree(long ev) {
		try {
			hEvFree.invokeExact(ev);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	/** Planer-Parameter nach out (11 doubles), 0 = ok */
	public static int planInfo(long out) {
		try {
			return (int)hPlanInfo.invokeExact(out);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static void worldStats(long out) {
		try {
			hWorldStats.invokeExact(out);
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static long[] memInfo() {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment f = a.allocate(8), tt = a.allocate(8);
			hMemInfo.invokeExact(f.address(), tt.address());
			return new long[]{f.get(L, 0), tt.get(L, 0)};
		} catch (Throwable t) {
			throw wrap(t);
		}
	}

	public static String deviceName() {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment b = a.allocate(256);
			int r = (int)hDeviceName.invokeExact(b.address(), 256);
			return r == 0 ? b.getString(0) : "CUDA";
		} catch (Throwable t) {
			throw wrap(t);
		}
	}
}
