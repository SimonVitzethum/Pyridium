package simon.pyridium.client.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import simon.pyridium.client.gpu.Native;

/**
 * Anzeige im Minecraft-Fenster: das Bild geht als shm-Puffer direkt an die
 * wl_surface, die SDL für das Fenster angelegt hat. Kein zweites Fenster,
 * keine Grafik-API.
 */
public class PydSurface implements GpuSurfaceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.lang.management.OperatingSystemMXBean SYSTEM = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
	private final long window;
	private final BooleanSupplier iconified;
	private GpuSurface.@Nullable Configuration config;
	private @Nullable PydTextureView source;
	private boolean waylandReady;

	public PydSurface(long window, BooleanSupplier iconified) {
		this.window = window;
		this.iconified = iconified;
		if (Boolean.getBoolean("pyridium.headless")) {
			LOGGER.info("Pyridium: ohne Fenster (headless), Bilder nur als Aufnahmen");
			return;
		}
		int props = SDLVideo.SDL_GetWindowProperties(window);
		long display = SDLProperties.SDL_GetPointerProperty(props, "SDL.window.wayland.display", 0L);
		long surface = SDLProperties.SDL_GetPointerProperty(props, "SDL.window.wayland.surface", 0L);
		if (display == 0L || surface == 0L) {
			throw new IllegalStateException("Pyridium braucht Wayland (SDL-Videotreiber: " + SDLVideo.SDL_GetCurrentVideoDriver() + ")");
		}
		if (Native.presentInit(display, surface) != 0) {
			throw new IllegalStateException("Wayland-Ausgabe: " + Native.error());
		}
		this.waylandReady = true;
		LOGGER.info("Pyridium: Ausgabe direkt in die wl_surface des Minecraft-Fensters");
	}

	@Override
	public void configure(GpuSurface.Configuration config) throws SurfaceException {
		this.config = config;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		this.source = (PydTextureView)textureView;
	}

	@Override
	public void present() {
		PydTextureView s = this.source;
		this.source = null;
		if (s == null || this.config == null) {
			return;
		}
		PydTexture t = s.pyd();
		int r = 0;
		if (this.waylandReady && !this.iconified.getAsBoolean()) {
			boolean vsync = this.config.presentMode() == GpuSurface.PresentMode.FIFO;
			r = Native.present(t.levelLayer(s.baseMipLevel(), 0), s.getWidth(0), s.getHeight(0), this.config.width(), this.config.height(), vsync);
		} else {
			// ohne Anzeige: hoechstens ein Bild voraus (sonst misst die FPS-Anzeige nur das Einreihen)
			long ev = Native.eventRecord();
			if (this.lastFrameEvent != 0) {
				Native.eventWait(this.lastFrameEvent, true);
				Native.eventFree(this.lastFrameEvent);
			}
			this.lastFrameEvent = ev;
		}
		if (r != 0) {
			LOGGER.warn("Pyridium: Anzeige fehlgeschlagen: {}", Native.error());
		}
		this.frames++;
		long now = System.nanoTime();
		if (this.fpsStart == 0) {
			this.fpsStart = now;
            this.cpuStart = processCpuTime();
		} else if (now - this.fpsStart > 5_000_000_000L) {
			LOGGER.info("Pyridium: {} FPS ({}x{})", String.format("%.1f", (this.frames - this.fpsFrames) * 1e9 / (now - this.fpsStart)), s.getWidth(0), s.getHeight(0));
            long cpu = processCpuTime();
            if (cpu >= this.cpuStart && this.cpuStart >= 0) {
                double cores = (double)(cpu - this.cpuStart) / (now - this.fpsStart);
                LOGGER.info("Pyridium: CPU-Prozess {} Kerne, {} % von {} logischen CPUs", String.format("%.2f", cores),
                    String.format("%.1f", 100 * cores / SYSTEM.getAvailableProcessors()), SYSTEM.getAvailableProcessors());
            }
            this.cpuStart = cpu;
			this.fpsStart = now;
			this.fpsFrames = this.frames;
		}
		this.maybeShot(t, s);
	}

	private long frames;
	private long lastFrameEvent;
	private long fpsStart, fpsFrames;
    private long cpuStart;

    private static long processCpuTime() {
        return SYSTEM instanceof com.sun.management.OperatingSystemMXBean system ? system.getProcessCpuTime() : -1;
    }
	private long nextShot = System.currentTimeMillis() + 5000;
	private int shotIndex;
	private long lastBurstFrame = -1;

	/** -Dpyridium.shotDir=<dir>: alle 10 s das angezeigte Bild als PNG (Diagnose) */
	private void maybeShot(PydTexture t, PydTextureView v) {
		String dir = System.getProperty("pyridium.shotDir");
		long now = System.currentTimeMillis();
		// -Dpyridium.burstStart=<Weltframe> -Dpyridium.burstCount=<n>: n aufeinanderfolgende Frames (Rauschmessung)
		long burstStart = Long.getLong("pyridium.burstStart", -1L);
		if (dir != null && burstStart >= 0) {
			long wf = simon.pyridium.client.world.PyritWorld.worldFrames();
			if (wf < burstStart || wf >= burstStart + Long.getLong("pyridium.burstCount", 30L) || wf == this.lastBurstFrame) {
				return;
			}
			this.lastBurstFrame = wf;
		} else if (dir == null || now < this.nextShot) {
			return;
		} else {
			this.nextShot = now + Long.getLong("pyridium.shotEvery", 10000L);
		}
		int w = v.getWidth(0), h = v.getHeight(0);
		long buf = org.lwjgl.system.MemoryUtil.nmemAlloc((long)w * h * 4);
		try {
			Native.download2d(buf, (long)w * 4, t.levelLayer(v.baseMipLevel(), 0), (long)t.getWidth(v.baseMipLevel()) * 4, (long)w * 4, h);
			try (com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(w, h, false)) {
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int c = org.lwjgl.system.MemoryUtil.memGetInt(buf + ((long)(h - 1 - y) * w + x) * 4L);
						img.setPixelABGR(x, y, c | 0xff000000);
					}
				}
				java.nio.file.Path out = java.nio.file.Path.of(dir, String.format("pyd_%03d.png", this.shotIndex++));
				java.nio.file.Files.createDirectories(out.getParent());
				img.writeToFile(out);
				LOGGER.info("Pyridium: Aufnahme {} ({}x{}, Frame {})", out, w, h, this.frames);
			}
		} catch (Exception e) {
			LOGGER.warn("Pyridium: Aufnahme fehlgeschlagen", e);
		} finally {
			org.lwjgl.system.MemoryUtil.nmemFree(buf);
		}
	}


	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.MAILBOX);
	}

	@Override
	public void close() {
	}
}
