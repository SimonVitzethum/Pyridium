# Pyridium GPU-Worldgen („PG“): Entwurf

Ziel: schöne, vanilla-artige Welten, vollständig auf der GPU (CUDA), direkt auf jeder
LOD-Stufe erzeugbar, Datapacks (Biome, Noise, Strukturen, Features) werden übernommen.
Bitgleichheit mit Vanilla ist **kein** Ziel; Plausibilität, Look und Geschwindigkeit sind es.

## Grundprinzip: zustandslos und stufenunabhängig

Jeder Voxel hängt nur von (Seed, x, y, z, Stufe) ab, nie von Nachbarchunks oder
Generierungsreihenfolge. Damit ist dieselbe Funktion bei Schritt `s = 2^lod` abtastbar:
LOD0 und LOD7 zeigen dieselbe Welt, es gibt keine Chunk-Abhängigkeiten, keine Locks,
keine CPU-Rückkopplung. Nur sichtbare Haut wird erzeugt (Band um die Oberfläche plus
sichtbare Höhlenwände), Rest bleibt leer/verdeckt.

## Bausteine

| Teil | Ansatz | Datapack | Status |
|---|---|---|---|
| Gelände, Höhlen (Cheese/Spaghetti/Noodle, Adern) | Noise-Router/`finalDensity` per NVRTC kompiliert (`ExactPlan`) | ja, jede Dichtefunktion | vorhanden |
| Biome | Klima-R-Baum aus der Registry (`ExactBiomes`) | ja | vorhanden |
| Oberflächenmaterial | `SurfaceRules` → flaches Programm, `wg_exact_surface.cu` auf LOD-Gitter | ja | **im LOD** (`wg_lod_surface.cu`, vier Tiefen je Spalte, Hang/Biom/Wasser aus der LOD-Spalte) |
| Wasser/Aquifer | `wg_exact_aquifer.cu` auf LOD-Gitter | ja | Kernel da, nicht im LOD |
| Canyons/Schluchten (Carver) | Zellen (z. B. 8×8 Chunks) mit hash-erzeugten Wurm-Pfaden, Kapsel-Test je Voxel | Carver-Parameter aus Registry | neu |
| Erze | je Chunkzelle hash-erzeugte Blobs aus `ore`-Features (count, Höhe, size, Ziel-Blöcke) | ja | neu |
| Strukturen | **Komplett GPU, keine CPU-Generierung.** Platzierung (`StructurePlacement`: random_spread, concentric_rings) und Biom-/Höhenprüfung im Kernel. Teile: ein generischer GPU-Jigsaw-Löser (Pools, Connectoren, begrenzte Tiefe, Kollisionsprüfung) für Dörfer, Bastion, Außenposten, Trail Ruins, Ancient City, Trial Chambers; handgeschriebene Kernel für Stronghold, Mineshaft, Monument, Festung, Endstadt, Wrack, Ruinen. Die Registry wird beim Laden **einmal** zu GPU-Tabellen kompiliert (wie die Dichtefunktionen), NBT-Vorlagen zu DAG-Geometrie gebacken – das ist Vorbereitung, keine Generierung | ja (Pools, Vorlagen, Sets) | neu |
| Bäume/Vegetation | GPU-Generatoren je Feature-Typ (Stamm-/Kronenplatzierer, Patches, Blobs) mit den Parametern der `configured_feature`s; Platzierung per Hash-Raster nach count/rarity | ja | LOD: Dichte/Arten aus den Datapack-Features (`ExactVegetation`), Form noch Näherung; Generator-Kernel offen |
| Licht | entfällt: Path Tracing; nur Nahwelt serverseitig braucht Vanilla-Licht | – | – |

Strukturen sind selten, aber als Instanz billig: Pyrits DAG teilt Geometrie, tausende
Instanzen kosten fast nichts. Erzeugung (Platzierung, Jigsaw, Teile) läuft im Kernel; die
Vorlagen-Geometrie wird beim Laden aus den Registry-NBTs gebacken. Bäume/Vegetation
folgen demselben Weg: GPU-Generatoren je Feature-Typ (TreeFeature-Familie, Patches, Blobs),
Vanilla-Feature-Code läuft nie zur Laufzeit.

## Pipeline

1. **Säulen-Cache (GPU-resident):** je Spalte und Stufe Höhe, Biom, Oberflächenblock,
   Wasserstand. Wird nur bei Kamerabewegung ergänzt.
2. **Auftragsliste auf der GPU:** Bandauswahl im Kernel, Atomic-Zähler, indirekter
   Dispatch für die 3D-Stufe. Kein Readback, keine `sync`s, kein `generate_mutex`.
3. **Voxelkernel:** Dichte → Oberfläche/Höhle → Material (SurfaceRules) → Erze →
   Carver-Kapseln → Haut direkt in Pyrits Puffer (wie `wg_lod_voxels.cu`).
4. **Strukturen/Bäume:** getrennt als Instanzen (Erzeugung im Kernel), nicht in die Chunkvoxel gebacken.
5. Eigener Stream und eigener Thread, asynchron zum Renderer.

## Meilensteine (jeweils messbar)

1. LOD-Pipeline ohne CPU-Roundtrip (Bandauswahl auf der GPU). Messwert: LOD-Chunks/s,
   Ziel ≥ 50 000/s. Aktuell: 250 Aufträge in ~25 s.
2. Exakte SurfaceRules + Aquifer/Meeresspiegel im LOD (ersetzt Biom-Näherung).
3. Erze und Carver-Würmer (vanilla-artig, stufenunabhängig).
4. Vorlagen-Bäume/Vegetation aus Datapack-Features.
5. Strukturen als Instanzen, schrittweise: Dörfer, Tempel, Festungen, Ruinen, Wracks …
   bis alle Vanilla-Strukturen laufen; Prüfliste über die Registry.
6. Nahwelt: der integrierte Server nutzt dieselbe GPU-Welt (kein Vanilla-Generator mehr; Chunk-Download nur dort, wo Minecraft Block-States braucht).

## Abnahme

- Datapack-Test: eigenes Biom + eigene Struktur erscheinen im LOD und in der Nahwelt.
- Alle `minecraft:worldgen/structure`-Einträge werden gestartet, keiner wirft.
- Screenshots Full-HD bei LOD0…7, keine sichtbaren Nähte beim LOD-Wechsel.
- Chunks/s und GPU-Auslastung pro Prozess (`nvidia-smi pmon`), nicht global.

## Stand (2026-10-05)

- LOD ohne CPU-Rückkopplung (Bandauswahl `wg_lod_select.cu`), Sichtkegel-Priorität in Pyrits Planer.
- Keine festen Stufen: Höhenfeld (`wg_lod_height.cu`) statt 3D-Dichte, sobald ein Voxel nach Auflösung/FOV
  jenseits von `pyridium.caveBlocks` (1024 Blöcke) liegt; Vorwärmen der Stufen folgt der Sichtweite.
- Echte SurfaceRules im LOD, datengetriebene Bäume. Durchsatz ~11–15k LOD-Chunks/s unter geteilter GPU.
- In Arbeit (parallele Worktrees): Strukturen auf der GPU, Server-/Nahwelt-Generator (Carver, Erze), Renderer 6 ms.
