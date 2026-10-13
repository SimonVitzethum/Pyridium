# Strukturen auf der GPU (CUDA)

Alle Vanilla-Strukturen (Platzierung, Auswahl, Teile) laufen als CUDA-Kernel; zur Laufzeit gibt es keinen Vanilla-
`StructureStart`/`StructurePiece`/`ChunkGenerator`-Code. Zustandslos: Ergebnis = f(Seed, Chunk, Registry-Tabellen, Umgebung).
Die Registry (Vanilla + Datapacks) wird beim Laden **einmal** zu flachen Tabellen kompiliert; Vorlagen-NBT werden zu
Voxel-/Palettentabellen gebacken (Vorbereitung, keine Generierung).

## Aufbau

```
Registry (JSON + NBT, Datapacks)             StructureCompiler / TemplateBaker / ProcessorCompiler   (Java, einmalig beim Laden)
        |  flache int-Tabellen  (StructureTables)
        v
 wg_struct_place  ->  Kandidaten (Set, Chunk)          random_spread / concentric_rings / Frequenz / Exclusion Zones
        |  (Umgebungs-Kacheln: Hoehen + Biome, Anbieter-Vertrag s. u.)
        v
 wg_struct_gen    ->  Starts + Teile (Arena)           Auswahl nach Gewicht, Biom-/Hoehenpruefung, Jigsaw-Loeser, prozedurale Typen
        v
 wg_struct_index  ->  Raumindex (Hash, 64x64 Bloecke)
        v
 struct_columnBegin / struct_columnAt  (wg_struct_query.cuh)   <- Haken fuer LOD-/Nahwelt-Voxelkernel: Block je Voxel
```

Dateien (neu, alles unter `src/main`):

| Datei | Inhalt |
|---|---|
| `java/.../worldgen/structures/StructureCompiler.java` | Registry -> Tabellen (Sets, Strukturen, Pools, Elemente, Aliase, Biom-Masken, Umgebungs-Groessen) |
| `.../TemplateBaker.java`, `ScatteredPieces.java`, `FortressPieces.java`, `VirtualPiece.java` | NBT -> Voxel/Palette/Jigsaws/Mips; Bauanweisungs-Strukturen (Wueste, Dschungel, Sumpf, Nether-Festung) -> Vorlagen |
| `.../ProcessorCompiler.java` | processor_list -> GPU-Programm (rule, block_rot, ...) |
| `.../ProceduralTypes.java` | nicht-Jigsaw-Typen: Vorlagenlisten und Parameter |
| `.../StructureGpu.java` | Upload der Tabellen, Kernel-Aufrufe, Arena/Index, Abfrage-Sicht, Anbieter-Schnittstelle |
| `cuda/wg_struct_tables.cuh` | Tabellen-Layout (`StructTables`), Konstanten, Legacy-Zufall (`LRand`); eigenstaendig einbindbar |
| `cuda/wg_struct_common.cuh`, `_place.cuh`, `_jigsaw.cuh`, `_alias.cuh`, `_gen.cuh` | Platzierung, Jigsaw-Loeser, Aliase, Kernel |
| `cuda/wg_struct_proc.cuh`, `_procdispatch.cuh`, `_endcity.cuh`, `_fortress.cuh`, `_mansion.cuh` | nicht-Jigsaw-Typen |
| `cuda/wg_struct_query.cuh` | **Abfrage-API** (eigenstaendig) |
| `cuda/wg_struct_env_tile.cuh`, `wg_struct_tile.cu` | Produktions-Umgebung (Kacheln), Modul `wg_struct_tile` |
| `cuda/wg_struct_synth.cu`, `_tilesynth.cu`, `_synth_stats.cu` | Test-Module (analytische Umgebung / Kachelpfad / Zaehler) |
| `java/.../structures/tools/*` | Testwerkzeug (nur Entwicklung) gegen Vanilla: `StructureHarness`, `VanillaBoot`, `VanillaEnvProvider`, `Synth` |
| `tools/check_structures.sh`, `tools/port_scattered.py`, `tools/port_fortress.py` | Regressionstest; Entwicklungswerkzeuge, die Bauanweisungen uebertragen |

## Test (`./gradlew structHarness -Pmode=<m> ...`, `tools/check_structures.sh`)

