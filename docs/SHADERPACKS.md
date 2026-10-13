# Pyridium-Shaderpacks (Entwurf 0.1)

**Status: Laufzeit implementiert fuer die Stage `display` (ABI 1); die uebrigen Stages
sind Entwurf.** Umgesetzt und gemessen: Loader (Ordner/Zip), NVRTC-Bau mit Plattencache,
Linken mit den Zig-Kernels (`cuLink`), Optionen als Geraete-Global, Heissladen
(automatisch bei geaenderten Quellen, `ShaderPacks.reload()`), Fehler-Rueckfall
(Pack -> Standardpack -> Notfall-Pack), Standardpack und Beispielpack `flat-colours`.
Noch nicht portiert: Material/Shade/Sky/Water-Stages (die Beleuchtung steckt weiter in
`device/shade.zig`), Entrauscher-Parameter, Strahlbudget-API.
Dieses Dokument legt die Schnittstelle fest, damit die Standardoptik und Drittpacks
durch dieselbe ABI laufen. Lizenz des Repos: GPL-3.0-or-later.

## 1. Ziel und Abgrenzung

Ein Pack bestimmt das **Aussehen**:

- Material-/BRDF-Auswertung, Beleuchtungsmodell, Himmel/Atmosphäre, Wasser und Transparenz
- Licht-/GI-Abtaststrategie innerhalb eines Strahlbudgets
- Tonemapping, Farbkorrektur, Nachbearbeitungseffekte, Optionen und Uniforms

Ein Pack bestimmt **nicht**: LOD-System, HashDAG/BVH/OptiX-Traversierung, Welt-Streaming,
Entity-Upload, DLSS (SR/RR/FG), Speicherverwaltung. Das gehört Pyrit/Pyridium und wird
über die Schnittstelle unten bereitgestellt. Die Standardoptik ist selbst ein Pack
(`assets/pyridium/shaderpacks/default`) und nutzt keine privilegierte Abkürzung.

## 2. Format

Ordner oder `.zip` in `shaderpacks/` (neben `resourcepacks/`):

```
mypack/
  pack.json            Name, Version, Autor, Lizenz, ABI-Version, Optionen
  stages/*.cu          CUDA-C++-Quellen (zur Laufzeit mit NVRTC gebaut)
  stages/*.ptx         alternativ vorübersetzt (arch-spezifisch, optional)
  assets/              LUTs, Rauschtexturen (PNG/RAW), Sternkarte usw.
```

`pack.json`:

```json
{ "name": "Standard", "version": "1.0", "abi": 1, "license": "GPL-3.0-or-later",
  "stages": { "shade": "stages/shade.cu", "sky": "stages/sky.cu", "post": "stages/post.cu" },
  "options": [
    { "id": "exposure_bias", "type": "float", "min": -3, "max": 3, "default": 0, "label": "Belichtung (EV)" },
    { "id": "gi_bounces",    "type": "int",   "min": 0,  "max": 4, "default": 1, "label": "GI-Bounces" },
    { "id": "stars",         "type": "bool",  "default": true }
  ] }
```

Optionen werden vom Java-Teil im Grafikmenü gelistet und in
`config/pyridium-shaderpack-<name>.properties` gespeichert. Sie erscheinen im Pack als
Struct `PackOptions` (Konstantenspeicher, `uniform`-artig, ein Upload je Änderung).
Bool/Int-Optionen dürfen als `PYR_OPT_<ID>`-Makros zur Compile-Zeit eingebaut werden
(dann kompiliert die Variante ohne toten Code; Cache-Schlüssel enthält die Werte).

## 2a. Ist-Stand der Implementierung (ABI 1)

- Einstieg: `stages/pack.cu` (darf aus `stages/` per `#include` weitere Dateien laden).
  Java entpackt Zips nach `cache/pyridium/shaderpacks/unzipped/`; das Standardpack liegt in
  `assets/pyridium/shaderpacks/default` und wird bei jedem Start nach
  `cache/pyridium/shaderpacks/default` kopiert.
- Pyrit setzt `src/pack/pyrit_pack.cuh` vor das Pack (ABI-Header, gehoert dem Kern) und
  baut mit NVRTC (`--relocatable-device-code`, compute_75-PTX, der Treiber uebersetzt
  fuer die GPU). Das PTX liegt unter `cache/pyridium/shaderpacks/ptx/<hash>.ptx`
  (Schluessel: Header, Quelltext, NVRTC-Version; Includes werden nicht gehasht, bei
  Aenderung nur an Includes `pack.cu` anfassen).
- Die Zig-Kernels deklarieren die Stage als `extern fn`; `cuLinkAddData` verbindet beide PTX-
  Module zu einem Modul. Kein Funktionszeiger zur Laufzeit.
