# Pyridium: Stand und überprüfbare Grenzen

Der Weltpfad verwendet CUDA und OptiX, nicht OpenGL oder Vulkan-Rasterisierung.
Vulkan ist ausschließlich in Pyrits DLSS-FG-Interop vorgesehen. **DLSS FG ist
noch nicht mit der Pyridium-Ausgabe verbunden.** Das Produktionsziel bleibt
165 FPS bei 1024 Chunks Sichtweite; dieses Ergebnis ist noch nicht nachgewiesen.

Die Beleuchtung wird nicht zweimal als „RT plus PT“ gerendert: direkte
Beleuchtung, diffuse Lichtpfade und Reflexionen teilen den CUDA/OptiX-Tracer.
OptiX nutzt die RT-Cores für die Hardware-BVH; genaue HashDAG-Treffer und
Laub-Alpha-Prüfungen bleiben eigene GPU-Schnittprogramme. Die Welt verwendet
bereits 8³-Teilbaum-Boxen, die kleinste unterstützte DAG-Primitivgröße.
Dieser häufige Schnittpfad hat jetzt eine konstante Tiefe mit kleinem Stack.
Größere Geometrien verwenden weiterhin denselben generischen DAG-Walker.
Die Spezialisierung bleibt aus dem großen CUDA-Shading-/Medien-Austrittscode
heraus, weil zusätzliche Varianten dort Register-Spills erhöhen.
DLSS Ray Reconstruction rekonstruiert anschließend das verrauschte Ergebnis;
es ist kein zusätzlicher Strahlverfolger. Die Beleuchtung ist weiterhin eine
Kombination direkter Strahlen, separater GI und Reflexionen, kein vollständig
vereinheitlichter physikalischer Path Tracer.

## Renderer-Schalter

Grafikeinstellungen → **Pyridium · CUDA** bietet unabhängig gespeicherte
Schalter für **Ray Reconstruction**, **Path Tracing / indirektes Licht**,
**Raytracing-Effekte** und **RT-Cores (OptiX)**. Alle sind standardmäßig an;
der bisherige Spectator-Schalter bleibt erhalten. Die gemeinsame Datei ist
`config/pyridium-renderer.properties`; unbekannte Eigenschaften bleiben erhalten.

- RR aus: statt DLSS RR native CUDA-Nachbearbeitung. Explizite JVM-Modi
  `native`, `taau`, `dlaa` und `dlss` werden nicht durch den RR-Schalter ersetzt.
- Path Tracing aus: kein GI-/AO-Strahl und kein separater GI-Durchgang.
- Raytracing-Effekte aus: keine strahlbasierten direkten Schatten,
  Szenenreflexionen, Wasser-/Glasbrechung oder zusätzlichen Coverage-Strahlen.
  Wasser und Glas werden zu einfachen sichtbaren Oberflächen. Direkte
  PBR-Beleuchtung, Texturen und prozeduraler Himmel bleiben erhalten.
- RT-Cores aus: der Kontext wird ohne OptiX neu angelegt und benutzt den
  bestehenden CUDA-Software-Tracer. Dabei werden Welt-/Material-/Entity-Caches
  neu aufgebaut. Dieser Fallback prüft Szeneninstanzen linear, hat noch keine
  eigene CUDA-Szenen-BVH und ist bei großer Sichtweite erheblich langsamer.

Die primäre HashDAG-Sichtbarkeitsprüfung bleibt auch mit allen Schaltern aus
eine Strahlsuche; es gibt keinen heimlich zugeschalteten Rasterpfad.
Wer zusätzliche Lichtstrahlen abschalten und schneller rendern will, kann
die ersten drei Schalter ausschalten und die RT-Cores für Sichtbarkeit behalten.
Licht-/RR-Wechsel verwerfen temporale Bild- und GI-Verläufe. Die erstmalige
RR-Initialisierung ist auch nach einem Start ohne RR auf einem ausreichend
großen nativen Thread-Stack geschützt; native Fallbacks werden nicht jedes
Frame erneut durch die Java-Einstellung überschrieben.

