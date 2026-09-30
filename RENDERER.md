# Pyridium: Stand und überprüfbare Grenzen

Der Weltpfad verwendet CUDA und OptiX, nicht OpenGL oder Vulkan-Rasterisierung.
Vulkan ist ausschließlich in Pyrits DLSS-FG-Interop vorgesehen. **DLSS FG ist
noch nicht mit der Pyridium-Ausgabe verbunden.** Das Produktionsziel bleibt
165 FPS bei 1024 Chunks Sichtweite; dieses Ergebnis ist noch nicht nachgewiesen.

## Blocktexturen

Pyridium benutzt die echten gebackenen Minecraft-Blocktexturen, nicht die
Demo-Texturen. Material16 erweitert die Materialtabelle auf 16384 Plätze.
Die Texturen bleiben RGBA8; die Biom-Tönung im Voxelattribut benutzt RGB565.
Legacy-Pyrit-Kontexte behalten Material8/RGB24. Wegen der erweiterten Scene-ABI
ist die native API-Version jetzt 0.4; alte native Aufrufer müssen neu bauen.

Im fensterlosen Bare-Bones-PBR-Test von Minecraft 26.3: **10840 Blockmaterialien,
30154 texturierte Zustände, 0 einfarbige Ersatzmaterialien**. Das ist keine
Behauptung vollständiger Blockmodelltreue: der Weltpfad stellt solide Zustände
weiterhin als Voxel dar; Treppenformen und Texturanimationen sind nicht vollständig.
Alle sechs Seiten werden aus den echten Modell-Quads mit deren UV-Rotation,
Zuschnitt, Overlays und separater Biom-Tönung gebacken. Standardlimit: 128×128,
mit `pyridium.maxTextureSize` bis 256×256. Laub benutzt die Textur-Alpha-Maske
statt zufälliger Löcher. Mips mischen keine benachbarten Flächen.

Aktive Resource-Packs liefern LabPBR-`_n`/`_s`-Karten: Normalen mit rotierten
Tangenten, AO, Smoothness/Rauheit, dielektrisches F0, metallische Reflexion und
Emission werden ausgewertet. Der Test fand 758 Normal- und 852 Materialkarten.
Height/POM und Porosity/Wetness sind noch nicht implementiert; vordefinierte
Metalle verwenden bisher Albedo-F0 statt materialspezifischer Leiter-N/K-Werte.
Gemeinsame Texturkacheln und Materialrezepte werden dedupliziert; GPU-Uploads
erfolgen bedarfsweise statt für jeden möglichen Blockzustand gleichzeitig.

## Dynamisches LOD

Der HashDAG-Planer wählt die Verfeinerung aus projizierter Voxelgröße,
Kameraposition, FoV, Ausgabeauflösung und Speicherbudget. Leer abgetastete grobe
Chunks verhindern keine feineren Nachfolger mehr. Sichtbare Verfeinerungsjobs
werden nach projiziertem Fehler priorisiert statt sämtliche Fernstufen zuerst
abzuarbeiten. Die dyadischen DAG-Stufen bleiben diskret; Fern-Worldgen-Geometrie
ist weiterhin approximiert und wird dadurch nicht zu Vanilla-Geometrie.
Auch leere fertige Batches stoßen Auswahl/Verfeinerung erneut an. Ersetzte
Chunks werden wieder sichtbar geschaltet; leer gewordene Ersatz-Chunks geben
ihre vorherigen Instanzen frei.

## Spectator

Grafikeinstellungen → Pyridium · CUDA → **Spectator-DAG vorhalten** (Standard:
aus). Die Einstellung wird atomar in `config/pyridium-renderer.properties`
gespeichert. Ein zusätzlicher Pyrit-World-Streamer baut geladene Minecraft-
Sektionen einschließlich Höhlenflächen in LOD0 innerhalb von 128 Blöcken.
Es gibt keinen separaten Raster-Renderer und keine zweite Materialpipeline.
Aus: derselbe Cache entsteht erst beim Wechsel in den Spectator-Modus und
wird beim Verlassen wieder freigegeben. An: er bleibt auch außerhalb des
Spectator-Modus vorgebaut und folgt der Kamera, solange Speicher verfügbar ist.