- Stage `display` (heute): `PYR_STAGE_DISPLAY { ... }` mit `r, g, b, out, mode`; Optionen
  `PYR_OPT(i)` (Index = Reihenfolge in `pack.json`).
- Native API: `pyd_pack_set(dir, fallback, cache)`, `pyd_pack_option(i, v)`,
  `pyd_pack_reload(rebuild)`, `pyd_pack_message(...)`; Java: `ShaderPacks`
  (`options()`, `setOption(id, v)`, `choose(name)`, `reload()`, `poll()`).
  Optionen werden in `config/pyridium-shaderpack.properties` gespeichert
  (`pack=`, `opt.<pack>.<id>=`). `-Dpyridium.shaderpack=<name|Pfad>` waehlt ein Pack.
- Stages `sky` und `sun` (ABI 1, **neu**): `PYR_STAGE_SKY` liefert die Himmelsstrahldichte je
  Richtung, `PYR_STAGE_SUN` die Farbe des direkten Sonnenlichts. Pyrit bakt sie auf der GPU
  (`pyr_k_bake_sky`, 1024x512, equirektangulaer wie `envRadiance`) in die Umgebungskarte
  samt Importance-Sampling-Verteilung; das Schattierungsmodul liest nur die Karte, die
  Stage kostet pro Frame nichts. Gebacken wird beim Start, bei Optionswechsel und beim
  Heissladen (`pyd_pack_reload`). Das Standardpack enthaelt eine physikalische
  Einfachstreuung (Rayleigh/Mie, keine Wolken, kein Dunst, Sterne bei tiefer Sonne).
- Fehlende Stages ergaenzt Pyrit aus Vorgaben des Headers (`PYR_DEFAULT_*_IMPL`), erkannt
  per Textsuche in `pack.cu` und seinen `#include`-Dateien (ein Pack mit nur `display`
  bleibt gueltig).
- Mitgelieferte Packs: `default` (Noble-inspiriert, immer frisch aus dem Jar nach dem Cache
  kopiert), `arc-realism` (filmische Kurve, physikalischer Himmel; Idee Arc-Shader, MIT) und
  `flat-colours` (Beispiel) im Ordner `shaderpacks/`.
- Befehle (Client): `/pyridium reload`, `/pyridium pack <name>`, `/pyridium options`,
  `/pyridium option <id> <wert>`. Ein Optionsmenue-Bildschirm ist **noch nicht** gebaut;
  die API (`ShaderPacks`) steht.
- Das Zig-Geraet kompiliert fuer die CPU-Tests mit dem eingebauten Tonemap; die GPU benutzt
  immer das Pack.

## 3. Stage-ABI (Device-Header `pyrit_pack.cuh`)

Alle Stages sind `__device__`-Funktionen mit festen Namen; NVRTC fügt sie zur Compile-Zeit
in die Pyrit-Kernels ein (Inlining, **keine** Funktionszeiger im Heißpfad). Nicht
definierte Stages nimmt der Header aus einer leeren Vorgabe.

| Stage | Signatur (vereinfacht) | Aufgabe |
| --- | --- | --- |
| `pack_material` | `void(const HitInfo&, MaterialIn&, SurfaceOut&)` | LabPBR/Texturen/Tönung zu `Surface` (Albedo, F0/Leiter, Rauheit, Emission, SSS, Porosität, Normale) |
| `pack_shade` | `Radiance(const HitInfo&, const Surface&, ShadeCtx&)` | Beleuchtung am Treffer; fordert Sekundärstrahlen über `ctx.trace(...)` an |
| `pack_sky` | `float3(float3 dir, SkyCtx&)` | Himmelsstrahldichte (ohne Wolken/Nebel in der Standardoptik) |
| `pack_sun_sky_lights` | `LightSample(Rng&, ...)` | optionale Lichtabtastung (Sonne, Himmel, Blocklicht) |
| `pack_water` | `WaterOut(const HitInfo&, ...)` | Wasseroberfläche (Wellennormale, Farbe, Tiefe) |
| `pack_denoise_params` | `DenoiseParams()` | Parameter des Pyrit-Entrauschers (siehe 5) |
| `pack_post` | `float3(float3 hdr, PostCtx&)` | Tonemap/Grading über HDR je Pixel |

`HitInfo`: Weltposition, geometrische Normale, Strahl (o, d), `t`, UV und Face, Instanz-/
Materialindex, Biomtönung (RGB), Voxelattribut, Footprint (Pixelgröße am Treffer),
Tiefe der Rekursion. Pyrit liefert die Materialtabelle (`Material` samt LabPBR-Rohkanälen)
und Texturzugriff (`pyr_sample(tex, uv, footprint)`), das Pack bewertet.

Strahlen: `ctx.trace(o, d, tmin, tmax, mask, flags)` und `ctx.occluded(...)` sind die
**einzige** Strahlanfrage. Sie laufen über die Replay-Wavefront (siehe 4); das Pack sieht
keine OptiX-Details.