Für isolierte Tests überschreiben `pyridium.rayReconstruction`,
`pyridium.rayEffects`, `pyridium.pathTracing`, `pyridium.hardwareRaytracing`
die gespeicherten Werte (`true`/`false`). `tools/check_renderer.sh` akzeptiert
entsprechend `PYRIDIUM_TEST_RR`, `PYRIDIUM_TEST_RAY_EFFECTS`,
`PYRIDIUM_TEST_PATH_TRACING`, `PYRIDIUM_TEST_HARDWARE_RT`.
`PYRIDIUM_TEST_TOGGLES=1` mit `PYRIDIUM_TEST_SECONDS=160` prüft laufende
Wechsel einschließlich Kontext-Neuaufbau und persistenter Einstellungen,
ausschließlich im wegwerfbaren Testverzeichnis.

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

Die Worldgen-Spezialisierungen werden in NVRTC-Gruppen von bis zu acht
Kernels übersetzt, nicht mehr als ein Modul je Spezialisierung. Identische
PTX-Module teilen auch zwischen LOD-Stufen ihren CUDA-Modulcode. Die
Pufferbindungen bleiben pro Kernel unabhängig und werden unmittelbar vor
dem Dispatch streamgeordnet aktiviert; unveränderte Bindungen werden nicht
erneut übertragen. Module werden referenzgezählt genau einmal freigegeben;
nur der letzte Besitzer wartet vor dem Entladen auf den CUDA-Stream.
Der PTX-Cache berücksichtigt Architektur, Mathematikmodus, NVRTC-Version,
Quelltext und alle eingebundenen Header. Renderer, Sektions- und Entity-Kernels
werden bei der Initialisierung aufgelöst und, wenn `cuFuncLoad` verfügbar ist,
explizit vorab geladen. Das vermeidet Lazy-Loading beim ersten Einsatz,
garantiert aber weder volle SM-Belegung noch 165 FPS.

Neue Entity-Modell-DAGs allokieren und entsorgen ihre temporären GPU-Puffer
streamgeordnet im vorhandenen CUDA-Speicherpool. Das vermeidet geräteweite
Allokations-/Freigabe-Wartepunkte neben dem Worldgen-Arbeiter. Upload,
Voxelisierung und DAG-Bau verwenden denselben Bildstream; der Builder wartet
vor der Rückgabe auf seine Ergebnisse. Auch frühe Fehler warten vor Rückgabe,
damit der Java-Flächenpuffer nicht während einer offenen Kopie freigegeben wird.

- `./gradlew compileClientJava`, `bash tools/check_block_faces.sh` und
  `zig build test` in `native/pyrit` (60 Tests einschließlich PBR, Alpha,
  Spectator-Strahlen, Speichergruppen, VRAM-Policy und Replay-Ausgabe).
- `bash tools/check_shader_batches.sh` prüft gemischte Punkt-/Volumen-Kernels
  in einem Modul, getrennte Bindungen, erneutes Binden und mehrfache Freigabe
  fensterlos direkt auf CUDA. Das ist kein vollständiger Worldgen-Nachweis.
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
  `PYRIDIUM_TEST_LIGHTING=path` protokolliert die normale GI-/Reflexions-
  Baseline; `PYRIDIUM_TEST_LIGHTING=direct` deaktiviert GI einschließlich
  Halbauflösungs-Durchgang, AO-Strahlen und Szenenreflexionen nur für den Test.
  PBR, direkte Raytracing-Schatten, Wasserbrechung, Himmel/Nebel und RR bleiben
  unverändert aktiv. Das ist **kein Raytracing-freier Rastermodus**. Ohne die
  Testvariable bleiben Spieler-Einstellungen und normales Licht unverändert.
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
`PYRIT_GPU_TIMING=1` sammelt die GPU-Ereignisse jetzt auch im Minecraft-
Ausgabepfad; `PYRIT_GPU_TIMING_PASSES=1` trennt die Replay-Durchgänge.
Die Full-HD-PBR-Baseline lag bei rund 48 ms GPU-Zeit je Frame, davon etwa
14 ms Bild-Shading, 14,5 ms Bild-RT, 9 ms GI und 8,2 ms DLSS.
Während des Weltaufbaus wurden 99 % GPU-Auslastung gemessen. Das beweist
keine dauerhaft volle oder besonders effiziente Belegung aller Einheiten.
Replay-Diagnosezähler werden nur mit `PYRIT_REPLAY_STATS` atomar aktualisiert.
Zwischenstände mit offenen Strahlen schreiben nicht mehr die vollständigen
G-Buffer; ein Pixel schreibt beim Abschluss oder im letzten budgetierten
Durchgang. GI beginnt weiterhin erst nach sämtlichen Bild-Durchgängen.
Ein Regressionstest prüft unveränderte fertige Ausgabe und letzte Annäherung.
Im erneuten Full-HD-PBR-Test blieb die gesamte GPU-Zeit bei ungefähr 48 ms;
ein erheblicher FPS-Gewinn durch diese Änderung ist also nicht nachgewiesen.
Die Versuche mit `PYRIT_REPLAY_NOSPEC=2` (rund 51 ms) und
`PYRIT_MAXREG=96` (rund 52 ms) waren langsamer und sind keine neuen Standards.
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