Budget: ein Achtel der bestehenden Welt-DAG-Quote, höchstens 128 MiB für
Knoten/Blätter/Attribute. Start nur ab 768 MiB freiem VRAM; unter 384 MiB
oder bei Überschreiten der DAG-Quote wird der optionale Cache freigegeben.
Generator-/BVH-Scratch benötigt zusätzlich Speicher. Die VRAM-Hysterese und
Wiederanlaufpause verhindern dauerhaftes Anlegen/Freigeben am Grenzwert.
Geteilte Batch-Speicherbereiche werden pro Welt genau einmal gezählt.

Sobald der Cache fertig ist, sehen Primärstrahlen innerhalb von 96 Blöcken
die geladenen Daten, danach weiterhin das adaptive Fern-LOD. Ein leerer
Höhlenstrahl fällt nicht auf approximierte Nahgeometrie zurück. Sekundärstrahlen
benutzen dort den geladenen Cache, damit keine fiktiven Höhlenschatten entstehen.
Entities nehmen an beiden Ansichten teil. Der Full-HD-PBR-Test hat Vorbau,
Einstellungsbildschirm, Moduswechsel und einen geschlossenen unterirdischen
Hohlraum überprüft; die Szene benötigte etwa 5–6 MiB zusätzliche DAG-Daten.

Grenzen: nur tatsächlich geladene Sektionen, keine zusätzlichen Vanilla-
Höhlen aus der approximierten Fern-Worldgen. Vollständige Vanilla-Noclip-Parität
mit der Kamera **im Inneren eines massiven Blocks** ist noch nicht nachgewiesen.
Während Neuaufbau/Teleports kann kurzzeitig die reguläre Weltansicht erscheinen.

## Entities

Vanilla extrahiert weiterhin die echten Modelle, Posen, Skins und Modell-Layer.
CUDA voxelisiert ModelPart-Cubes, gebackene Item-Quads und Quad-basierte
Custom-Geometry-Submissions mit ihren echten Texturen, UVs und Alpha;
Pyrit baut und rendert diese als wiederverwendbare HashDAG-Geometrien. Animation
wird durch stabile Instanzen mit wechselnden Posen und Motion-History übertragen.
Die Poseberechnung bleibt Java-seitig; der Grafikpfad ist CUDA/OptiX.
Geometrien ohne Nutzer werden nach Ablauf der Instanzen aus dem Cache entfernt.
Im Lebenszyklustest wurden die drei Testmobs entfernt: der Cache schrumpfte von
41 auf 17 Geometrien; anschließend konnte ein Schwein wieder dargestellt werden.

Die Sichtbarkeitsprüfung und Block-Entity-Erfassung hängen nicht mehr von
abgeschalteten Vanilla-Terrain-Meshes ab. Geprüft: Kuh, Schwein, Zombie und Truhe
mit Texturen und Schatten; 51 Modellinstanzen bei 27 stabil gecachten Geometrien.
Im erweiterten fensterlosen Test werden außerdem gedroppte Items (Diamant und
Grasblock), ein gehaltenes Diamantschwert, ein Diamanthelm und ein
Drachenfeuerball übernommen. Drei Item- und eine Custom-Instanz sind im Log
bestätigt; die Testaufnahme zeigt die Ausrüstung, den Grasblock und Feuerball.
Stabile Geometrie bleibt beim Drehen/Bewegen gecacht. Custom-Callbacks liefern
lokale Vertex-Metadaten, keine CPU-voxelisierten Ersatzmodelle.

