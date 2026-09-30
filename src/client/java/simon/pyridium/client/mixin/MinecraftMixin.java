package simon.pyridium.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.pyridium.client.world.PyritWorld;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/** Neue oder keine Welt: Pyrits Welt freigeben (neu angelegt beim naechsten Weltframe) */
	@Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
	private void pyridium$level(@Nullable ClientLevel level, boolean stopSound, CallbackInfo ci) {
		PyritWorld.levelChanged(level);
	}

	/** Testschalter -Dpyridium.autoQuit=Sekunden: "Speichern und zum Titelbild" ausloesen */
	@Inject(method = "tick", at = @At("TAIL"))
	private void pyridium$autoQuit(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        simon.pyridium.client.world.RendererTestFixture.tick(mc);
		// A headless test must also terminate if onboarding or world loading
		// prevents joining. Independent of the in-world autoQuit timer.
		if (Boolean.getBoolean("pyridium.headless")) {
			if (pyridium$testStarted == 0L) pyridium$testStarted = System.currentTimeMillis();
			long deadline = Long.getLong("pyridium.testTimeout", 180L);
			if (System.currentTimeMillis() - pyridium$testStarted > deadline * 1000L) {
				org.slf4j.LoggerFactory.getLogger("Pyridium").warn("Pyridium: Zeitlimit des Headless-Tests erreicht");
				mc.stop();
				return;
			}
		}
		Long after = Long.getLong("pyridium.autoQuit");
		if (after == null || mc.level == null || mc.player == null) {
			return;
		}
        // Texture baking/world startup is not part of the scene-evaluation
        // interval. Allow a full capture sequence after the first world frame.
        if (!PyritWorld.isReady()) return;
		if (pyridium$joined == 0L) {
			pyridium$joined = System.currentTimeMillis();
		}
		if (System.currentTimeMillis() - pyridium$joined > after * 1000L) {
			pyridium$joined = Long.MAX_VALUE / 2;
			org.slf4j.LoggerFactory.getLogger("Pyridium").info("Pyridium: Test - Speichern und Verlassen");
			mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			if (Boolean.getBoolean("pyridium.headless")) {
				mc.stop();
			}
		}
	}

	private long pyridium$joined;
	private long pyridium$testStarted;
}
