# GPU full-chunk generator (near world / integrated server)

Everything that fills a chunk for the server runs as CUDA kernels in one batch per launch; the CPU only copies the
packed result into Minecraft's containers. Source: `worldgen/full/*`, kernels `cuda/wg_full_*.cu`, `cuda/wg_carve.cuh`
(shared with the LOD: `carvedAt/carveFloor/carveGather/oreAt`).

## Pipeline (per batch of up to `pyridium.fullBatch` = 64 chunks, one stream submission, one download)

1. `ExactBiomePipeline` on own chunk + 8 neighbours (deduplicated over the batch, biome rows subsampled vertically, `pyridium.fullBiomeYs`=8)
   -> `wg_full_win` gathers the 6x6 quart biome window
2. terrain, two modes (`-Dpyridium.fullMode=fast|exact`):
   * `fast` (default): `FastTerrainPipeline`: preliminary surface on a 4-block grid (bilinear + hash noise) -> `wg_full_hf` writes stone/water/air,
     then the datapack SurfaceRules run on it (`wg_full_surface`, copy of the exact kernel: no ore veins, deep rock keeps the default block,
     no aquifers: sea-level water). Not bit-exact, ~5 us/chunk of plan + ~15 us surface rules.
   * `exact`: `ExactTerrainPipeline` (density, aquifers, surface; verified 0 differences vs Vanilla with `-Dpyridium.fullVerify=true`)
3. `wg_full_carve`: cave worms + canyons (`wg_carve.cuh`, hash-seeded capsules per 64/128 block cell)
4. `wg_full_ore`: ore blobs from the common underground ore features (count, rarity, height range, size, targets, discard)
5. `wg_full_decor`: ground cover + trees from the datapack's vegetal features (`FullDecor`)
6. extra stages (`FullChunkPipeline.addStage`, mask bit 4): structures, see `wg_structure_hook.cuh` and `wg_full_struct.cu.snippet`
7. `wg_full_height` (4 heightmaps + vertical sky light) and `wg_full_pack` (palette + SimpleBitStorage + block counters per 16^3 section)

Server hooks (mixins): `ChunkGenerator.createBiomes` runs the whole thing (BIOMES status); `buildTerrain`, `generateFeatures` are no-ops
for GPU chunks; `generateStructureStarts` is skipped for overworld-like generators (one-time warning); `Heightmap.primeHeightmaps` is
skipped (heightmaps come from the GPU); `initializeLight` queues GPU sky-light layers (uniform layers without arrays), `isLighted`
makes LIGHT a no-op. Unedited GPU chunks are not saved (`ChunkMapSaveMixin`/`LevelChunkGpuMixin`; they are regenerated from the seed;
`-Dpyridium.fullSaveAll=true` disables). Sections are built without `recalcBlockCounts` (counters come from the GPU).

Fallbacks (nether/end, blending, upgrading, datapack not compilable, no CUDA) go to the previous partial-GPU/Vanilla path and are logged
(`GPU-Vollchunk ... Fallback`). VRAM shortage never falls back: requests wait and the batch shrinks/grows with `cuMemGetInfo`
(`pyridium.fullVramShare`=0.11 of the free memory), VRAM is released after 120 s without requests. Watchdog: log line after 30 s
without progress (`-Dpyridium.fullDebug=true` additionally logs neighbour state when Vanilla throws 'Requested chunk unavailable').

## Flags
`pyridium.fullGpu`, `fullMode`, `fullBatch`, `fullBiomeYs`, `fullVramShare`, `fullProfile` (per-stage GPU ms, CPU fill ms),
`fullVerify` (exact mode vs Vanilla), `fullStages` (bitmask 1 carve, 2 ore, 4 extra, 8 decor), `fullNoLight`, `fullSelfBench` (GPU-only
throughput, no chunk system), `fullDump=<dir>` (with `-Dpyridium.genBench=R`: top view, slices, raw states), `genBenchKeep`.
Bench: `./gradlew runServer -PpydServerJvm="-Xmx2g -Dpyridium.genBench=24 -Dpyridium.genBenchExit=true"` (delete `run/world`; chunks are
unloaded after counting unless `genBenchKeep`).

## Limits
* Structures: none yet (hook only).
* Fast terrain differs from the LOD heightfield by a few blocks at most (same preliminary surface; different smoothing/noise): no hard seams
  expected but not measured against the LOD.
* Trees only with trunks inside the chunk, simplified shapes; no vines, mushrooms, lakes, springs, disks, dripstone, geodes.
* Carvers/ores vanilla-like (not Vanilla's RNG); ores common to >=60 % of the biomes only.
* Sky light vertical only; block light left to Vanilla's engine.
* Rare Vanilla stall 'Requested chunk unavailable during world generation' (2 of ~40 runs, first batch after startup) not root-caused.
