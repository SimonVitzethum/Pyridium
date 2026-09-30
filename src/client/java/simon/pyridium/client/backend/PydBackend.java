package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import simon.pyridium.client.gpu.Native;

/**
 * Grafik-"Backend" ohne Grafik-API: CUDA für Speicher und Compositor, Pyrit
 * für die Welt, Wayland-shm für die Anzeige.
 */
public class PydBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Pyridium (CUDA/Pyrit)";
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		try {
			Native.load();
			Native.init();
		} catch (RuntimeException e) {
			throw new BackendCreationException("Pyridium: " + e.getMessage(), BackendCreationException.Reason.OTHER);
		}
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		// weder SDL_WINDOW_OPENGL noch SDL_WINDOW_VULKAN: das Bild kommt über wl_shm
		if (Boolean.getBoolean("pyridium.headless")) {
			flags |= SDLVideo.SDL_WINDOW_HIDDEN;
		}
		return SDLVideo.SDL_CreateWindow(title, width, height, flags);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		return new FrontendGpuDevice(new PydDevice());
	}
}
