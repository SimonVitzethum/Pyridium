package simon.pyridium.client.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** Persistent renderer switches with explicit JVM overrides for disposable tests. */
public final class RendererSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pyridium-renderer.properties");
    public enum Feature {
        RAY_RECONSTRUCTION("rayReconstruction", "rayReconstruction", 0, false),
        RAY_EFFECTS("rayEffects", "rayEffects", 1, true),
        PATH_TRACING("pathTracing", "pathTracing", 2, true),
        HARDWARE_RT("hardwareRaytracing", "hardwareRaytracing", 4, true),
        SPECTATOR_CACHE("spectatorPrebuild", "spectatorCache", 0, false);
        private final String key, override, label;
        private final int mask;
        private final boolean defaultValue;
        Feature(String key, String override, int mask, boolean defaultValue) {
            this.key = key; this.override = "pyridium." + override; this.label = "pyridium.options." + override;
            this.mask = mask; this.defaultValue = defaultValue;
        }
        public String label() { return label; }
    }
    private static final Properties SETTINGS = load();
    private RendererSettings() {}

    public static boolean spectatorPrebuild() {
        return enabled(Feature.SPECTATOR_CACHE);
    }

    private static Properties load() {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) try (var in = Files.newInputStream(FILE)) { p.load(in); }
        catch (IOException e) { LoggerFactory.getLogger("Pyridium").warn("Renderer-Einstellungen konnten nicht geladen werden", e); }
        return p;
    }

    public static void spectatorPrebuild(boolean enabled) {
        enabled(Feature.SPECTATOR_CACHE, enabled);
    }

    public static synchronized boolean enabled(Feature feature) {
        return Boolean.parseBoolean(System.getProperty(feature.override,
            SETTINGS.getProperty(feature.key, Boolean.toString(feature.defaultValue))));
    }

    public static synchronized int nativeFeatures() {
        int flags = 0;
        for (Feature feature : Feature.values()) if (enabled(feature)) flags |= feature.mask;
        return flags;
    }

    public static synchronized void enabled(Feature feature, boolean enabled) {
        SETTINGS.setProperty(feature.key, Boolean.toString(enabled));
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = Files.createTempFile(FILE.getParent(), "pyridium-renderer-", ".tmp");
            try (var out = Files.newOutputStream(temporary)) { SETTINGS.store(out, "Pyridium CUDA renderer"); }
            try { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { LoggerFactory.getLogger("Pyridium").warn("Renderer-Einstellungen konnten nicht gespeichert werden", e); }
    }
}