Das Werkzeug bootet Minecrafts Registries (nur Test!), baut eine analytische Umgebung (Hoehe/Biom als reine Integer-Funktionen,
bitgleich in Java und CUDA; mit `-Preal=true` echtes Vanilla-Gelaende) und vergleicht die GPU mit Vanillas
`StructurePlacement`, `ChunkGenerator.createStructures` und `Structure.generate` (Struktur, Position, Rotation, Spiegelung,
Vorlage, Bounding Box jedes Teils).

| Modus | Prueft | Ergebnis |
|---|---|---|
| `place` | Kandidaten aller 18 Overworld-Sets in 800x800 Chunks | **identisch** (inkl. Exclusion Zone, legacy-Frequenzen, Strongholds) |
| `rings` | GPU-Ringloeser gegen `generateRingPositions` (128 Positionen, 3 Seeds) | **identisch** |
| `gen` | Starts + Teile aller unterstuetzten Strukturen (`-Pdim=overworld|nether|end`) | **identisch** (siehe Checkliste, mehrere Tausend Starts) |
| `tile` | Kachelpfad (Anbieter-Kacheln) gegen direkte Analytik-Umgebung | **identisch** |
| `real` | echtes Vanilla-Gelaende (Hoehen/Biome von `NoiseBasedChunkGenerator`) -> Kacheln -> GPU-Dorf | **identisch** zu Vanilla (99 Teile, y-genau) |
| `view` | Draufsicht/Schnitt LOD0..3 nach `build/scratch/*.png` | s. u. |
| `bench` / `vstat` | Durchsatz / Teile-Statistik | s. u. |

## Checkliste (Stand: siehe unten, `done` = Platzierung + Start + alle Teile identisch zu Vanilla im Test)

| Struktur (Set) | Status | Hinweis |
|---|---|---|
| village_plains/desert/savanna/snowy/taiga (villages) | done | Jigsaw; Prozessoren: Strassen-Zufall (10 % Gras u. a.); Welt-Praedikate der Strassenregeln (Wasser) uebersprungen |
| pillager_outpost | done | Jigsaw, legacy_type_1-Frequenz, Exclusion Zone Dorf |
| abandoned_camp x18 (abandoned_camp) | done | Jigsaw |
| ancient_city | done | Jigsaw; block_rot/rule-Prozessoren; protected_blocks/Welt-Praedikate nicht |
| trial_chambers | done | Jigsaw mit Pool-Aliasen (random/random_group) |
| trail_ruins | done | Jigsaw |
| bastion_remnant (nether_complexes) | done | Jigsaw |
| igloo | done | Vorlagen; Hoehe am Eingang aus der Umgebung |
| shipwreck, shipwreck_beached | done | Vorlagen; Hoehe aus der Umgebung (Zufall des Platzierens: Hash) |
| ruined_portal x7 | done | Vorlage + Setup/Y-Suche; Eigenschaften (Mooswahrscheinlichkeit, Kaelte, ...) im Teil, Prozessoren/Netherrack-Streuung noch nicht angewandt |
| ocean_ruin_cold/warm | done | Vorlagen inkl. Cluster; Block-Faeule (`BlockRotProcessor`) noch nicht angewandt |
| nether_fossil | done | Vorlage |
| buried_treasure | done | nur Marker (eine Truhe ohne Block im Voxelabruf) |
| desert_pyramid, jungle_pyramid, swamp_hut | done | aus den Bauanweisungen zu Vorlagen gebacken (feste Stichprobe der Zufallsbloecke); Position/Ausrichtung exakt |
| end_city | done | rekursiver Aufbau (Zustandsautomat), Kollisionslogik exakt |
| fortress (nether_complexes) | done | Layout exakt; Teile aus Bauanweisungen gebacken (Bruecken-Endstuecke: eine Stichprobe) |
| mansion (woodland_mansions) | done | Layout und Raeume exakt (Vorlagen) |
| stronghold | todo | Ringpositionen + Platzierung done (GPU-Ringloeser identisch); Teile todo |
| mineshaft, mineshaft_mesa | todo | Platzierung + Frequenz done; Teile todo |
| monument | todo | Platzierung done; Teile todo |

## Umgebung (Anbieter-Vertrag)

