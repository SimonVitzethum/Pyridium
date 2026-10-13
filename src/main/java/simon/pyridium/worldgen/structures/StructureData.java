package simon.pyridium.worldgen.structures;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Datenquelle der Strukturregistry (Vanilla + Datapacks). Nur beim Laden benutzt: die Daten werden
 * zu GPU-Tabellen kompiliert ({@link StructureCompiler}), zur Laufzeit liest nichts mehr hieraus.
 */
public interface StructureData {
    /** Alle JSON-Ressourcen unter data/&lt;ns&gt;/&lt;dir&gt;/**.json: Id ("ns:pfad", ohne Endung) -&gt; Inhalt (oberstes Paket gewinnt). */
    Map<String, JsonElement> json(String dir);

    /** Alle Pakete einer JSON-Ressource (Tags werden ueber Pakete zusammengefuehrt), unterstes zuerst. */
    List<JsonElement> jsonStack(String dir, String id);

    /** Rohbytes von data/&lt;ns&gt;/&lt;dir&gt;/&lt;pfad&gt;&lt;ext&gt;; null, wenn nicht vorhanden. */
    byte[] bytes(String dir, String id, String ext);

    /**
     * Server-Daten (wie {@link #of}); mit -Dpyridium.extraData=&lt;Ordner mit data/&gt; wird dort zusaetzlich ein Datapack-Verzeichnis
     * ueberlagert (Entwicklung/Test: eigene Strukturen ohne Weltordner-Eingriff, siehe tools/testpack).
     */
    static StructureData ofServer(ResourceManager rm) {
        StructureData base = of(rm);
        String extra = System.getProperty("pyridium.extraData");
        return extra == null || extra.isEmpty() ? base : overlay(base, java.nio.file.Path.of(extra));
    }

    /** Verzeichnis-Datapack (root/data/&lt;ns&gt;/&lt;dir&gt;/...) ueber eine Quelle legen: seine Dateien gewinnen. */
    static StructureData overlay(StructureData base, java.nio.file.Path root) {
        java.nio.file.Path data = root.resolve("data");
        return new StructureData() {
            private java.nio.file.Path file(String dir, String id, String ext) {
                Identifier rl = Identifier.parse(id);
                return data.resolve(rl.getNamespace()).resolve(dir).resolve(rl.getPath() + ext);
            }

            @Override
            public Map<String, JsonElement> json(String dir) {
                Map<String, JsonElement> out = new HashMap<>(base.json(dir));
                if (!java.nio.file.Files.isDirectory(data)) return out;
                try (var nss = java.nio.file.Files.list(data)) {
                    for (java.nio.file.Path ns : (Iterable<java.nio.file.Path>) nss::iterator) {
                        java.nio.file.Path d = ns.resolve(dir);
                        if (!java.nio.file.Files.isDirectory(d)) continue;
                        try (var walk = java.nio.file.Files.walk(d)) {
                            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) walk.filter(q -> q.toString().endsWith(".json"))::iterator) {
                                String rel = d.relativize(f).toString().replace('\\', '/');
                                try (var r = java.nio.file.Files.newBufferedReader(f)) {
                                    out.put(ns.getFileName() + ":" + rel.substring(0, rel.length() - 5), JsonParser.parseReader(r));
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Overlay " + root + ": " + e, e);
                }
                return out;
            }

            @Override
            public List<JsonElement> jsonStack(String dir, String id) {
                List<JsonElement> out = new ArrayList<>(base.jsonStack(dir, id));
                java.nio.file.Path f = file(dir, id, ".json");
                if (java.nio.file.Files.isRegularFile(f)) {
                    try (var r = java.nio.file.Files.newBufferedReader(f)) {
                        out.add(JsonParser.parseReader(r));
                    } catch (IOException e) {
                        throw new IllegalStateException("Overlay " + f + ": " + e, e);
                    }
                }
                return out;
            }

            @Override
            public byte[] bytes(String dir, String id, String ext) {
                java.nio.file.Path f = file(dir, id, ext);
                if (java.nio.file.Files.isRegularFile(f)) {
                    try {
                        return java.nio.file.Files.readAllBytes(f);
                    } catch (IOException e) {
                        throw new IllegalStateException("Overlay " + f + ": " + e, e);
                    }
                }
                return base.bytes(dir, id, ext);
            }
        };
    }

    /** Quelle auf Basis des Minecraft-ResourceManagers (Server-Daten inkl. Datapacks). */
    static StructureData of(ResourceManager rm) {
        return new StructureData() {
            @Override
            public Map<String, JsonElement> json(String dir) {
                Map<String, JsonElement> out = new HashMap<>();
                for (var e : rm.listResources(dir, id -> id.getPath().endsWith(".json")).entrySet()) {
                    Identifier id = e.getKey();
                    String path = id.getPath().substring(dir.length() + 1, id.getPath().length() - 5);
                    try (var r = new InputStreamReader(e.getValue().open(), StandardCharsets.UTF_8)) {
                        out.put(id.getNamespace() + ":" + path, JsonParser.parseReader(r));
                    } catch (IOException | RuntimeException ex) {
                        throw new IllegalStateException("Datei " + id + ": " + ex, ex);
                    }
                }
                return out;
            }

            @Override
            public List<JsonElement> jsonStack(String dir, String id) {
                List<JsonElement> out = new ArrayList<>();
                Identifier rl = Identifier.parse(id);
                Identifier file = Identifier.fromNamespaceAndPath(rl.getNamespace(), dir + "/" + rl.getPath() + ".json");
                for (Resource r : rm.getResourceStack(file)) {
                    try (var in = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                        out.add(JsonParser.parseReader(in));
                    } catch (IOException | RuntimeException ex) {
                        throw new IllegalStateException("Datei " + file + ": " + ex, ex);
                    }
                }
                return out;
            }

            @Override
            public byte[] bytes(String dir, String id, String ext) {
                Identifier rl = Identifier.parse(id);
                Identifier file = Identifier.fromNamespaceAndPath(rl.getNamespace(), dir + "/" + rl.getPath() + ext);
                var res = rm.getResource(file);
                if (res.isEmpty()) return null;
                try (InputStream in = res.get().open()) {
                    return in.readAllBytes();
                } catch (IOException ex) {
                    throw new IllegalStateException("Datei " + file + ": " + ex, ex);
                }
            }
        };
    }
}
