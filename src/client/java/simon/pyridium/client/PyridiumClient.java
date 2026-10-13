package simon.pyridium.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import simon.pyridium.client.world.SectionSync;
import org.slf4j.Logger;

public class PyridiumClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * /pyridium reload | pack <name> | options | option <id> <wert>: Shaderpacks
	 * heissladen, wechseln und Optionen live aendern (Menue-Haken; ein
	 * Bildschirm kann dieselbe ShaderPacks-API benutzen).
	 */
	private static void registerShaderPackCommands() {
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("pyridium")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("reload").executes(c -> {
					simon.pyridium.client.world.ShaderPacks.reload();
					c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Pyridium: Shaderpack '" + simon.pyridium.client.world.ShaderPacks.activePack() + "' neu gebaut"));
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("options").executes(c -> {
					for (var o : simon.pyridium.client.world.ShaderPacks.options()) {
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(o.id() + " = " + simon.pyridium.client.world.ShaderPacks.get(o.id()) + "  [" + o.min() + ".." + o.max() + "]  " + o.label()));
					}
					return 1;
				}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("pack")
					.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c -> {
						simon.pyridium.client.world.ShaderPacks.choose(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"));
						c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Pyridium: Shaderpack '" + simon.pyridium.client.world.ShaderPacks.activePack() + "' aktiv"));
						return 1;
					})))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("option")
					.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
						.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("wert", com.mojang.brigadier.arguments.FloatArgumentType.floatArg()).executes(c -> {
							simon.pyridium.client.world.ShaderPacks.setOption(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"), com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "wert"));
							return 1;
						}))))));
	}

	@Override
	public void onInitializeClient() {
		// Einzelspieler: Terrain ganz auf der GPU
		simon.pyridium.worldgen.GpuTerrain.setClientShare(1.0);
		ClientChunkEvents.CHUNK_LOAD.register(SectionSync::chunkLoaded);
		registerShaderPackCommands();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(simon.pyridium.client.world.LodGenerator::prepareAsync);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> simon.pyridium.client.world.LodGenerator.shutdown());
		ClientChunkEvents.CHUNK_UNLOAD.register(SectionSync::chunkUnloaded);
		LOGGER.info("Pyridium: Renderer = Pyrit (CUDA, Sparse Voxel Hash-DAG), Ausgabe ueber Wayland");
	}
}