Der Jigsaw-Loeser fragt Gelaendehoehen an vom Verlauf abhaengigen Stellen (Strassen/terrain_matching, Hoehenprojektion), das
Biom an der Startposition und die Biomsaeule der Chunkmitte. Statt diese Funktionen aus der (je Welt per NVRTC erzeugten) Dichte
aufzurufen, legt `wg_struct_place` je Kandidat eine **Kachel** an (Geometrie im Kopf), ein **Anbieter** befuellt sie auf der GPU
(`StructureGpu.EnvProvider`, laeuft im selben Stream zwischen place und gen), Format: `wg_struct_env_tile.cuh`:

* Oberflaechenhoehe (WORLD_SURFACE_WG, erster freier y, int16) auf `[cx*16-R, cx*16+16+R)^2` mit R je Set (Jigsaw: maxDist+24(+12), sonst 48)
* Meeresbodenhoehe (OCEAN_FLOOR_WG) nur fuer Sets, die sie brauchen
* Biomvolumen 12x12 Quarts (3x3 Chunks um den Start) x alle y-Quarts als Biom-Index (Index = Position in `biomeUniverse`, die der
  GPU-Biomsuche entspricht)
* Ring-Ebenen (Strongholds): 57x57 Biome bei y=0 um jede Ausgangsposition (nach `wg_struct_rings_init`)

Referenzimplementierung: `SynthEnvProvider` + `wg_struct_envfill_synth` (in `wg_struct_tilesynth.cu`); mit echten Daten:
`tools/VanillaEnvProvider` (Test).

## Integrationshaken (vom Lead einzubauen; die gesperrten Dateien wurden nicht angefasst)

Java (Pipeline-Init, einmal je Welt): `StructureCompiler` mit Biom-Indizes in `ExactBiomes`-Reihenfolge ausfuehren, dann
`new StructureGpu(gpu, tables, source, "wg_struct_tile", capCand, capStart, capPieces, slots, hashCap, envInts)`,
`sg.setAttrTable(BlockPalette.ATTR)`, `sg.solveRingsAsync(provider)` (Strongholds), je Region
`sg.generateAsync(cx0, cz0, cw, cd, provider)`. `provider` (EnvProvider) fuellt die Kacheln aus der Gelaende-/Biomdichte.
`sg.viewBuffer()` als zusaetzliche Bindung an den Voxelkernel uebergeben (z. B. `vf_b[N]`).

CUDA in `wg_lod_voxels.cu`:
```
#define STRUCT_EXTERNAL_VFB
#include "wg_struct_query.cuh"
...  // je Spalte
StructCol sc; struct_columnBegin(*(const StructTables*)vf_b[N], sc, wx, wz, step, groundY);
...  // je Voxel
unsigned r = struct_columnAt(*(const StructTables*)vf_b[N], sc, wy);   // STRUCT_PRESENT | stateId
// STRUCT_PRESENT gesetzt: state = r & ~STRUCT_PRESENT; state 0 = Luft (carve), sonst Block via struct_attr(...); als solide fuer Exposure
```
Grobes LOD: `step` = 2^k waehlt automatisch das Mip-Level (dyadisches Downsampling). Dynamisch, keine feste LOD-Anzahl.
(Exakte Signaturen: `wg_struct_query.cuh`.)

## Leistung (RTX 5070 Laptop, geteilt)
ca. 1500 Doerfer/s, ca. 190k Teile/s bei 4096 Slots (`-Pmode=bench`); Scratch ca. 170 KB/Slot; Stack 208-416 B (Rekursion entfernt).

## Fehlend / Naeherungen
stronghold, mineshaft x2, monument: nur Platzierung. Prozessoren: Welt-Praedikate, protected_blocks, block_age, blackstone_replace
nicht; Ozean-Ruinen/Wracks nutzen Umgebungs-Naeherung; Bauanweisungs-Strukturen als feste Stichprobe der Zufallsbloecke.


## Aktueller Stand der Integration (LOD-Schicht, Vollchunk-Haken, Datenformate)