`pyridium.viewChunks` ist standardmäßig 1024. Beliebige Werte sind zulässig
(Planer-Seeds skalieren mit der Sichtweite, nicht quadratisch damit); praktisch
begrenzen nur Terrain-Generation (Durchsatz) und VRAM-Budget (Verfeinerung weicht
statt Speicher zu wachsen). `pyridium.targetFps` 165. Minecrafts
eigener FPS-Deckel wird bei Bedarf auf mindestens dieses Ziel angehoben
(165 → 170 im 10er-Raster). `targetFps=0` deaktiviert diese Anpassung und die
adaptive Auflösung und wählt ausdrücklich 100 % interne Renderauflösung; auch
nach einem Moduswechsel bleibt dies erhalten. Höhere bereits eingestellte
Minecraft-Limits bleiben erhalten.

Fensterloser Lichtvergleich vom 01.10.2026, RTX 5070 Laptop, Bare Bones PBR,
1920×1080 **auch intern**, 1024-Chunk-Radius, RR, `targetFps=0`, Duty 1:

| Licht | FPS-Mittel | Bereich | GPU-Zeit pro Bild |
| --- | ---: | ---: | ---: |
| Normales GI/Path Tracing + Szenenreflexionen | 17,6 | 16,9–18,2 | ca. 47,5 ms |
| `PYRIDIUM_TEST_LIGHTING=direct` | 18,9 | 18,2–19,8 | ca. 42,8 ms |

Mittel aus den letzten sechs 5-Sekunden-FPS-Fenstern je Lauf, nach dem
LOD-Aufbau (beide rund 34.690 sichtbar / 43.905 resident / 0 offen).
Ein Lauf je Variante, keine statistische Benchmark-Serie. Die Direktvariante
behält RT-Schatten, Wasserbrechung, PBR, Nebel und DLSS RR; sie ist kein
Raster- oder komplett raytracingfreier Modus. Ohne GI fällt der separate
GI-Durchgang weg, allerdings wandert die direkte Himmelslicht-Abtastung ins
Bild-Shading. Deshalb lassen sich die vorher gemessenen GI-Millisekunden
nicht einfach vom Frame abziehen: die Hochrechnung von etwa 22 FPS wurde
in diesem Vergleich **nicht** erreicht. Quellspielstand und Spieleroptionen
blieben unverändert, beide Integrationstests bestanden.

Protokolle und native HD-Aufnahmen dieses Vergleichs:
`/tmp/pyridium-render-check.vQ5QLm` (normal) und
`/tmp/pyridium-render-check.G9EBR5` (direkt).

### Gezielte RT-Optimierung vom 01.10.2026

Der konstante 8³-Schnittpfad ist nur im OptiX-Teilbaum-Tracer aktiviert.
CPU-Referenzen vergleichen ihn mit dem generischen Walker einschließlich
Palette, nicht-null Attributbasis, Material16-Überspringen, Innenstart,
nicht normierten Richtungen und begrenzter Strahllänge. Der bestehende
Laubtest prüft weiterhin echte Textur-Alpha-Masken auf beiden Wegen.
`zig build test kernel-check --release=fast --summary all` bestand mit
60/60 Tests; auch die GPU-Referenztests des Spezialisierungsversuchs bestanden.

