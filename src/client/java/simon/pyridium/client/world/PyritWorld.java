package simon.pyridium.client.world;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import simon.pyridium.client.backend.PydTexture;
import simon.pyridium.client.gpu.Native;

/** Brücke zu Pyrits Welt: Kamera aus Minecraft, Bild ins Hauptziel. */
public final class PyritWorld {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final ByteBuffer CAM = MemoryUtil.memCalloc(96);
	private static final ByteBuffer STATS = MemoryUtil.memCalloc(48);
	private static boolean created;
    private static int createdFeatures;
    private static int requestedMode;
	private static boolean failed;
	private static volatile boolean ready;
	private static long nextLog;
	private static long camStart;
	private static long worldFrames;

	/** Diagnose: Weltframes seit Start der festen Testkamera */
	public static long worldFrames() {
		return worldFrames;
	}


	public static boolean isReady() {
		return ready || failed;
	}
	private static final long START = System.nanoTime();

	private PyritWorld() {
	}

	/** Weltwechsel/Verlassen: alles freigeben, beim naechsten Weltframe neu anlegen */
	public static void levelChanged(Object level) {
		if (created) {
			LodGenerator.shutdown();
			Native.worldDestroy();
			LOGGER.info("Pyridium: Pyrit-Welt freigegeben");
		}
		created = false;
		ready = false;
		failed = false;
        SectionSync.reset();
        EntityScene.reset();
        RendererTestFixture.reset();
	}

	public static int mode() {
		String m = System.getProperty("pyridium.mode", "rr");
        // Ohne DLSS-RR: nativ mit eigenem Entrauscher und Jitter-TAA (schaerfer und guenstiger als TAAU in 1:1)
        if (m.equals("rr") && !RendererSettings.enabled(RendererSettings.Feature.RAY_RECONSTRUCTION)) m = "native";
		return switch (m) {
			case "native" -> 0;
			case "dlaa" -> 2;
			case "dlss" -> 3;
			case "rr" -> 4;
			default -> 1;
		};
	}

	/** Terrain-Benchmark: der Renderer ruht, die GPU gehoert dem Generator (-Dpyridium.skipWorldRender=true) */
	private static final boolean SKIP_WORLD = Boolean.getBoolean("pyridium.skipWorldRender");