### Wo Strukturen erscheinen
* **LOD (Fernbereich)**: eigene Pyrit-Geometrieschicht (`LodStructures` + `LodStructureLayer`, Client). Das Terrain wird nie angefasst. Ein Arbeits-Thread
  mit eigenem Stream (mehrere "Bahnen", `-Dpyridium.structLanes`) erzeugt 2048-Block-Bloecke rund um die Kamera (nah zuerst), laedt nur die Teileliste
  und ein Hoehenraster (Abstand 8, gleiche vorlaeufige Oberflaeche wie das Terrain) und baut je Vorlage (und je dyadischer Mip-Ebene) einmal geteilte
  HashDAG-Geometrie mit Pyrit-Attributen (`TemplateGeometry.bakeVoxels`, `pyd_model_create_voxels`). Je Teil eine Instanz; Mip nach Abstand/Bildschirmgroesse.
  Fundamente: Quader unter dem Teil bis zum Boden der LOD-Hoehenfunktion; Teile ueber tiefem Wasser/Abgruenden (Fundament > 12 Bloecke) entfallen.
* **Nahwelt (Server)**: Stufe `wg_full_struct.cu` im Vollchunk-Generator (`FullStructures`): echte Blockzustaende aus derselben GPU-Arena, Fundament bis zum Boden.
* Der alte Weg (Abfrage im Voxelkern, `wg_struct_adapt.cuh`) bleibt hinter `-Dpyridium.lodStructuresInKernel=true` (kostet Durchsatz).

### Loeser: ein Warp je Struktur
`wg_struct_gen` startet je Scratch-Slot **einen Warp**. Alle 32 Lanes fuehren den Jigsaw-Loeser identisch aus (gleicher Zufall, gleiche Daten, damit Ergebnis und
Reihenfolge exakt wie vorher), nur der teure Boxtest `jg_freeFits` ist ueber die Lanes verteilt (je Lane ein Knoten der Freiraum-Liste, `__any_sync`);
Ausgabe-Atomics macht Lane 0. Ergebnis: Starts/Teile weiterhin identisch zu Vanilla (`structHarness -Pmode=gen`: 2146/2146), Erzeugung eines
2048x2048-Blocks (485 Kandidaten, 56 Starts, 3868 Teile) in ca. 260 ms statt 1,7-2,7 s (synthetische Umgebung, 256 Slots); ein Dorf (135 Teile) ca. 130 ms, eine
Trial Chamber (250 Teile) ca. 0,5 s. Restkosten: je Versuch (Element x Rotation) Jigsaw-Listen mischen (Zufallsfolge muss erhalten bleiben).

### Datenformate (alles aus Registry/Datapack, keine Struktur im Code)
Gelesen wird ausschliesslich ueber `StructureData` (Ressourcen-Manager des Servers, inklusive Datapacks und Mod-Ressourcen):
* `worldgen/structure`, `worldgen/structure_set`, `worldgen/template_pool`, `worldgen/processor_list`, `structure/*.nbt`, Biom-Tags (`tags/worldgen/biome/**`) wie in Vanilla.
  Jede Struktur vom Typ `minecraft:jigsaw` (oder einem auf `jigsaw` abgebildeten Typ) funktioniert ohne Codeaenderung: Pools, Elemente (single/list/legacy/feature),
  Prozessorlisten (`rule`, `block_rot`, `protected_blocks` teilweise), Pool-Aliase, Hoehenanker, `project_start_to_heightmap`, `terrain_adaptation`, `max_distance_from_center`.
* **Strukturtyp-Register** `data/<ns>/structure_type/<name>.json`: `{"type": "<Typ-Id>", "generator": "<Erzeuger>"}`. Der Compiler bildet den deklarierten Typ der
  Struktur auf den Erzeuger ab (Standard: identisch, mitgeliefert in `src/main/resources/data/pyridium/structure_type/` fuer alle Vanilla-Typen). Ein Mod, der einen
  eigenen Strukturtyp registriert, kann ihn so auf `minecraft:jigsaw` (oder einen anderen vorhandenen Erzeuger) abbilden. Unbekannte Typen werden
  geloggt (`Typ ... noch nicht auf der GPU`) und uebersprungen, nie ein Absturz.
* Biome: nie ueber Namen, immer ueber die Biom-Tags/-Listen der Strukturen (Maske je Struktur, `ExactBiomes`-Reihenfolge).
* **Entwicklung**: `-Dpyridium.extraData=<Ordner>` legt einen Datapack-Ordner (mit `data/`) ueber die Server-Daten (`StructureData.overlay`).

