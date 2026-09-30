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
	private static boolean failed;
	private static volatile boolean ready;
	private static long nextLog;
	private static long camStart;

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
		return switch (m) {
			case "native" -> 0;
			case "dlaa" -> 2;
			case "dlss" -> 3;
			case "rr" -> 4;
			default -> 1;
		};
	}

	public static void render(CameraRenderState camera) {
		if (failed || !camera.initialized) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		try {
			if (!created) {
				long seed = 1L;
				IntegratedServer server = mc.getSingleplayerServer();
				if (server != null) {
					seed = server.overworld().getSeed();
				}
				// Paletten/Materialien vor dem Generator, der sie benutzt
				Native.worldCreate(seed, mode());
				// LOD unsichtbar: ein Voxel jeder Stufe hoechstens ~1 Pixel gross
				Native.worldConfigure(Float.parseFloat(System.getProperty("pyridium.voxelPx", "1.0")),
					Integer.getInteger("pyridium.viewChunks", 1024), Float.parseFloat(System.getProperty("pyridium.targetFps", "165")));
				SectionSync.tick(0);
				if (server != null) {
					LodGenerator.setup(server); // meist schon beim Serverstart geschehen
				}
				Native.setLodGenerator(LodGenerator.upcallStub());
				created = true;
				LOGGER.info("Pyridium: Pyrit-Welt angelegt (Samen {})", seed);
			}
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
				float yaw = (float)Math.toRadians(Float.parseFloat(v[3])), pitch = (float)Math.toRadians(Float.parseFloat(v[4]));
				f = new Vector3f((float)(-Math.sin(yaw) * Math.cos(pitch)), (float)-Math.sin(pitch), (float)(Math.cos(yaw) * Math.cos(pitch)));
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
			MemoryUtil.memPutFloat(a + 40, u.x);
			MemoryUtil.memPutFloat(a + 44, u.y);
			MemoryUtil.memPutFloat(a + 48, u.z);
			MemoryUtil.memPutFloat(a + 52, (float)((System.nanoTime() - START) * 1e-9));
			MemoryUtil.memPutInt(a + 56, target.getWidth(0));
			MemoryUtil.memPutInt(a + 60, target.getHeight(0));
			MemoryUtil.memPutLong(a + 64, target.ptr);
			MemoryUtil.memPutFloat(a + 72, 0.9f);
			MemoryUtil.memPutFloat(a + 76, 3.0f);
			MemoryUtil.memPutInt(a + 80, 0);
			if (System.currentTimeMillis() > nextLog) {
				nextLog = System.currentTimeMillis() + 10000;
				LOGGER.info("Pyridium: Kamera {} {} {} Blick {} {} {}", String.format("%.1f", p.x), String.format("%.1f", p.y), String.format("%.1f", p.z),
					String.format("%.2f", f.x), String.format("%.2f", f.y), String.format("%.2f", f.z));
				Native.worldStats(MemoryUtil.memAddress(STATS));
				LOGGER.info("Pyridium: {} Chunks sichtbar, {} resident, {} offen; LOD {} px/Voxel, {} MiB, {} Ueberlaeufe",
					STATS.getInt(0), STATS.getInt(4), STATS.getInt(8), STATS.getFloat(24), STATS.getLong(40) >> 20, STATS.getInt(20));
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
