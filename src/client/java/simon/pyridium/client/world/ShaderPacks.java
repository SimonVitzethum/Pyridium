package simon.pyridium.client.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simon.pyridium.client.gpu.Native;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shaderpack-Verwaltung (docs/SHADERPACKS.md): findet Packs (Ordner oder Zip in
 * {@code shaderpacks/}), entpackt sie in den Cache, liest die Optionen aus
 * {@code pack.json}, speichert Werte in {@code config/pyridium-shaderpack.properties}
 * und meldet alles an Pyrit. Das Standardpack liegt im Mod-Jar und laeuft durch
 * denselben Weg wie jedes Drittpack. Geaenderte Quellen werden automatisch neu gebaut.
 */
public final class ShaderPacks {
	private static final Logger LOGGER = LoggerFactory.getLogger("Pyridium/ShaderPacks");
	private static final String[] BUNDLED = {"default", "arc-realism", "flat-colours"};
	private static final String[] FILES = {"pack.json", "stages/pack.cu", "stages/sky.cu", "stages/shade.cu", "stages/post.cu"};

	/** Eine Option aus pack.json (alle Werte als float; bool = 0/1). */
	public record Option(String id, String type, float min, float max, float def, String label) {
	}

	private static Path gameDir, packsDir, cacheRoot, configFile;
	private static final Properties PROPS = new Properties();
	private static String active = "default";
	private static Path activeDir, defaultDir;
	private static List<Option> options = List.of();
	private static long nextPoll;
	private static long lastStamp;

	private ShaderPacks() {
	}

