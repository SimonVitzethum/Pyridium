package simon.pyridium.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import simon.pyridium.client.world.SectionSync;
import org.slf4j.Logger;

public class PyridiumClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onInitializeClient() {
		// Einzelspieler: Terrain ganz auf der GPU
		simon.pyridium.worldgen.GpuTerrain.setClientShare(1.0);
		ClientChunkEvents.CHUNK_LOAD.register(SectionSync::chunkLoaded);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(simon.pyridium.client.world.LodGenerator::prepareAsync);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> simon.pyridium.client.world.LodGenerator.shutdown());
		ClientChunkEvents.CHUNK_UNLOAD.register(SectionSync::chunkUnloaded);
		LOGGER.info("Pyridium: Renderer = Pyrit (CUDA, Sparse Voxel Hash-DAG), Ausgabe ueber Wayland");
	}
}