	public static void render(CameraRenderState camera) {
		if (failed || !camera.initialized || SKIP_WORLD) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		try {
            int features = RendererSettings.nativeFeatures();
            if (created && (BlockPalette.resourcesChanged() || ((features ^ createdFeatures) & 4) != 0)) {
                var loaded = new java.util.ArrayList<net.minecraft.world.level.chunk.LevelChunk>();
                SectionSync.loadedChunks().forEach(loaded::add);
                levelChanged(mc.level);
                for (var chunk : loaded) SectionSync.chunkLoaded(mc.level, chunk);
                LOGGER.info("Pyridium: Ressourcen oder RT-Core-Auswahl geaendert; Welt- und Material-Caches werden neu aufgebaut");
            }
			if (!created) {
				long seed = 1L;
				IntegratedServer server = mc.getSingleplayerServer();
				if (server != null) {
					seed = server.overworld().getSeed();
				}
				// Paletten/Materialien vor dem Generator, der sie benutzt
                requestedMode = mode();
                ShaderPacks.init();
                Native.worldCreate(seed, requestedMode, features);
                String packMessage = Native.packMessage();
                if (!packMessage.isEmpty()) {
                    LOGGER.warn("Pyridium: Shaderpack: {}", packMessage);
                }
                createdFeatures = features;
                float targetFps = Float.parseFloat(System.getProperty("pyridium.targetFps", "165"));
				// LOD unsichtbar: ein Voxel jeder Stufe hoechstens ~1 Pixel gross
				Native.worldConfigure(Float.parseFloat(System.getProperty("pyridium.voxelPx", "1.0")),
                    Integer.getInteger("pyridium.viewChunks", 1024), targetFps);
                // Vanilla defaults to 120 FPS; that must not cap an explicit 165-FPS renderer target.
                int limit = Math.clamp((int)(Math.ceil(targetFps / 10) * 10), 10, 260);
                if (targetFps > 0 && mc.options.framerateLimit().get() < limit) mc.options.framerateLimit().set(limit);
				SectionSync.tick(0);
				if (server != null) {
					LodGenerator.setup(server); // meist schon beim Serverstart geschehen
				}
				Native.setLodGenerator(LodGenerator.upcallStub());
				created = true;
				LOGGER.info("Pyridium: Pyrit-Welt angelegt (Samen {})", seed);
			}
            if (features != createdFeatures) {
                Native.worldSetFeatures(features);
                createdFeatures = features;
            }
            int desiredMode = mode();
            if (desiredMode != requestedMode) {
                Native.worldSetMode(desiredMode);
                requestedMode = desiredMode; // Do not overwrite a native fallback every frame.
            }
			ShaderPacks.poll();
			SectionSync.tick(Integer.getInteger("pyridium.sectionsPerFrame", 2048));
			PydTexture target = (PydTexture)mc.gameRenderer.mainRenderTarget().getColorTexture();
			long a = MemoryUtil.memAddress(CAM);
			Vec3 p = camera.pos;
			Vector3f f = camera.orientation.transform(new Vector3f(0, 0, -1));
			Vector3f u = camera.orientation.transform(new Vector3f(0, 1, 0));
			// Diagnose: feste Kamera -Dpyridium.cam=x,y,z,yaw,pitch (Grad, Minecraft-Konvention)
			String fixed = System.getProperty("pyridium.cam");
			if (fixed != null) {
				// mehrere Posen mit ';' getrennt: Wechsel alle pyridium.camEvery ms (Standard wie Aufnahmen)
				String[] poses = fixed.split(";");
				long every = Long.getLong("pyridium.camEvery", Long.getLong("pyridium.shotEvery", 10000L));
				int pi = (int)(((System.currentTimeMillis() - START / 1000000L) / Math.max(every, 1L)) % poses.length);
				if (camStart == 0L) {
					camStart = System.currentTimeMillis();
				}
				pi = (int)(((System.currentTimeMillis() - camStart) / Math.max(every, 1L)) % poses.length);
				String[] v = poses[pi].split(",");
				p = new Vec3(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]));
				// Diagnose: -Dpyridium.camYawRate=Grad/s und -Dpyridium.camSpeed=Bloecke/s lassen die feste Pose wandern
				// -Dpyridium.camFrameDt=<s>: Pose haengt vom Frameindex ab (reproduzierbar, fuer Rauschvergleiche)
				double frameDt = Double.parseDouble(System.getProperty("pyridium.camFrameDt", "0"));
				double secs = frameDt > 0 ? worldFrames * frameDt : (System.currentTimeMillis() - camStart) * 1e-3;
				worldFrames++;
				float yawRate = Float.parseFloat(System.getProperty("pyridium.camYawRate", "0"));
				double speed = Double.parseDouble(System.getProperty("pyridium.camSpeed", "0"));
				float yaw = (float)Math.toRadians(Float.parseFloat(v[3]) + yawRate * secs), pitch = (float)Math.toRadians(Float.parseFloat(v[4]));
				f = new Vector3f((float)(-Math.sin(yaw) * Math.cos(pitch)), (float)-Math.sin(pitch), (float)(Math.cos(yaw) * Math.cos(pitch)));
				if (speed != 0) {
					p = p.add(f.x * speed * secs, 0, f.z * speed * secs);
				}
				u = new Vector3f(0, 1, 0);
			}
            EntityScene.render(camera, p);
            MemoryUtil.memPutDouble(a, p.x);
			MemoryUtil.memPutDouble(a + 8, p.y);
			MemoryUtil.memPutDouble(a + 16, p.z);
			MemoryUtil.memPutFloat(a + 24, f.x);
			MemoryUtil.memPutFloat(a + 28, f.y);
			MemoryUtil.memPutFloat(a + 32, f.z);
			float m11 = camera.projectionMatrix.m11();
			float fovY = m11 != 0 ? (float)(2.0 * Math.atan(1.0 / Math.abs(m11))) : 1.2f;
			if (!(fovY > 0.05f && fovY < 3.0f)) {
				fovY = 1.2f;
			}
			MemoryUtil.memPutFloat(a + 36, fovY);
			// Simulationsbereich: dort kommen die echten Chunks vom Server, der Fernbereich rechnet sie nie
			Native.simArea((int)Math.floor(p.x) >> 4, (int)Math.floor(p.z) >> 4, mc.options.simulationDistance().get());
			// Pixel je Block in Entfernung 1: bestimmt, ab welcher Stufe Hoehlen nicht mehr sichtbar sind (LodGenerator)
			LodGenerator.pixelsPerBlock = 0.5 * target.getHeight(0) / Math.tan(fovY * 0.5);
			MemoryUtil.memPutFloat(a + 40, u.x);
			MemoryUtil.memPutFloat(a + 44, u.y);
			MemoryUtil.memPutFloat(a + 48, u.z);
			MemoryUtil.memPutFloat(a + 52, (float)((System.nanoTime() - START) * 1e-9));
			MemoryUtil.memPutInt(a + 56, target.getWidth(0));
			MemoryUtil.memPutInt(a + 60, target.getHeight(0));
			MemoryUtil.memPutLong(a + 64, target.ptr);
			MemoryUtil.memPutFloat(a + 72, 0.9f);
			MemoryUtil.memPutFloat(a + 76, 3.0f);
			MemoryUtil.memPutInt(a + 80, (mc.player != null && mc.player.isSpectator() ? 1 : 0) |
                (RendererSettings.spectatorPrebuild() ? 2 : 0));
			if (System.currentTimeMillis() > nextLog) {
				nextLog = System.currentTimeMillis() + 10000;
				LOGGER.info("Pyridium: Kamera {} {} {} Blick {} {} {}", String.format("%.1f", p.x), String.format("%.1f", p.y), String.format("%.1f", p.z),
					String.format("%.2f", f.x), String.format("%.2f", f.y), String.format("%.2f", f.z));
				Native.worldStats(MemoryUtil.memAddress(STATS));
				LOGGER.info("Pyridium: {} Chunks sichtbar, {} resident, {} offen; LOD {} px/Voxel, {} MiB, {} Ueberlaeufe",
					STATS.getInt(0), STATS.getInt(4), STATS.getInt(8), STATS.getFloat(24), STATS.getLong(40) >> 20, STATS.getInt(20));
			}
			String timing = Native.timingReport();
			if (timing != null) {
				LOGGER.info("Pyridium: {}", timing);
			}
			if (Native.worldFrame(a) != 0) {
				LOGGER.error("Pyridium: Weltframe fehlgeschlagen: {}", Native.error());
			} else {
				ready = true;
			}
		} catch (RuntimeException e) {
			failed = true;
			LOGGER.error("Pyridium: Welt abgeschaltet", e);
		}
	}
}