## 4. Budget und Leistung (Pflichten der ABI)

- **Strahlbudget je Pixel** (Pyrit setzt, Pack liest `ctx.budget`): Primärstrahl 1,
  Sekundärstrahlen max. 6 im Bildpass, max. 3 je GI-Pixel (halbe Auflösung), Bounces max. 4.
  Überschreitung: weitere `trace`-Aufrufe liefern „kein Treffer".
- **Shade-once/Hit-Cache:** `pack_shade` muss deterministisch von (Pixel, Frame, Treffer)
  abhängen, nie von Wandzeit. Pyrit darf den Zustand zwischen Wavefront-Durchgängen
  cachen; die ABI erlaubt deshalb keine versteckten globalen Schreibzugriffe aus
  `pack_shade`. Zufallszahlen nur über `Rng` (geschichtete Dimensionen, siehe
  `Rng::initStratified`). Erweiterung gegenüber dem heutigen Stand: `ShadeCtx::scratch`
  (kleiner, pixelweiser Zwischenspeicher, 32 B), in dem `pack_shade` Zwischenwerte
  über Durchgänge behält (Voraussetzung für Shade-once).
- Register: `__launch_bounds__` setzt Pyrit; Ziel unter 168 Register für den
  Schattierungskernel. Packs, die das reißen, werden mit Warnung geladen, nicht abgelehnt.
- Kompilierte Kernels werden mit Schlüssel (Pack-Hash, Optionen, Architektur, NVRTC-Version)
  im Spielverzeichnis `run/cache/shaderpacks/` abgelegt. Kein /tmp.

## 5. Entrauscher und Nachbearbeitung

Das Pack liefert **Parameter**, nicht den Entrauscher selbst: Pyrit besitzt die
Reprojektion (Motion Vectors, Disocclusion), die varianzgeführte À-trous-Kette mit
Normalen-/Tiefen-/Material-Stops und die Albedo-Akkumulation. Parameter:
Historienlänge, Luminanz-Phi, Anzahl Iterationen, Firefly-Grenze. Das Pack bekommt die
entrauschte Beleuchtung und das akkumulierte Albedo in `pack_post` (HDR, linear).

**Keine Unschärfe (verbindlich für das Standardpack):** keine Tiefenschärfe, keine
Bewegungsunschärfe, kein Linsen-/Gauß-Weichzeichnen, kein Filmkorn, kein
Soft-Focus-Bloom. Bloom nur schwach und mit Schwelle. Der Entrauscher filtert nur das
Beleuchtungssignal; Albedo/Textur bleibt pixelscharf. Drittpacks dürfen Effekte
hinzufügen; das Standardpack nicht.

## 6. Fehler und Hot-Reload

- Compile-Fehler: NVRTC-Log in `logs/latest.log` und als Chat-/Menümeldung; das Pack wird
  verworfen, die Standardoptik bleibt aktiv. Laufzeitfehler (Launch-Failure) schalten auf
  das Standardpack zurück.
- Hot-Reload: `F3+R` oder `/pyridium reload`. Verlauf (History) wird dabei verworfen.

## 7. Java-Mods

- Parameter-API: `PyridiumShaders.setOption(id, value)` (überschreibt Pack-Optionen zur
  Laufzeit, z. B. Nässe/Regen, Tageszeit-Tönung, Biomwerte).
- Datengetriebene Tabellen bleiben im Kern: Materialtabelle, `Lighting` (Sonne, Himmelskarte,
  `wetness`), `PostFx`. Mods ändern sie ohne Zig-Eingriff.
- Optional: Mods registrieren zusätzliche PTX-Hook-Kernels (`registerHook(stage, ptx)`) an
  definierten Stellen (`sky`, `shade_modifier`, `post`); Aufruf nur, wenn registriert
  (kein Overhead sonst).

## 8. ABI-Erweiterungen gegenüber dem Ist-Stand (Backlog)

1. `ShadeCtx::scratch` für Shade-once (siehe 4).
2. Zugriff auf Pyrits akkumuliertes Albedo und die Varianz in `pack_post`.
3. Strahlbudget-Abfrage `ctx.budget`.
4. Weltweite Irradianz-Cache-Abfrage `ctx.irradiance_cache(p, n)` (sobald der Cache existiert).
5. Optionen als Compile-Zeit-Makros (siehe 2).

## 9. Herkunft

Die Standardoptik orientiert sich an den Techniken von Noble (BelmuTM, GPL-3.0-or-later);
Code wird nicht kopiert, nur eigene Umsetzung. Complementary-Shader wurden aus
Lizenzgründen (All Rights Reserved) nicht herangezogen. Noble-Assets (Texturen, LUTs)
dürfen mit Namensnennung übernommen werden.