Ein wiederholtes PBR-Surface-Caching innerhalb der Replay-Kacheln wurde
implementiert und getestet, aber **nicht übernommen**: der zusätzliche
Cache-/Shader-Aufwand lieferte keinen überzeugenden Gesamtgewinn. Ebenso
wurde die Variante verworfen, die den kleinen Walker zusätzlich in den großen
CUDA-Shading-Kernel einbettet; sie erhöhte Register-Spills. Der ausgelieferte
Pfad behält deshalb die bisherige Schattierung, Samples und RR unverändert.

Fensterlos, RTX 5070 Laptop, Bare Bones PBR, intern und Ausgabe 1920×1080,
1024-Chunk-Radius, GI/Reflexionen/RR aktiv, Duty 1 und `targetFps=0`:

| Messung nach LOD-Aufbau | Frische Baseline | Gezielter RT-Pfad |
| --- | ---: | ---: |
| Bild-RT | ca. 14,3 ms | ca. 13,3 ms |
| GI-RT | ca. 3,9 ms | ca. 3,6 ms |
| Gesamte GPU-Zeit | ca. 47,5 ms | ca. 46,6–47,0 ms |
| Letzte sechs FPS-Fenster | 17,5 FPS | 17,7 FPS |

Das sind Einzelvergleiche, keine statistische FPS-Serie: ein kleiner GPU-
Gewinn, **kein belastbarer großer FPS-Sprung und kein 165-FPS-Nachweis**.
Der Abschlusslauf meldete 34.691 sichtbare / 43.909 residente LOD-Einträge
und 0 offene Aufträge. Beide Minecraft-Integrationstests bestanden; native
HD-Aufnahmen wurden visuell geprüft. Quellspielstand und Optionen blieben
unverändert. Logs und PNGs: `/tmp/pyridium-render-check.wzQANe` (Baseline),
`/tmp/pyridium-render-check.4Hek7v` (gezielter RT-Pfad).

### Abschaltvergleich vom 01.10.2026

Fensterlos auf der RTX 5070 Laptop, Bare Bones PBR, intern und Ausgabe
1920×1080, 1024-Chunk-Radius, `targetFps=0`, Duty 1:

| Einstellung | Tatsächliche FPS | Einordnung |
| --- | ---: | --- |
| RR, GI/Path Tracing und Raytracing-Effekte aus; RT-Cores an | 29,6 Mittel, 28,7–30,1 | letzte sechs 5-s-Fenster, LOD fertig |
| zusätzlich RT-Cores aus | 1,2 Mittel, 1,1–1,3 | letzte sechs Fenster **während LOD-Aufbau**, nicht gleichwertiger Vollweltvergleich |

Der erste Lauf meldete 34.686 sichtbare / 43.904 residente LOD-Einträge und
0 offene Aufträge. Seine GPU-Zeit lag zuletzt bei ca. 16,3–16,6 ms, inklusive
direkter PBR-Schattierung, primärer RT-Sichtbarkeit und CUDA-Nachbearbeitung.
Der Kehrwert dieser GPU-Zeit (ca. 60 FPS) ist **nicht** die gemessene
Minecraft-Bildrate. Die tatsächlichen FPS beinhalten übrigen Frame-Aufwand.
Ein Einzelvergleich gegen die vorherige vollständige Beleuchtung/RR
(ca. 17–18 FPS) ist keine statistische Benchmark-Serie.

Der Software-Lauf endete bereits bei rund 8.945 sichtbaren / 12.102 residenten
Einträgen und 97.000 offenen Aufträgen. Seine Zahl darf nicht als FPS einer
fertig geladenen 1024-Chunk-Welt ausgegeben werden. Er zeigt, dass das
Entfernen der Hardware-Sichtbarkeitsbeschleunigung beim aktuellen linearen
Fallback keine Performance-Optimierung ist. Kein Lauf beweist 165 FPS oder
vollständige Vanilla-GPU-Worldgen. Beide isolierten Integrationstests bestanden;
native Full-HD-Bilder wurden auf sichtbare Welt, Minecraft-/PBR-Materialien,
Entities und die abgeschalteten Effekte geprüft.

