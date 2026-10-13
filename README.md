# Pyridium

A Fabric mod that replaces Minecraft's rasterizer with a **CUDA/OptiX path tracer** and moves **world generation to the GPU**. No OpenGL/Vulkan rasterization in the world path — primary visibility, direct lighting, diffuse paths and reflections share one CUDA/OptiX tracer over a HashDAG voxel world with adaptive LOD up to 1024 chunks view distance.

## Scaling: T(n) = O(1) + c₁·O(log n) + c₂·O(log log n)

n = view distance in chunks (radius). Frame time is essentially constant in n, with a logarithmic correction:

- **O(1) — shading.** PBR, GI shade and post cost pixels × rays, independent of world size. This ~40 ms constant dwarfs the logarithmic terms (~1 ms).
- **c₁·O(log n) — per-frame IAS refit + instance kernel.** Screen-space-error LOD doubles chunk size per level with distance, so each level contributes O(1) entries and the level count is ≈ log₂ n (9 levels ≈ 36k visible entries at n = 1024).
- **c₂·O(log log n) — BVH traversal.** OptiX traverses those O(log n) instances logarithmically; DAG walks per hit are depth-bounded, i.e. O(1).
- **O(log n) one-time — streaming/buildup**, amortized to ~0 in steady state. VRAM is O(log n), O(1) under the memory budget (distant quality yields instead of memory growing).

The punchline: doubling view distance adds *one* coarse LOD level (~4k cheap entries, ~1–2 % frame time). Without LOD it would be O(n²) in area / O(n³) in volume — exactly the catastrophe the LOD removes. Constants beat complexity class here: 40 ms O(1) vs ~1 ms O(log n).

## How it works

- **Native core (`native/pyrit`, Zig):** builds `libpyridium.so`. OptiX uses the RT cores for the hardware BVH; exact HashDAG hits and foliage alpha stay custom GPU intersection programs. A replay wavefront runs unmodified CUDA shading while OptiX resolves collected rays.
- **Materials:** real baked Minecraft block textures (10,840 materials / 30,154 textured states / 0 flat fallbacks in the Bare Bones PBR test), LabPBR `_n`/`_s` maps, 16k material table. No demo textures.
- **Entities:** Vanilla extracts models/poses/skins; CUDA voxelizes them into shared HashDAG geometries.
- **Worldgen:** CUDA pipelines for terrain density, biomes, aquifers and surfaces; GPU structure placement/generation (Jigsaw solver + procedural types, verified identical to Vanilla in the harness); GPU full-chunk server generator (carve, ore, decor). Carver/features on CPU are being ported step by step.
- **Shaderpacks:** runtime NVRTC packs (`display`/`sky`/`sun` stages, ABI 1) with hot-reload; the default look is itself a pack.
- **Caching at full path tracing:** GI every Nth frame (`PYRIT_GI_EVERY`), static frame reuse (`PYRIT_STATIC_REUSE=1`, opt-in), pipelined LOD stats. No ray is ever dropped — only reuse.

## Status and verified limits

Production target is 165 FPS at 1024 chunks view distance — **not proven**. Measured (RTX 5070 Laptop, 1920×1080 native, 1024-chunk radius, Duty 1):

| Config | Real FPS | GPU time |
|---|---|---|
| Full GI/path tracing + reflections + RR | 17.6 (16.9–18.2) | ~47.5 ms |
| Direct (`direct`, no GI pass) | 18.9 (18.2–19.8) | ~42.8 ms |
| RR + GI + ray effects off, RT cores on | 29.6 (28.7–30.1) | ~16.5 ms |
| Native mode, GI every 2nd / 3rd (`PYRIT_GI_EVERY=2/3`) | ~9 (CPU-bound headless) | GI 9.2 → 3.0 / 2.0 ms |

Single runs, no statistical series. Details, boundaries and non-goals: [`RENDERER.md`](RENDERER.md), [`docs/GPU_WORLDGEN.md`](docs/GPU_WORLDGEN.md), [`docs/STRUCTURES.md`](docs/STRUCTURES.md), [`docs/SHADERPACKS.md`](docs/SHADERPACKS.md), [`docs/FULL_CHUNK_GEN.md`](docs/FULL_CHUNK_GEN.md).

## Requirements

- Linux x86_64, NVIDIA GPU (CUDA + OptiX), JDK 25, Zig, `nvcc` (optional — without it, CUDA kernels are skipped and Vanilla computes)
- Minecraft 26.3, Fabric Loader 0.19.5 (via Fabric Loom)

## Build, run, test

```bash
./gradlew build -x compileCuda        # mod JAR (native lib builds automatically)
./gradlew runClient                   # client (native lib + CUDA kernels build automatically)
./gradlew runServer -PpydServerJvm="-Xmx2g -Dpyridium.genBench=24 -Dpyridium.genBenchExit=true"
bash tools/check_renderer.sh 'run/saves/Pyridium Test'   # headless Full-HD renderer test on a copied save
tools/bench_render.sh "run/saves/Pyridium Welt"          # benchmark: <= 6 ms GPU, >= 94 % util targets
./gradlew structHarness -Pmode=place -Pseed=4711 -Pradius=300   # structure Vanilla comparison
( cd native/pyrit && zig build test ) # 66 native tests (CPU, no GPU needed)
```

Tests use a disposable copied save, SDL dummy drivers, and muted test audio (`check_renderer.sh` silences only the throwaway copy — never player settings or the mod itself). Never point tests at a valued save. Only one GPU run at a time (8 GB VRAM, enforced by a lock file).

## Configuration

- In-game: Settings → Graphics → **Pyridium · CUDA** (ray reconstruction, path tracing, ray effects, RT cores; persisted in `config/pyridium-renderer.properties`).
- Key flags: `-Dpyridium.viewChunks=1024`, `-Dpyridium.targetFps=165` (`0` = full internal resolution), `-Dpyridium.mode=native|taau|dlaa|dlss|rr`, `-Dpyridium.shaderpack=<name|path>`.
- Key env: `PYRIT_GI_EVERY=N`, `PYRIT_STATIC_REUSE=1` (+ `PYRIT_NATIVE_NOJITTER=1` + `PYRIT_NOTIME=1` for truly frozen scenes), `PYRIT_GPU_TIMING=1` (+ `PYRIT_GPU_TIMING_PASSES=1`), `PYRIT_GI_FRAMES`, `PYRIT_REPLAY_PASSES[_GI]`, `PYRIT_REPLAY_STATS=1`.

## License

GPL-3.0-or-later (see [`LICENSE`](LICENSE)). The look of the default packs takes cues from Noble (BelmuTM, GPL-3.0-or-later) and Arc-Shader (Null-MC, MIT) — own CUDA code, no copied shader code.