	/** Vor Native.worldCreate aufrufen. */
	public static synchronized void init() {
		gameDir = FabricLoader.getInstance().getGameDir();
		packsDir = gameDir.resolve("shaderpacks");
		cacheRoot = gameDir.resolve("cache").resolve("pyridium").resolve("shaderpacks");
		configFile = FabricLoader.getInstance().getConfigDir().resolve("pyridium-shaderpack.properties");
		try {
			Files.createDirectories(packsDir);
			Files.createDirectories(cacheRoot.resolve("ptx"));
			// Standardpack immer frisch aus dem Jar; Beispielpacks nur, wenn sie fehlen
			defaultDir = cacheRoot.resolve("default");
			extractBundled("default", defaultDir, true);
			for (String n : BUNDLED) {
				if (!n.equals("default") && !Files.exists(packsDir.resolve(n))) {
					extractBundled(n, packsDir.resolve(n), false);
				}
			}
			if (Files.exists(configFile)) {
				try (Reader r = Files.newBufferedReader(configFile)) {
					PROPS.load(r);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Pyridium: Shaderpack-Verzeichnisse nicht vorbereitet", e);
		}
		select(System.getProperty("pyridium.shaderpack", PROPS.getProperty("pack", "default")));
	}

	private static void extractBundled(String name, Path target, boolean overwrite) throws IOException {
		for (String f : FILES) {
			try (InputStream in = ShaderPacks.class.getResourceAsStream("/assets/pyridium/shaderpacks/" + name + "/" + f)) {
				if (in == null) {
					continue;
				}
				Path out = target.resolve(f);
				Files.createDirectories(out.getParent());
				if (overwrite || !Files.exists(out)) {
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Pack waehlen (Ordnername oder Zip-Name ohne .zip) und an Pyrit melden. */
	public static synchronized void select(String name) {
		Path dir = resolve(name);
		if (dir == null) {
			LOGGER.warn("Pyridium: Shaderpack '{}' nicht gefunden, Standardpack aktiv", name);
			name = "default";
			dir = defaultDir;
		}
		active = name;
		activeDir = dir;
		options = readOptions(dir);
		Native.packSet(dir.toString(), defaultDir.toString(), cacheRoot.resolve("ptx").toString());
		pushOptions();
		lastStamp = stamp();
	}

	private static Path resolve(String name) {
		if (name.equals("default")) {
			return defaultDir;
		}
		Path d = packsDir.resolve(name);
		if (Files.isDirectory(d) && Files.exists(d.resolve("stages/pack.cu"))) {
			return d;
		}
		Path z = packsDir.resolve(name.endsWith(".zip") ? name : name + ".zip");
		if (Files.isRegularFile(z)) {
			try {
				return unzip(z, cacheRoot.resolve("unzipped").resolve(name.replace(".zip", "")));
			} catch (IOException e) {
				LOGGER.warn("Pyridium: Zip '{}' nicht lesbar", z, e);
			}
		}
		return null;
	}

	private static Path unzip(Path zip, Path target) throws IOException {
		if (Files.exists(target)) {
			try (var s = Files.walk(target)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		Files.createDirectories(target);
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				Path out = target.resolve(e.getName()).normalize();
				if (!out.startsWith(target)) {
					continue; // Zip-Slip
				}
				if (e.isDirectory()) {
					Files.createDirectories(out);
				} else {
					Files.createDirectories(out.getParent());
					Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		// einziger Oberordner (typisch bei Zips): dort liegt pack.json
		if (!Files.exists(target.resolve("pack.json"))) {
			try (var s = Files.list(target)) {
				for (Path c : (Iterable<Path>)s::iterator) {
					if (Files.exists(c.resolve("pack.json"))) {
						return c;
					}
				}
			}
		}
		return target;
	}

	private static List<Option> readOptions(Path dir) {
		List<Option> out = new ArrayList<>();
		try (Reader r = Files.newBufferedReader(dir.resolve("pack.json"))) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			JsonArray arr = root.has("options") ? root.getAsJsonArray("options") : new JsonArray();
			for (JsonElement el : arr) {
				JsonObject o = el.getAsJsonObject();
				String type = o.has("type") ? o.get("type").getAsString() : "float";
				float def = o.has("default") ? (o.get("default").getAsString().equals("true") ? 1f : o.get("default").getAsString().equals("false") ? 0f : o.get("default").getAsFloat()) : 0f;
				out.add(new Option(o.get("id").getAsString(), type,
					o.has("min") ? o.get("min").getAsFloat() : 0f, o.has("max") ? o.get("max").getAsFloat() : 1f, def,
					o.has("label") ? o.get("label").getAsString() : o.get("id").getAsString()));
			}
		} catch (Exception e) {
			LOGGER.warn("Pyridium: pack.json von '{}' nicht lesbar: {}", dir, e.toString());
		}
		return out;
	}

	private static void pushOptions() {
		for (int i = 0; i < options.size(); i++) {
			Option o = options.get(i);
			Native.packOption(i, value(o));
		}
	}

	private static float value(Option o) {
		String v = PROPS.getProperty("opt." + active + "." + o.id());
		if (v == null) {
			return o.def();
		}
		try {
			return Math.clamp(Float.parseFloat(v), o.min(), o.max());
		} catch (NumberFormatException e) {
			return o.def();
		}
	}

	// --- API fuer Optionsmenue und Mods ---

	public static synchronized List<Option> options() {
		return options;
	}

	public static synchronized String activePack() {
		return active;
	}

	public static synchronized float get(String id) {
		for (Option o : options) {
			if (o.id().equals(id)) {
				return value(o);
			}
		}
		throw new IllegalArgumentException(id);
	}

	/** Optionswert setzen: wird gespeichert und sofort an die GPU gegeben (kein Neubau). */
	public static synchronized void setOption(String id, float v) {
		for (int i = 0; i < options.size(); i++) {
			Option o = options.get(i);
			if (o.id().equals(id)) {
				PROPS.setProperty("opt." + active + "." + id, Float.toString(Math.clamp(v, o.min(), o.max())));
				Native.packOption(i, value(o));
				Native.packReload(false);
				save();
				return;
			}
		}
	}

	/** Anderes Pack waehlen (gespeichert) und neu bauen. */
	public static synchronized void choose(String name) {
		PROPS.setProperty("pack", name);
		save();
		select(name);
		reload();
	}

	/** Heissladen: Pack neu bauen; bei Fehler bleibt das Standardpack/Notfall aktiv. */
	public static synchronized void reload() {
		int rc = Native.packReload(true);
		String msg = Native.packMessage();
		if (rc != 0 || !msg.isEmpty()) {
			LOGGER.warn("Pyridium: Shaderpack '{}': {}", active, msg.isEmpty() ? "Neubau fehlgeschlagen" : msg);
		} else {
			LOGGER.info("Pyridium: Shaderpack '{}' neu gebaut", active);
		}
		lastStamp = stamp();
	}

	/** Pro Frame aufrufen: erkennt geaenderte Pack-Quellen (alle 2 s) und baut neu. */
	public static void poll() {
		long now = System.currentTimeMillis();
		if (now < nextPoll || activeDir == null) {
			return;
		}
		nextPoll = now + 2000;
		long s = stamp();
		if (s != lastStamp) {
			synchronized (ShaderPacks.class) {
				lastStamp = s;
				options = readOptions(activeDir);
				pushOptions();
				reload();
			}
		}
	}

	private static long stamp() {
		long t = 0;
		for (String f : FILES) {
			try {
				t += Files.getLastModifiedTime(activeDir.resolve(f)).toMillis();
			} catch (IOException ignored) {
			}
		}
		return t;
	}

	private static void save() {
		try (var w = Files.newBufferedWriter(configFile)) {
			PROPS.store(w, "Pyridium Shaderpack-Einstellungen");
		} catch (IOException e) {
			LOGGER.warn("Pyridium: Shaderpack-Einstellungen nicht gespeichert", e);
		}
	}
}