Protokolle und Aufnahmen: `/tmp/pyridium-render-check.acT1Rq` (nur primäre
Hardware-Sichtbarkeit), `/tmp/pyridium-render-check.VnRijE` (CUDA-Fallback).
`zig build test --summary all`: 62/62 Tests bestanden, einschließlich
Lichtschalter-Kombinationen und genau eines Sichtstrahls je Pixel auch vor
transparentem Glas, mit Coverage- und Nebeleinstellungen. `zig build
kernel-check --release=fast --summary all` bestand ebenfalls.

Der zusätzliche Einstellungs-Lifecycle-Test bestand ebenfalls:
`/tmp/pyridium-render-check.qIcUK1`. Er startete ohne RR, öffnete die echten
Grafikeinstellungen fensterlos, speicherte alle Schalter in seiner isolierten
Konfiguration, wechselte Licht und RR sowie RT-Cores aus/an und renderte
anschließend wieder mit RR, GI und Hardware-Beschleunigung. Der Abschluss
meldete 34.686 sichtbar / 43.904 resident / 0 offen; die Full-HD-Aufnahme
zeigt wieder beleuchtete Entities und Szenenreflexionen. Quellspielstand,
Spieleroptionen und echte Renderer-Konfiguration wurden nicht geändert.
`./gradlew build -x compileCuda` lieferte die aktualisierte Mod-JAR erfolgreich.

## Profiling und Datenparameter (Optimierungsrunde 05.10.2026)

- `PYRIT_GPU_TIMING=1` (optional `PYRIT_GPU_TIMING_PASSES=1`, `PYRIT_REPLAY_STATS=1`)
  schreibt den 60-Frame-Mittelbericht jetzt auch als `PyritWorld`-Zeile in
  `logs/latest.log` (`pyd_timing_report`); stderr ging im Gradle-Lauf verloren.
  Wegen der Mitbenutzung der GPU die **kleinste** Gesamtzeit über mehrere
  Berichte vergleichen, nicht den letzten.
- Replay-Zustandsfelder werden nicht mehr je Streifen gelöscht, sondern mit einer
  Epoche gestempelt (`ReplayParams.epoch`): „Replay leeren“ 0,55 -> 0,08 ms.
- Dunst ist nur noch sehr schwach (`fog_density` 0,00002, Umgebungsvariable
  `PYRIT_FOG`), die Belichtungskorrektur liegt bei -0,2 EV (`PYRIT_EXPOSURE_COMP`);
  beides sind Datenparameter in `native/pyrit/demo/scene.zig`.
- Gemessen (RR aus, 1080p, Testszene, kleinste Gesamtzeit, GPU mitbenutzt):
  Basis ca. 12,2 ms, mit Epoche ca. 12-13 ms (Rauschen der geteilten GPU größer als
  der Gewinn); typischer Wert unter Last 28 ms. Verdichtung offener Pixel
  (Compaction der späten Replay-Durchgänge) brachte keinen Gewinn und wurde verworfen.

### Runde 2 (05.10.2026): Belichtung, Zufallsfolgen, Nativmodus, LabPBR-Teile

- **Belichtung:** die Automatik (`postfx.zig`) misst zusätzlich ein Histogramm der
  Oberflächenhelligkeit (ohne Himmel) und begrenzt die Belichtung so, dass das
  97.-Perzentil nicht über 1,1 liegt (Schnee/Sand brennen nicht mehr aus, helle
  Wolken dunkeln das Land nicht ab). Belichtungskorrektur Standard 0 EV
  (`PYRIT_EXPOSURE_COMP`), Dunst `PYRIT_FOG`.
