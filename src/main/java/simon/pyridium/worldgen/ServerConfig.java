package simon.pyridium.worldgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * config/pyridium-server.properties: gpuShare (0-100 %, 0 = aus; nur dedizierter Server) und
 * parallelFeatures. Rechen-Backend ist immer CUDA; Chunks speichert Pyridium wie Vanilla. Fehlende
 * Datei oder Schluessel werden mit Standardwerten ergaenzt.
 */
public final class ServerConfig {
    private static final Logger LOG = LoggerFactory.getLogger("pyridium/config");

    /** GPU-Anteil 0..1 oder -1 (nicht gesetzt). */
    public final double gpuShare;
    /** FEATURES-Stufe parallel (FeatureScheduler). */
    public final boolean parallelFeatures;

    private ServerConfig(double gpuShare, boolean parallelFeatures) {
        this.gpuShare = gpuShare;
        this.parallelFeatures = parallelFeatures;
    }

    public static ServerConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("pyridium-server.properties");
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(file)) try (var r = Files.newBufferedReader(file)) {
                p.load(r);
            }
            StringBuilder add = new StringBuilder();
            if (!Files.isRegularFile(file))
                add.append("# Pyridium auf dem Server: exakte Terrain-Generierung auf der GPU per CUDA (bitgleich zu Vanilla).\n");
            if (!p.containsKey("gpuShare")) {
                add.append("# gpuShare: Anteil der GPU-Zeit in Prozent (1-100), 0 = aus (Vanilla rechnet).\ngpuShare=100\n");
                p.setProperty("gpuShare", "100");
            }
            if (!p.containsKey("parallelFeatures")) {
                add.append("""
                        # parallelFeatures (experimentell): true = Features (Baeume, Erze, Strukturen) nicht
                        # ueberlappender Chunks gleichzeitig auf allen Kernen setzen statt strikt nacheinander.
                        parallelFeatures=false
                        """);
                p.setProperty("parallelFeatures", "false");
            }
            if (!add.isEmpty()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, add.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            double share = Math.clamp(Integer.parseInt(p.getProperty("gpuShare").trim()), 0, 100) / 100.0;
            boolean parallel = Boolean.parseBoolean(p.getProperty("parallelFeatures").trim());
            return new ServerConfig(share, parallel);
        } catch (Exception e) {
            LOG.warn("{} nicht lesbar ({}), nutze Standardwerte", file, e.toString());
            return new ServerConfig(-1, false);
        }
    }
}
