# Pyridium: Stand und überprüfbare Grenzen

Der Weltpfad verwendet CUDA und OptiX, nicht OpenGL oder Vulkan-Rasterisierung.
Vulkan ist ausschließlich in Pyrits DLSS-FG-Interop vorgesehen. **DLSS FG ist
noch nicht mit der Pyridium-Ausgabe verbunden.** Das Produktionsziel bleibt
165 FPS bei 1024 Chunks Sichtweite; dieses Ergebnis ist noch nicht nachgewiesen.

## Blocktexturen

Pyridium benutzt die echten gebackenen Minecraft-Blocktexturen, nicht die
Demo-Texturen. Material16 erweitert die Materialtabelle von 256 auf 4096 Plätze.
Die Texturen bleiben RGBA8; die Biom-Tönung im Voxelattribut benutzt RGB565.
Legacy-Pyrit-Kontexte behalten Material8/RGB24. Wegen der erweiterten Scene-ABI
ist die native API-Version jetzt 0.3; alte native Aufrufer müssen neu bauen.

Im fensterlosen Integrationstest von Minecraft 26.3: **1196 Blockmaterialien,
30154 texturierte Zustände, 0 einfarbige Ersatzmaterialien**. Das ist keine
Behauptung vollständiger Blockmodelltreue: der Weltpfad stellt solide Zustände
weiterhin als Voxel dar und übernimmt Ober- und Seitentexturen, nicht sämtliche
modellabhängigen UVs, sechs individuellen Seiten, Blumen, Treppenformen oder
Texturanimationen. Größere Resource-Pack-Texturen werden bisher auf 16×16 abgetastet.

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
Die Übergabe an Pyrits separaten DAG-Stream wartet explizit auf die Voxelisierung.

**Nicht vollständig:** Namensschilder, Partikel, Feuer am Entity, Leinen,
Glint, zusätzliche Blockmodell-Submissions und Nicht-Quad-Spezialgeometrie
benötigen noch Übernahme. Custom-Geometrie über 16 Blöcke Kantenlänge,
8192 Quads oder 1.048.576 Samples wird mit einer Warnung nicht übernommen.
Alpha wird als Cutout abgetastet, nicht als vollständiges Vanilla-Blending;
Shader-Spezialeffekte und animierte Texturen sind noch nicht vollständig.
Änderungen an Custom-UVs oder -Eckfarben können einen neuen DAG-Bau auslösen.
Nicht übernommene Featuregruppen erscheinen im Testlog; ein leerer Zähler ist
kein Nachweis vollständiger Entity-Unterstützung.

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

- `./gradlew compileClientJava` und `zig build test` in `native/pyrit`.
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
- Für das gemeinsame Testbudget sind unabhängiges Prefetch und serverseitige
  GPU-Terrain-Jobs in diesen Renderer-Tests abgeschaltet. Fern-LOD bleibt CUDA.
  Solche Tests beweisen deshalb nicht die GPU-Worldgen des Serverpfads.

Die Drosselung begrenzt CUDA-Arbeits-/Pausenzeiten, **keine garantierte
Hardware-Partitionierung 60/40**. Der andere Java-Prozess wird nicht verändert.
Ohne die Test-Umgebungsvariable gibt es keine GPU-Drosselung im Produktionspfad.
Die Integrationstests erreichten während des Ladens ungefähr 16–35 tatsächliche
FPS mit 50-%-Testpausen bei 1280×720 (unterschiedliche Szenen und Mitbenutzung
der GPU). Das ist weder ein 165-FPS-Nachweis noch ein
Benchmark einer vollständig geladenen 1024-Chunk-Welt.

`pyridium.viewChunks` ist standardmäßig 1024, `pyridium.targetFps` 165. Minecrafts
eigener FPS-Deckel wird bei Bedarf auf mindestens dieses Ziel angehoben
(165 → 170 im 10er-Raster). `targetFps=0` deaktiviert diese Anpassung und die
adaptive Auflösung. Höhere bereits eingestellte Minecraft-Limits bleiben erhalten.