Der gemeinsame 128-Byte-Flächendeskriptor unterstützt vier Eckpositionen,
UVs und ARGB-Farben. CUDA interpoliert entlang der Vanilla-Diagonale 0→2,
unterstützt untexturierte Quads und sucht Samplebereiche binär statt linear.
Pyrits Weltkontext teilt den CUDA-Stream der Voxelisierung; die Stream-Reihenfolge
sichert die Übergabe der fertigen Samples ohne zusätzliche Host-Wartezeit.

**Nicht vollständig:** Namensschilder, Partikel, Feuer am Entity, Leinen,
Glint, zusätzliche Blockmodell-Submissions und Nicht-Quad-Spezialgeometrie
benötigen noch Übernahme. Custom-Geometrie über 16 Blöcke Kantenlänge,
8192 Quads oder 1.048.576 Samples wird mit einer Warnung nicht übernommen.
Alpha wird als Cutout abgetastet, nicht als vollständiges Vanilla-Blending;
Shader-Spezialeffekte und animierte Texturen sind noch nicht vollständig.
Änderungen an Custom-UVs oder -Eckfarben können einen neuen DAG-Bau auslösen.
Nicht übernommene Featuregruppen erscheinen im Testlog; ein leerer Zähler ist
kein Nachweis vollständiger Entity-Unterstützung.
Vanillas separater First-Person-Hand-/Item-Pass ist ebenfalls noch nicht in
diesen Welt-Collector integriert; gehaltene Items von Welt-Entities sind davon
zu unterscheiden.

## Worldgen

Terrain-Dichte, Biome, Aquifere und Oberflächen besitzen CUDA-Pipelines. Die
CPU koordiniert und schreibt Ergebnisse in Minecraft-Chunks. Zusätzlich laufen
**Carver, Strukturplatzierung und Features weiterhin auf der CPU**; unbekannte
Konfigurationen und Blending können Vanilla-Fallbacks auslösen. Fern-LOD nutzt
die GPU-Dichte und Biome, hat aber approximierte Oberflächen/Bäume und keine
vollständigen Vanilla-Strukturen/Features. Fehlende Minecraft-Daten werden
nicht mehr durch eine andere Demo-Welt ersetzt.

Eine vollständige GPU-Worldgen ist damit ausdrücklich **noch nicht umgesetzt**.
Die verbleibenden Algorithmen müssen mit ihrer Seed-/Zufallsreihenfolge und
chunkübergreifenden Abhängigkeiten portiert und gegen Vanilla verglichen werden;
GPU-Terrain allein beweist das nicht.

## Tests und Leistung

- `./gradlew compileClientJava`, `bash tools/check_block_faces.sh` und
  `zig build test` in `native/pyrit` (58 Tests einschließlich PBR, Alpha,
  Spectator-Strahlen, Speichergruppen und VRAM-Policy).
- `python3 tools/check_model_voxels.py <pyridium_kernels.ptx>` prüft CUDA-UVs,
  Alpha, Tönung, Voxelkoordinaten, verzerrte Quads, Eckfarben, untexturierte
  Geometrie und 257 Flächenbereiche gegen eine unabhängige CPU-Referenz
  (sechs Fälle).
- `python3 tools/check_lod_voxels.py build/cuda/wg_lod_voxels.ptx` prüft
  Terrain-Oberflächen und Überlaufzählung.
- Integrationstests benutzen SDLs Dummy-Treiber, `pyridium.headless=true`, einen
  **kopierten Test-Spielstand** und `PYRIDIUM_TEST_GPU_DUTY=0.5`. `testEntities`
  ist ein ausdrücklich aktivierter Testschalter und verändert die Testwelt.
  Niemals mit einem wichtigen Spielstand verwenden.
- `pyridium.testEntityFeatures=true` erweitert die explizite Test-Entity-Fixture
  um Item- und Spezialgeometrie. Auch dieser Schalter verändert nur die dafür
  bereitgestellte Testwelt; nicht für einen wichtigen Spielstand benutzen.