### Datapack-Test (`tools/testpack`)
`python3 tools/testpack/make_testpack.py` schreibt das Pack `pyridiumtest`: ein eigenes Jigsaw-Bauwerk (Huette + Turm, zwei NBT-Vorlagen mit Jigsaw-Bloecken,
zwei Pools, ein `structure`, ein `structure_set` mit `random_spread`) und einen eigenen Biom-Tag `pyridiumtest:has_test_outpost`. Lauf:
`JAVA_TOOL_OPTIONS="-Dpyridium.extraData=$PWD/tools/testpack" tools/check_renderer.sh "run/saves/Pyridium Welt"`: Das Register zaehlt 19 Sets/53 Strukturen
(je +1 gegenueber Vanilla), die Strukturschicht loggt "Datapack-Struktur pyridiumtest:test_outpost Teil bei ..." fuer die Huetten (Region 0..4096).

### Mitgeliefertes Datenpaket (`src/main/resources/data/pyridium/`)
Alle Vanilla-Typen ausser End City und Villa sind jetzt **Daten**:
* `worldgen/structure_override/<ns>/<name>.json`: Jigsaw-Ersatzdefinition einer prozeduralen Vanilla-Struktur (`pyridium:<ns>/<name>`). Der Compiler
  (`StructureCompiler.overridden`) nimmt sie, wenn das Original kein Jigsaw ist, und behaelt Biome, Schritt, Gelaendeanpassung und Spawns des Originals
  (ein Datapack kann sie also weiter ueberschreiben). Pools unter `worldgen/template_pool/override|fortress|mineshaft|stronghold|monument/`, Vorlagen
  unter `structure/scattered|fortress|mineshaft|stronghold|monument/`.
* Erzeugt von zwei Offline-Werkzeugen (Ausgabe committet, zur Laufzeit nicht noetig):
  `./gradlew exportPack` (`PackExporter`: Streu-Bauwerke aus den Bauanweisungen -> NBT, Overrides fuer Igloo, Wrack, Ozeanruinen, Schatz, Nether-Fossil, Portalruinen,
  Nether-Festung als Jigsaw-Netz mit den Anschluessen der 14 Teiltypen) und `python3 tools/pack/gen_structures.py` (Bergwerk normal/mesa, Stronghold, Ozeanmonument als
  Jigsaw-Netze aus Gang-/Kreuzungs-/Raum-/Treppen-/Portalraum-/Fluegel-Vorlagen mit Jigsaw-Anschluessen; vanilla-aehnlich, nicht identisch).
* Die Kernel fuer Igloo, Wrack, Ozeanruine, Schatz, Nether-Fossil, Portalruine, Streu-Bauwerke und Festung sind entfernt (`wg_struct_proc.cuh`), ebenso die
  zugehoerigen Java-Teile in `ProceduralTypes`. `ScatteredPieces`/`FortressPieces`/`VirtualPiece` bleiben nur als Eingabe des Offline-Werkzeugs.
* Strukturen werden nie ueber Namen im Code erkannt; Biome nur ueber die Tags/Listen der Strukturdefinition.
* **Noch Code**: End City (`wg_struct_endcity.cuh`) und Woodland Mansion (`wg_struct_mansion.cuh`) sind Zustandsautomaten mit Vorlagenlisten (`ProceduralTypes`);
  als Jigsaw-Netz erst offen. Stronghold-Ringe (concentric_rings) brauchen die Biom-Ebenen der Ringloeser (im LOD abgeschaltet), daher erscheinen
  Strongholds dort noch nicht (Daten und Teile sind da, getestet mit der synthetischen Umgebung: ~90 Teile je Start).
* Zufall der Jigsaw-Loeser: zaehlerbasiert (Struktur-Seed, Quellteil, Jigsaw, Versuch), nicht Vanillas Reihenfolge; Platzierung und Startwahl bleiben
  identisch (harness `gen`: Jigsaw-Strukturen nur statistisch verglichen, ersetzte Strukturen uebersprungen).

### Praezompilierung der Kernel
`./gradlew ... -Pcubin` (oder `-Pcubin=75,120`) erzeugt statt reinem PTX eine Fatbin (PTX compute_75 + SASS je Architektur), damit der Treiber beim ersten Laden nicht
jeden Kernel uebersetzen muss (JIT ca. 30 s je grossem Modul, einmal je PTX-Hash). Standard bleibt PTX (Build-Zeit).