- **Geschichtete Zufallsdimensionen:** die ersten 8 Ziehungen je Pixel (Sonnenkegel,
  Himmel, erster GI-Strahl) sind Kronecker-Folgen über die Frames mit pixelweiser
  Verschiebung (`Rng.initStratified`); `PYRIT_PLAIN_RNG=1` schaltet auf den alten Hash.
  Im statischen Test kein messbarer Unterschied (Restrauschen vom Entrauscher dominiert);
  kein Rückschritt.
- **Nativmodus (ohne DLSS-RR) ist jetzt Standard:** Jitter bleibt an, der zeitliche Verlauf
  des Entrauschers mittelt die Subpixel, kein TAAU-Durchgang. Sichtbar schärfer.
  `PYRIT_NATIVE_NOJITTER=1` schaltet den Jitter ab.
- **Wavefront:** 5 statt 8 Bild- und 4 statt 7 GI-Durchgänge (gemessen 1,1 ms gespart;
  `PYRIT_REPLAY_PASSES[_GI]` zum Vergleichen).
- **LabPBR 1.3:** die acht vordefinierten Leiter (Grün 230-237) nutzen F0 aus der n/k-Tabelle,
  255 die Albedo (`conductorF0`). Porosität/Nässe: `Lighting.wetness` (0..1, Standard 0,
  kostet dann nichts) dunkelt poröse Flächen ab und glättet. **Noch offen:** Height/POM
  (braucht den Blickstrahl in `surfaceAt`), Regenquelle für `wetness` aus Java.
- **Herkunft der Optik:** Die gewünschte Optik orientiert sich allgemein an Complementary
  Reimagined/Unbound (EminGT / Complementary Development). Deren Lizenz (Complementary
  License Agreement 1.7) behandelt alles außerhalb der Grants als „All Rights Reserved";
  Code oder Konstanten daraus wurden **nicht** übernommen (nur die Lizenzdatei gelesen).
  Belichtung, Kurven und Beleuchtung sind eigene, pfadverfolgte Umsetzungen.
- **Shaderpack-Laufzeit** (siehe docs/SHADERPACKS.md): die Anzeigestufe (Tonemap, Lebendigkeit)
  kommt aus dem Standardpack, per NVRTC gebaut und mit den Kernels gelinkt.
  Albedo-Verlauf ist jetzt Standard (`PYRIT_POST_NOALBEDO=1` schaltet ab): bei festem Bild
  sank das Flackern von 0,0123 auf 0,0009 (Burst-Metrik, gleiche Pose), die Gradient-Schaerfe
  von 0,0337 auf 0,0307 (Texelkanten werden ueber den Jitter geglaettet).