- `bash tools/check_renderer.sh 'run/saves/Pyridium Test'` kopiert den angegebenen
  Spielstand in ein neues temporäres Verzeichnis und prüft Items/Custom-Quads
  mit **1920×1080 Ausgabe und interner Auflösung**, RR und ohne sichtbares Fenster.
  Die PNGs bleiben dort erhalten. `targetFps=0` verhindert Auflösungsreduktion;
  Aufnahmegröße und Entity-Submissions werden im Log geprüft.
  Der Quellspielstand soll geschlossen sein, damit die Kopie konsistent ist.
  `PYRIDIUM_TEST_PACK='/home/simon/Downloads/Bare Bones PBR.zip'` prüft zusätzlich
  den Pack in einer temporären Kopie, ohne Spielereinstellungen zu verändern.
  `PYRIDIUM_TEST_SPECTATOR=1` prüft zusätzlich Vorbau und Spectator-Höhlenkamera;
  `PYRIDIUM_TEST_SPECTATOR_PREBUILD=0` prüft stattdessen Aufbau erst bei Bedarf.
  Beide Varianten verlassen anschließend Spectator und deaktivieren den
  Test-Vorbauschalter, um die Freigabe zu prüfen. Der Aufnahmezeitraum beginnt
  erst mit dem ersten Weltframe, nicht während der Materialvorbereitung.
- Für das gemeinsame Testbudget sind unabhängiges Prefetch und serverseitige
  GPU-Terrain-Jobs in diesen Renderer-Tests abgeschaltet. Fern-LOD bleibt CUDA.
  Solche Tests beweisen deshalb nicht die GPU-Worldgen des Serverpfads.

Seit der Freigabe vom 30.09.2026 dürfen auch Tests **100 % GPU** nutzen; der
Full-HD-Testhelfer verwendet deshalb Duty 1. Für gedrosselte Tests kann weiterhin
`PYRIDIUM_TEST_GPU_DUTY=0.5` gesetzt werden.
Die Drosselung begrenzt CUDA-Arbeits-/Pausenzeiten, **keine garantierte
Hardware-Partitionierung 60/40**. Der andere Java-Prozess wird nicht verändert.
Ohne die Test-Umgebungsvariable gibt es keine GPU-Drosselung im Produktionspfad.
Die Integrationstests erreichten während des Ladens ungefähr 16–35 tatsächliche
FPS mit 50-%-Testpausen bei 1280×720 (unterschiedliche Szenen und Mitbenutzung
der GPU). Das ist weder ein 165-FPS-Nachweis noch ein
Benchmark einer vollständig geladenen 1024-Chunk-Welt.

Der Full-HD-PBR-Lauf (RR, `targetFps=0`, Duty 1, 1920×1080,
1024-Chunk-Sichtradius) erreichte nach dem Laden ungefähr **17–19 tatsächliche
FPS**. Der Scheduler meldete 34.691 sichtbare LOD-Chunks, 43.909 residente
Einträge und 0 offene Aufträge; ein anfänglicher Voxelüberlauf wurde durch
Kapazitätsvergrößerung aufgelöst. Das beweist die Übernahme
der geplanten LOD-Einträge in dieser festen Kamera, nicht vollständige
Vanilla-GPU-Worldgen oder das 165-FPS-Ziel. Die native Full-HD-Aufnahme zeigt
Mobs, Ausrüstung, Items, Feuerball, Blocktexturen und Schatten.

`pyridium.viewChunks` ist standardmäßig 1024, `pyridium.targetFps` 165. Minecrafts
eigener FPS-Deckel wird bei Bedarf auf mindestens dieses Ziel angehoben
(165 → 170 im 10er-Raster). `targetFps=0` deaktiviert diese Anpassung und die
adaptive Auflösung und wählt ausdrücklich 100 % interne Renderauflösung; auch
nach einem Moduswechsel bleibt dies erhalten. Höhere bereits eingestellte
Minecraft-Limits bleiben erhalten.
