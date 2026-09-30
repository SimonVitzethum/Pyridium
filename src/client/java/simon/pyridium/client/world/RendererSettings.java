package simon.pyridium.client.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** One end-user switch; memory policy remains native and automatic. */
public final class RendererSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pyridium-renderer.properties");
    private static volatile boolean spectatorPrebuild = load();
    private RendererSettings() {}

    public static boolean spectatorPrebuild() {
        return Boolean.getBoolean("pyridium.spectatorCache") || spectatorPrebuild;
    }

    private static boolean load() {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) try (var in = Files.newInputStream(FILE)) { p.load(in); }
        catch (IOException e) { LoggerFactory.getLogger("Pyridium").warn("Renderer-Einstellungen konnten nicht geladen werden", e); }
        return Boolean.parseBoolean(p.getProperty("spectatorPrebuild", "false"));
    }

    public static void spectatorPrebuild(boolean enabled) {
        spectatorPrebuild = enabled;
        Properties p = new Properties();
        p.setProperty("spectatorPrebuild", Boolean.toString(enabled));
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = Files.createTempFile(FILE.getParent(), "pyridium-renderer-", ".tmp");
            try (var out = Files.newOutputStream(temporary)) { p.store(out, "Pyridium CUDA renderer"); }
            try { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { LoggerFactory.getLogger("Pyridium").warn("Renderer-Einstellungen konnten nicht gespeichert werden", e); }
    }
}
