package simon.pyridium;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import simon.pyridium.worldgen.GpuTerrain;
import simon.pyridium.worldgen.WorldgenBench;

public class Pyridium implements ModInitializer {
	public static final String MOD_ID = "pyridium";

	@Override
	public void onInitialize() {
		// Server-Seite (dediziert und integriert): exakte Terrain-Generierung auf der GPU (CUDA)
		ServerLifecycleEvents.SERVER_STARTING.register(GpuTerrain::serverStarting);
		ServerLifecycleEvents.SERVER_STOPPED.register(GpuTerrain::serverStopped);
		ServerLifecycleEvents.SERVER_STARTED.register(WorldgenBench::serverStarted);
	}
}