- **Credits:** Die Optik und der Entrauscher orientieren sich an Techniken des Shaderpacks
  Noble (BelmuTM, GPL-3.0-or-later, https://github.com/BelmuTM/Noble); Code wird nicht
  kopiert, Assets nur mit Namensnennung.
- **Credits Arc-Shader:** Null-MC/Arc-Shader (MIT-Lizenz, geprueft im Klon) dient als
  Ideenquelle fuer das Realismus-Pack `arc-realism` (filmische Kurve, physikalisch
  orientierter Himmel). Eigener CUDA-Code, keine Uebernahme von GLSL.
- Test-Hilfen: `-Dpyridium.camYawRate`, `-Dpyridium.camSpeed` (wandernde Testkamera),
  `PYRIDIUM_TEST_MODE`, `PYRIDIUM_TEST_SHOT_EVERY` in `tools/check_renderer.sh`.

### Runde 3: Caching bei vollem Path Tracing, CPU stallt die GPU nie

Grundsatz: Der Render-Steady-State enthält keinen einzigen CPU-GPU-Sync und
keinen Readback (nur Launches und streamgeordnete Kopien; Syncs nur bei
Auflösungswechsel, Up-/Teardown und Diagnose). Die CPU fragt nichts ab, die
GPU läuft nie leer. Verbleibende Syncs sind belegt: Upcall-Grenze
(`LodGenerator.run`, nativ liest danach), GAS-Kompaktierung (nur Anlegen),
Staging-Umlauf, explizite Diagnose (`PYRIT_REPLAY_STATS`, `reportHistory`).

- **GI-Cache (`PYRIT_GI_EVERY=N`, Standard 1):** GI-Durchgang nur jeden
  N-ten Frame neu; dazwischen gilt der zeitliche Verlauf weiter
  (`giShouldRun` in `native/pyrit/src/device/cache.zig`, Zähler `gi_tick` je
  Ansicht). Kein Strahl entfällt, nur Wiederverwendung; Geometrieänderung oder
  fehlender Verlauf rechnen immer. Kombinieren läuft unverändert.
- **Statischer Frame-Reuse (`PYRIT_STATIC_REUSE=1`, opt-in):** Kamera ohne
  Jitter, Auflösung, Render-Schalter, Szene (`scene_seq` aus dem Commit),
  Licht und GI-Modus unverändert und kein Jitter → alle Endpuffer werden per
  DtoD auf dem Stream kopiert (ca. 84 B/Pixel Cache), kein Kernel, kein
  Warten. Greift bei wirklich stehender Szene (Zeit eingefroren, keine
  Animation/Edits); bei Jitter oder Bewegung inaktiv. Feststehende
  Abtastung statt Konvergenz ist der dokumentierte Preis des Opt-ins.
- **Pipelined LOD-Statistik:** der Zähler-Download pro LOD-Batch wird nur
  noch eingereiht und erst zu Beginn des nächsten Auftrags ausgewertet
  (`pendingStats` in `LodGenerator.java`); der Upcall kehrt zurück, sobald die
  Voxelkerne eingereiht sind. Reine Heuristik (ST_EMPTY/ST_NODATA), ein Batch
  verzögert, Voxeldaten unberührt.
- Tests: `zig build test` (66 Tests, +3 für `parseGiEvery`/`giShouldRun`,
  Kamera-Reuse, Reprojektions-Match) und `./gradlew compileClientJava`.
- **Gemessen (Serie 2, 13.10.2026, RTX 5070 Laptop, 1920×1080 nativ,
  1024 Chunks, Nativmodus, Duty 1, je 300 s, Skript
  `/tmp/opencode/bench_series2.sh`):** Per-Pass-Sektionen aus
  `GPU-Zeit je Frame (60)`, je Lauf ~36k sichtbar / ~47k resident / 0 offen.
  GI-Sektion (`GI schattieren` + `GI RT`, Mittel): **9,2 ms (every) →
  3,0 ms (EVERY=2) → 2,0 ms (EVERY=3)**; Bild+RT unverändert (~25 ms).
  Der Skip greift exakt wie konstruiert (Dosis-Wirkung stimmt); der Rest des
  Frames läuft unverändert weiter. Summen sind durch LOD-Aufbau bis kurz vor
  Laufende verfälscht (Streaming-Sektionen) und werden daher nicht als
  Totale ausgewiesen.
- **FPS folgen der GPU-Ersparnis hier nicht:** ~8–9 FPS in allen Läufen
  (Frame ~111 ms bei ~40 ms GPU) — der Headless-Frame ist CPU-seitig gebunden
  (Present/Sync/Java-Overhead), nicht GPU-seitig. Für das 6-ms-Ziel muss die
  CPU-Seite mit (Phase 6 des Plans), sonst kommt keine GPU-Ersparnis an.
- **Statischer Reuse griff in keinem Lauf** (0 Treffer, auch entity-frei +
  `NOTIME` + `NOJITTER`): Lebendige Welten ändern pro Frame Posen/Instanzen
  (Welt-Entities auch ohne Test-Fixtures), die strikte Entwertung greift
  korrekterweise immer. Realer Wert in Live-Szenen ≈ 0; pausierte/echt
  eingefrorene Szenen bleiben ungeprüft. `staticbare` (entity-frei) belegt
  dabei: Bild+RT 17 ms statt 25 ms (Entity-DAG weg), ~20 FPS.
- **Qualität GI3:** Aufnahme Baseline vs. EVERY=3 (gleiche Szene/Kamera)
  mit dem Auge identisch — Schatten, Wasser, Himmel, Entities unverändert.
  Im Stand kostet GI3 nichts Sichtbares; Bewegung bleibt Einzelexperiment.
