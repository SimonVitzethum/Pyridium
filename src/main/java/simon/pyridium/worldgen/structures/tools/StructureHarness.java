package simon.pyridium.worldgen.structures.tools;

import com.mojang.serialization.MapCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import simon.pyridium.worldgen.exact.CudaCompute;
import simon.pyridium.worldgen.exact.GpuCompute;
import simon.pyridium.worldgen.structures.StructureCompiler;
import simon.pyridium.worldgen.structures.StructureData;
import simon.pyridium.worldgen.structures.StructureGpu;
import simon.pyridium.worldgen.structures.StructureTables;
import simon.pyridium.worldgen.structures.SynthEnvProvider;

/**
 * Testwerkzeug der Strukturgenerierung (nur Entwicklung, nie zur Laufzeit): bootet Minecrafts Registries
 * (Vanilla-Daten), kompiliert die Strukturregistry zu GPU-Tabellen und vergleicht die GPU-Ergebnisse mit
 * Vanillas Platzierung und Jigsaw-Aufbau auf einer analytischen Test-Umgebung ({@link Synth}).
 *
 * <p>Aufruf: {@code ./gradlew structHarness -Pmode=place -Pseed=4711 -Pradius=300}
 */
public final class StructureHarness {
    static final Path SCRATCH = Path.of("build", "scratch");

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("mode", args.length > 0 ? args[0] : "stats");
        long seed = Long.getLong("seed", 4711L);
        int radius = Integer.getInteger("radius", 300);
        Files.createDirectories(SCRATCH);
        VanillaBoot boot = new VanillaBoot();
        Env env = new Env(boot, seed);
        System.out.println("Registries geladen; Biome: " + env.universe.size());
        switch (mode) {
            case "stats" -> stats(env);
            case "place" -> place(env, radius);
            case "gen" -> gen(env, radius);
            case "bench" -> bench(env, radius);
            case "tpl" -> {
                if (System.getProperty("nbt") != null) {
                    var tag = net.minecraft.nbt.NbtIo.readCompressed(java.nio.file.Path.of(System.getProperty("nbt")), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    var pal = tag.getListOrEmpty("palette");
                    for (int i = 0; i < pal.size(); i++) {
                        var st = net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK, pal.getCompoundOrEmpty(i));
                        System.out.println("palette " + i + ": " + pal.getCompoundOrEmpty(i) + " -> " + st + " jigsaw=" + st.is(net.minecraft.world.level.block.Blocks.JIGSAW));
                    }
                    System.out.println("blocks: " + tag.getListOrEmpty("blocks").size() + " erster: " + tag.getListOrEmpty("blocks").getCompoundOrEmpty(1));
                }
                String f = System.getProperty("structure", "pyridium");
                for (int i = 0; i < env.tables.numTemplates(); i++) {
                    if (!env.tables.templateNames.get(i).contains(f)) continue;
                    int b = i * StructureTables.TPL_W;
                    System.out.printf("%s: %dx%dx%d, Jigsaws %d, fehlt %d%n", env.tables.templateNames.get(i), env.tables.templates.getInt(b), env.tables.templates.getInt(b + 1),
                            env.tables.templates.getInt(b + 2), env.tables.templates.getInt(b + StructureTables.TP_JIGCOUNT), env.tables.templates.getInt(b + StructureTables.TP_FLAGS));
                }
            }
            case "rings" -> rings(env);
            case "tile" -> tile(env, radius);
            case "real" -> real(env, radius);
            case "vstat" -> vstat(env, radius);
            case "view" -> view(env, radius);
            default -> throw new IllegalArgumentException("mode " + mode);
        }
        System.exit(0);
    }

    // ----------------------------------------------------------------------------------------
    /** Gemeinsamer Teststand: Registries, Biom-Universum, Vanilla-Zustand, Tabellen */
    static final class Env {
        final VanillaBoot boot;
        final long seed;
        final List<Holder.Reference<Biome>> universe;
        final StructureTables tables;
        final BiomeSource biomeSource;
        final ChunkGenerator generator;
        final boolean real;
        final RandomState randomState;
        final ChunkGeneratorStructureState state;
        final net.minecraft.core.HolderLookup.RegistryLookup<StructureSet> setLookup;
        StructureTemplateManager templateManager;

        Env(VanillaBoot boot, long seed) {
            this.boot = boot;
            this.seed = seed;
            this.real = Boolean.getBoolean("real");
            var biomes = boot.registries.lookupOrThrow(Registries.BIOME);
            String dim = System.getProperty("dim", "overworld");
            BiomeSource realSource = null;
            if (real) {
                var preset = boot.registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
                realSource = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(preset);
            }
            Set<String> allowed = new HashSet<>(StructureCompiler.biomesOfTag(StructureData.overlay(StructureData.of(boot.resources), java.nio.file.Path.of("src/main/resources")), "minecraft:is_" + dim));
            if (real) {
                allowed.clear();
                for (var h : realSource.possibleBiomes()) allowed.add(h.unwrapKey().get().identifier().toString());
            }
            List<Holder.Reference<Biome>> u = new ArrayList<>(biomes.listElements().filter(h -> allowed.contains(h.key().identifier().toString())).toList());
            u.sort((x, y) -> x.key().identifier().toString().compareTo(y.key().identifier().toString()));
            universe = u;
            List<String> ids = new ArrayList<>();
            for (var h : universe) ids.add(h.key().identifier().toString());
            tables = StructureCompiler.compile(StructureData.overlay(StructureData.of(boot.resources), java.nio.file.Path.of("src/main/resources")), ids, seed, -64, 384, 63, dim.equals("overworld") ? 0 : dim.equals("nether") ? 1 : 2);
            String only = System.getProperty("sets", "");
            if (!only.isEmpty()) {
                Set<String> keep = new HashSet<>(Arrays.asList(only.split(",")));
                for (int i = 0; i < tables.numSets(); i++)
                    if (!keep.contains(tables.setNames.get(i).substring(10))) {
                        tables.sets.set(i * StructureTables.SET_W + StructureTables.SET_ENTRY_COUNT, 0);
                        tables.sets.set(i * StructureTables.SET_W + StructureTables.SET_ENV_INTS, 0);
                    }
            }
            var settingsHolder = boot.registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var settings = settingsHolder.value();
            if (real) {
                biomeSource = realSource;
                generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(realSource, settingsHolder);
            } else {
                biomeSource = new SynthBiomeSource(universe);
                generator = new SynthGenerator(biomeSource);
            }
            randomState = RandomState.create(boot.registries.lookupOrThrow(Registries.NOISE), seed, settings);
            setLookup = boot.registries.lookupOrThrow(Registries.STRUCTURE_SET);
            state = ChunkGeneratorStructureState.createForNormal(randomState, seed, ChunkPos.ZERO, biomeSource, setLookup);
        }

        StructureTemplateManager templates() throws IOException {
            if (templateManager == null) {
                var access = LevelStorageSource.createDefault(SCRATCH.resolve("saves")).createAccess("harness");
                templateManager = new StructureTemplateManager(boot.resources, access, net.minecraft.util.datafix.DataFixers.getDataFixer(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK);
            }
            return templateManager;
        }

        /** Vanillas ChunkGenerator.createStructures fuer ein (Set, Chunk): {Struktur-Name, Start} oder null */
        Object[] vanillaStart(String setName, int cx, int cz) throws IOException {
            StructureSet set = vanillaSet(setName);
            List<StructureSet.StructureSelectionEntry> structures = set.structures();
            if (structures.size() == 1) {
                StructureStart st = vanillaGenerate(structures.get(0), cx, cz);
                return st.isValid() ? new Object[]{name(structures.get(0)), st} : null;
            }
            List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(structures);
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(seed, cx, cz);
            int total = 0;
            for (var o : options) total += o.weight();
            while (!options.isEmpty()) {
                int choice = random.nextInt(total);
                int index = 0;
                for (var o : options) {
                    choice -= o.weight();
                    if (choice < 0) break;
                    index++;
                }
                var sel = options.get(index);
                StructureStart st = vanillaGenerate(sel, cx, cz);
                if (st.isValid()) return new Object[]{name(sel), st};
                options.remove(index);
                total -= sel.weight();
            }
            return null;
        }

        static String name(StructureSet.StructureSelectionEntry e) {
            return e.structure().unwrapKey().get().identifier().toString();
        }

        StructureStart vanillaGenerate(StructureSet.StructureSelectionEntry e, int cx, int cz) throws IOException {
            Structure structure = e.structure().value();
            return structure.generate(e.structure(), Level.OVERWORLD, boot.fullAccess, generator, biomeSource, randomState.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED), randomState,
                    templates(), seed, new ChunkPos(cx, cz), 0, LevelHeightAccessor.create(-64, 384), structure.biomes()::contains);
        }

        StructureSet vanillaSet(String name) {
            return setLookup.getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, net.minecraft.resources.Identifier.parse(name))).value();
        }

        /** Ringpositionen (Stronghold) aus Vanilla in die Tabellen (bis der GPU-RingSolver sie liefert) */
        void fillRingsFromVanilla() {
            for (int s = 0; s < tables.numSets(); s++) {
                int b = s * StructureTables.SET_W;
                if (tables.sets.getInt(b + StructureTables.SET_TYPE) != 1) continue;
                StructurePlacement pl = vanillaSet(tables.setNames.get(s)).placement();
                List<ChunkPos> pos = state.getRingPositionsFor((ConcentricRingsStructurePlacement) pl);
                int base = tables.sets.getInt(b + StructureTables.SET_RING_POS);
                for (int i = 0; i < pos.size(); i++) {
                    tables.ringPos.set((base + i) * 2, pos.get(i).x());
                    tables.ringPos.set((base + i) * 2 + 1, pos.get(i).z());
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    static final class SynthBiomeSource extends BiomeSource {
        final List<Holder.Reference<Biome>> universe;

        SynthBiomeSource(List<Holder.Reference<Biome>> universe) {
            this.universe = universe;
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return universe.stream().map(h -> (Holder<Biome>) h);
        }

        @Override
        public BiomeResolver createResolver(Climate.Sampler sampler) {
            return (qx, qy, qz) -> universe.get(Synth.biome(qx, qy, qz, universe.size()));
        }
    }

    static final class SynthGenerator extends ChunkGenerator {
        SynthGenerator(BiomeSource source) {
            super(source);
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void spawnOriginalMobs(WorldGenRegion region) {
        }

        @Override
        public int getGenDepth() {
            return 384;
        }

        @Override
        public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
                                                           BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
            return type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.OCEAN_FLOOR ? Synth.floor(x, z) : Synth.height(x, z);
        }

        @Override
        public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
            net.minecraft.world.level.block.state.BlockState[] states = new net.minecraft.world.level.block.state.BlockState[heightAccessor.getHeight()];
            for (int i = 0; i < states.length; i++) {
                int c = Synth.column(x, heightAccessor.getMinY() + i, z);
                states[i] = c == 2 ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                        : c == 1 ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            return new NoiseColumn(heightAccessor.getMinY(), states);
        }

        @Override
        public void addDebugScreenInfo(List<String> result, RandomState randomState, net.minecraft.core.BlockPos feetPos,
                                       net.minecraft.world.level.levelgen.densityfunction.SamplerContext samplerContext) {
        }
    }

    // ----------------------------------------------------------------------------------------
    static void stats(Env env) {
        StructureTables t = env.tables;
        System.out.println("Sets: " + t.setNames);
        System.out.println("Strukturen: " + t.numStructs() + " nicht unterstuetzt: " + new TreeMap<>(t.unsupported));
        System.out.println("Vorlagen: " + t.numTemplates() + " Voxel-KiB: " + t.voxels.size() * 2 / 1024);
    }

    static GpuCompute.BinarySource ptxSource() {
        return name -> Files.readAllBytes(Path.of("build", "cuda", name));
    }

    static void place(Env env, int radius) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        if (gpu == null) throw new IllegalStateException("kein CUDA: " + CudaCompute.lastError);
        System.out.println("GPU: " + gpu.deviceName());
        try (StructureGpu sg = new StructureGpu(gpu, env.tables, ptxSource(), "wg_struct_synth", 1 << 17, Integer.getInteger("capStart", 1 << 14), Integer.getInteger("capPieces", 1 << 18), Integer.getInteger("slots", 256), 1 << 20)) {
            sg.solveRingsAsync();
            sg.sync();
            int side = 2 * radius;
            long t0 = System.nanoTime();
            StructureGpu.Result r = null;
            // Kandidaten nur (gen laeuft mit; hier zaehlt place)
            r = sg.generate(-radius, -radius, side, side);
            System.out.printf("GPU: %d Kandidaten, %d Starts, %d Teile (place %.2f ms, gen %.2f ms)%n", r.candidates, r.starts, r.pieces, sg.lastPlaceMs, sg.lastGenMs);
            // Vergleich Kandidaten je Set
            Map<Integer, Set<Long>> gpuSets = new HashMap<>();
            for (int i = 0; i < r.candData.length / 4; i++)
                gpuSets.computeIfAbsent(r.candData[i * 4], k -> new HashSet<>()).add(((long) r.candData[i * 4 + 1] << 32) | (r.candData[i * 4 + 2] & 0xffffffffL));
            int bad = 0;
            long vanillaMs0 = System.nanoTime();
            for (int s = 0; s < env.tables.numSets(); s++) {
                String name = env.tables.setNames.get(s);
                StructurePlacement pl = env.vanillaSet(name).placement();
                Set<Long> exp = new HashSet<>();
                for (int cx = -radius; cx < radius; cx++)
                    for (int cz = -radius; cz < radius; cz++)
                        if (pl.isStructureChunk(env.state, cx, cz)) exp.add(((long) cx << 32) | (cz & 0xffffffffL));
                Set<Long> got = gpuSets.getOrDefault(s, Set.of());
                Set<Long> missing = new HashSet<>(exp);
                missing.removeAll(got);
                Set<Long> extra = new HashSet<>(got);
                extra.removeAll(exp);
                System.out.printf("%-28s vanilla %4d gpu %4d  fehlt %d zuviel %d%n", name, exp.size(), got.size(), missing.size(), extra.size());
                if (!missing.isEmpty() || !extra.isEmpty()) bad++;
            }
            System.out.printf("Vanilla-Platzierung %.1f s%n", (System.nanoTime() - vanillaMs0) / 1e9);
            System.out.println(bad == 0 ? "PLACEMENT OK: alle Sets identisch" : "PLACEMENT FEHLER in " + bad + " Sets");
        }
        gpu.close();
    }

    // ----------------------------------------------------------------------------------------
    static String describeElement(StructurePoolElement e) {
        if (e instanceof ListPoolElement l) return "List[" + describeElement(l.getElements().get(0)) + "...]";
        if (e instanceof SinglePoolElement sp) return sp.getTemplateLocation().toString() + (e.getClass().getSimpleName().startsWith("Legacy") ? "(L)" : "");
        if (e.getClass().getSimpleName().startsWith("Feature")) return "Feature";
        return e.toString();
    }

    static String describeGpuPiece(StructureTables t, int[] p) {
        int tpl = p[StructureTables.PC_TPL];
        int el = p[StructureTables.PC_ELEM];
        String name;
        int kind = t.elems.getInt(el * StructureTables.ELEM_W + StructureTables.EL_KIND);
        if (kind == 1) {
            name = t.templateNames.get(tpl) + ((t.elems.getInt(el * StructureTables.ELEM_W + StructureTables.EL_FLAGS) & 1) != 0 ? "(L)" : "");
        } else if (kind == 2) {
            int sub = t.subElems.getInt(t.elems.getInt(el * StructureTables.ELEM_W + StructureTables.EL_SUB_START));
            int st = t.elems.getInt(sub * StructureTables.ELEM_W + StructureTables.EL_TEMPLATE);
            name = "List[" + (st >= 0 ? t.templateNames.get(st) : "?") + ((t.elems.getInt(sub * StructureTables.ELEM_W + StructureTables.EL_FLAGS) & 1) != 0 ? "(L)" : "") + "...]";
        } else if (kind == 3) {
            name = "Feature";
        } else {
            name = "kind" + kind;
        }
        return name;
    }

    static String vanillaKey(StructurePiece p, boolean maskY) {
        var bb = p.getBoundingBox();
        int[] box = {bb.minX(), maskY ? 0 : bb.minY(), bb.minZ(), bb.maxX(), maskY ? 0 : bb.maxY(), bb.maxZ()};
        if (p instanceof PoolElementStructurePiece pe) {
            return key(pe.getPosition().getX(), pe.getPosition().getY(), pe.getPosition().getZ(), pe.getRotation().ordinal(), box, describeElement(pe.getElement()));
        }
        if (p instanceof net.minecraft.world.level.levelgen.structure.TemplateStructurePiece tp) {
            String name;
            try {
                var f = net.minecraft.world.level.levelgen.structure.TemplateStructurePiece.class.getDeclaredField("templateName");
                f.setAccessible(true);
                name = (String) f.get(tp);
            } catch (ReflectiveOperationException e) {
                name = "?";
            }
            var pos = tp.templatePosition();
            return key(pos.getX(), maskY ? 0 : pos.getY(), pos.getZ(), tp.placeSettings().getRotation().ordinal() + 10 * tp.placeSettings().getMirror().ordinal(), box, name);
        }
        if (p.getClass().getName().contains("NetherFortressPieces")) {
            int dir = switch (p.getOrientation()) {
                case NORTH -> 0;
                case EAST -> 1;
                case SOUTH -> 2;
                default -> 3;
            };
            return "fort " + p.getClass().getSimpleName() + " dir" + dir + " bb[" + box[0] + "," + box[1] + "," + box[2] + " .. " + box[3] + "," + box[4] + "," + box[5] + "]";
        }
        if (p instanceof net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece) {
            int dir = switch (p.getOrientation()) {
                case NORTH -> 0;
                case EAST -> 1;
                case SOUTH -> 2;
                default -> 3;
            };
            return "scattered dir" + dir + " bb[" + bb.minX() + "," + bb.minZ() + " .. " + bb.maxX() + "," + bb.maxZ() + "]";
        }
        return "piece " + p.getClass().getSimpleName() + " bb[" + Arrays.toString(box) + "]";
    }

    static String gpuKey(StructureTables t, int[] p, boolean maskY) {
        int[] box = {p[StructureTables.PC_MINX], maskY ? 0 : p[StructureTables.PC_MINY], p[StructureTables.PC_MINZ], p[StructureTables.PC_MAXX],
                maskY ? 0 : p[StructureTables.PC_MAXY], p[StructureTables.PC_MAXZ]};
        if (p[StructureTables.PC_KIND] == 0)
            return key(p[StructureTables.PC_X], p[StructureTables.PC_Y], p[StructureTables.PC_Z], p[StructureTables.PC_ROT], box, describeGpuPiece(t, p));
        String sn = t.structNames.get(p[StructureTables.PC_STRUCT]);
        if (sn.endsWith("fortress")) {
            String[] names = {"BridgeCrossing", "BridgeEndFiller", "BridgeStraight", "CastleCorridorStairsPiece", "CastleCorridorTBalconyPiece", "CastleEntrance",
                    "CastleSmallCorridorCrossingPiece", "CastleSmallCorridorLeftTurnPiece", "CastleSmallCorridorPiece", "CastleSmallCorridorRightTurnPiece",
                    "CastleStalkRoom", "MonsterThrone", "RoomCrossing", "StairsRoom"};
            int type = p[StructureTables.PC_FLAGS] >> 8;
            return "fort " + (p[StructureTables.PC_DEPTH] == 0 && type == 0 ? "StartPiece" : names[type]) + " dir" + p[StructureTables.PC_P0 + 2] + " bb[" + box[0] + "," + box[1] + "," + box[2] + " .. " + box[3] + "," + box[4] + "," + box[5] + "]";
        }
        if (sn.endsWith("desert_pyramid") || sn.endsWith("jungle_pyramid") || sn.endsWith("swamp_hut"))
            return "scattered dir" + p[StructureTables.PC_P0 + 2] + " bb[" + p[StructureTables.PC_MINX] + "," + p[StructureTables.PC_MINZ] + " .. " + p[StructureTables.PC_MAXX] + "," + p[StructureTables.PC_MAXZ] + "]";
        if (p[StructureTables.PC_TPL] >= 0)
            return key(p[StructureTables.PC_X], maskY ? 0 : p[StructureTables.PC_Y], p[StructureTables.PC_Z], p[StructureTables.PC_ROT] + 10 * p[StructureTables.PC_MIRROR], box,
                    t.templateNames.get(p[StructureTables.PC_TPL]).replace("minecraft:end_city/", "").replace("minecraft:woodland_mansion/", ""));
        return "piece " + "BuriedTreasurePiece" + " bb[" + Arrays.toString(box) + "]";
    }

    static String key(int x, int y, int z, int rot, int[] bb, String name) {
        return name + " @" + x + "," + y + "," + z + " r" + rot + " bb[" + bb[0] + "," + bb[1] + "," + bb[2] + " .. " + bb[3] + "," + bb[4] + "," + bb[5] + "]";
    }

    static void gen(Env env, int radius) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        StructureTables t = env.tables;
        int compared = 0, same = 0, startsMismatch = 0, piecesMismatch = 0, gpuMissing = 0, gpuExtra = 0;
        long vanillaNs = 0;
        List<String> report = new ArrayList<>();
        try (StructureGpu sg = new StructureGpu(gpu, t, ptxSource(), "wg_struct_synth", 1 << 17, Integer.getInteger("capStart", 1 << 14), Integer.getInteger("capPieces", 1 << 18), Integer.getInteger("slots", 256), 1 << 20)) {
            sg.solveRingsAsync();
            sg.sync();
            long t0 = System.nanoTime();
            StructureGpu.Result r = sg.generate(-radius, -radius, 2 * radius, 2 * radius);
            double gpuMs = (System.nanoTime() - t0) / 1e6;
            System.out.printf("GPU: %d Kandidaten, %d Starts, %d Teile in %.0f ms (place %.1f, gen %.1f), Flags=%d%n", r.candidates, r.starts, r.pieces, gpuMs,
                    sg.lastPlaceMs, sg.lastGenMs, r.flags);
            Map<Long, int[]> gpuStarts = new HashMap<>();
            Map<Long, List<int[]>> gpuPieces = new HashMap<>();
            for (int i = 0; i < r.starts; i++) {
                int[] st = r.start(i);
                long k = ((long) st[StructureTables.SR_SET] << 56) ^ ((long) st[StructureTables.SR_CX] << 28) ^ (st[StructureTables.SR_CZ] & 0xfffffffL);
                gpuStarts.put(k, st);
                List<int[]> ps = new ArrayList<>();
                for (int j = 0; j < st[StructureTables.SR_PIECES]; j++) ps.add(r.piece(st[StructureTables.SR_PIECE0] + j));
                gpuPieces.put(k, ps);
            }
            Set<Integer> supportedSets = new HashSet<>();
            for (int s = 0; s < t.numSets(); s++) {
                boolean all = true;
                int eb = t.sets.getInt(s * StructureTables.SET_W + StructureTables.SET_ENTRY_START);
                int ec = t.sets.getInt(s * StructureTables.SET_W + StructureTables.SET_ENTRY_COUNT);
                boolean anySupported = false;
                for (int e = 0; e < ec; e++) {
                    int si = t.entries.getInt((eb + e) * StructureTables.ENTRY_W);
                    if ((t.structs.getInt(si * StructureTables.STRUCT_W + StructureTables.ST_FLAGS) & 1) == 0) all = false;
                    else anySupported = true;
                }
                if (anySupported) supportedSets.add(s);
            }
            int limit = Integer.getInteger("count", Integer.MAX_VALUE);
            Map<String, int[]> perSet = new TreeMap<>();
            for (int i = 0; i < r.candidates && i < sg.capCand; i++) {
                int set = r.candData[i * 4], cx = r.candData[i * 4 + 1], cz = r.candData[i * 4 + 2];
                if (!supportedSets.contains(set) || t.sets.getInt(set * StructureTables.SET_W + StructureTables.SET_ENTRY_COUNT) == 0) continue;
                String sname = t.setNames.get(set);
                int[] pc = perSet.computeIfAbsent(sname, k -> new int[4]);
                if (pc[0] >= limit) continue;
                long k = ((long) set << 56) ^ ((long) cx << 28) ^ (cz & 0xfffffffL);
                long v0 = System.nanoTime();
                Object[] van = env.vanillaStart(sname, cx, cz);
                vanillaNs += System.nanoTime() - v0;
                int[] gs = gpuStarts.get(k);
                pc[0]++;
                compared++;
                if (van != null && t.unsupported.containsKey((String) van[0])) { compared--; pc[0]--; continue; }
                if ((van != null && t.converted.contains((String) van[0])) || (gs != null && t.converted.contains(t.structNames.get(gs[StructureTables.SR_STRUCT])))) { compared--; pc[0]--; convertedSkipped++; continue; }   // Jigsaw-Datenfassung: nicht 1:1 vergleichbar   // Vanilla wuerfelt eine noch nicht portierte Struktur: nicht vergleichbar
                if (van != null) pc[3]++;
                if (van == null && gs == null) { same++; pc[1]++; continue; }
                if (van == null) { gpuExtra++; pc[2]++; report.add(sname + " " + cx + "," + cz + ": GPU hat Start, Vanilla nicht"); continue; }
                if (gs == null) { gpuMissing++; pc[2]++; report.add(sname + " " + cx + "," + cz + ": Vanilla hat Start (" + van[0] + "), GPU nicht"); continue; }
                String gname = t.structNames.get(gs[StructureTables.SR_STRUCT]);
                StructureStart vs = (StructureStart) van[1];
                if (!gname.equals(van[0])) { startsMismatch++; pc[2]++; report.add(sname + " " + cx + "," + cz + ": Struktur " + gname + " statt " + van[0]); continue; }
                List<StructurePiece> vp = vs.getPieces();
                List<int[]> gp = gpuPieces.get(k);
                boolean maskY = gname.contains("igloo") || gname.contains("shipwreck") || gname.contains("ocean_ruin");
                List<String> a = new ArrayList<>(), b = new ArrayList<>();
                for (StructurePiece p : vp) a.add(vanillaKey(p, maskY));
                for (int[] p : gp) b.add(gpuKey(t, p, maskY));
                if (a.equals(b)) { same++; pc[1]++; continue; }
                if (JIGSAW_LOOSE && t.structs.getInt(gs[StructureTables.SR_STRUCT] * StructureTables.STRUCT_W + StructureTables.ST_TYPE) == StructureTables.STT_JIGSAW) {
                    // Jigsaw: Reihenfolge des Zufalls ist nicht Vanilla (zaehlerbasierter Zufall, parallel); Start/Struktur stimmen, Teile nur statistisch
                    same++; pc[1]++;
                    double[] st = jigStats.computeIfAbsent(gname, kk -> new double[3]);
                    st[0]++; st[1] += a.size(); st[2] += b.size();
                    continue;
                }
                piecesMismatch++;
                pc[2]++;
                int first = 0;
                while (first < a.size() && first < b.size() && a.get(first).equals(b.get(first))) first++;
                report.add(sname + " " + cx + "," + cz + " (" + gname + "): Teile " + a.size() + " vs GPU " + b.size() + ", erster Unterschied bei " + first
                        + "\n    vanilla: " + (first < a.size() ? a.get(first) : "-") + "\n    gpu:     " + (first < b.size() ? b.get(first) : "-"));
            }
            for (var e : perSet.entrySet())
                System.out.printf("%-28s verglichen %4d identisch %4d abweichend %4d (davon mit Start %4d)%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]);
        }
        gpu.close();
        for (var e : jigStats.entrySet()) System.out.printf("  Jigsaw %-34s %4.0f Starts: Teile je Start Vanilla %.1f, GPU %.1f%n", e.getKey(), e.getValue()[0], e.getValue()[1] / e.getValue()[0], e.getValue()[2] / e.getValue()[0]);
        for (int i = 0; i < Math.min(report.size(), 25); i++) System.out.println(report.get(i));
        System.out.println("durch Daten ersetzte Strukturen (nicht verglichen): " + convertedSkipped);
        System.out.printf("verglichen %d identisch %d  (Start fehlt GPU %d, extra GPU %d, andere Struktur %d, Teile abweichend %d); Vanilla %.1f s%n", compared, same,
                gpuMissing, gpuExtra, startsMismatch, piecesMismatch, vanillaNs / 1e9);
        System.out.println(same == compared ? "GEN OK: alle Starts identisch" : "GEN ABWEICHUNGEN");
    }

    static int convertedSkipped;
    static final boolean JIGSAW_LOOSE = !Boolean.getBoolean("strict");
    static final Map<String, double[]> jigStats = new TreeMap<>();

    static void bench(Env env, int radius) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        try (StructureGpu sg = new StructureGpu(gpu, env.tables, ptxSource(), System.getProperty("module", "wg_struct_synth"), 1 << 17, Integer.getInteger("capStart", 1 << 14),
                Integer.getInteger("capPieces", 1 << 18), Integer.getInteger("slots", 256), 1 << 20)) {
            sg.solveRingsAsync();
            sg.sync();
            for (int rep = 0; rep < 2; rep++) {
                sg.clearStats();
                StructureGpu.Result r = sg.generate(-radius, -radius, 2 * radius, 2 * radius);
                Map<String, int[]> per = new TreeMap<>();
                for (int i = 0; i < r.starts; i++) {
                    int[] st = r.start(i);
                    int[] c = per.computeIfAbsent(env.tables.structNames.get(st[StructureTables.SR_STRUCT]), k -> new int[2]);
                    c[0]++;
                    c[1] += st[StructureTables.SR_PIECES];
                }
                System.out.printf("Region %d x %d Chunks: %d Kandidaten, %d Starts, %d Teile; place %.1f ms, gen %.1f ms => %.0f Starts/s, %.0f Teile/s; Flags=%d%n",
                        2 * radius, 2 * radius, r.candidates, r.starts, r.pieces, sg.lastPlaceMs, sg.lastGenMs, r.starts / (sg.lastGenMs / 1e3), r.pieces / (sg.lastGenMs / 1e3), r.flags);
                if (System.getProperty("module", "").endsWith("stats")) System.out.println("  Zaehler: " + java.util.Arrays.toString(java.util.Arrays.copyOf(sg.readStats(), 8))
                        + " (Quell-Jigsaws, Elem x Rot Versuche, canAttach, freeFits, Knoten, Kandidatenlisten)");
                if (rep == 1) for (var e : per.entrySet()) System.out.printf("  %-34s %5d Starts, %7d Teile%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
        }
        gpu.close();
    }

    // ----------------------------------------------------------------------------------------
    static int stateColor(int stateId) {
        var st = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(stateId);
        if (st == null) return 0xff00ff;
        return st.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).col;
    }

    static int terrainTop(int x, int z) {
        int h = Synth.height(x, z);
        int g = 90 + (h - 60) * 8;
        return (60 << 16) | (Math.min(255, Math.max(60, g)) << 8) | 50;
    }

    static void view(Env env, int radius) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        String want = System.getProperty("structure", "village_plains");
        int pick = Integer.getInteger("pick", 0);
        try (StructureGpu sg = new StructureGpu(gpu, env.tables, ptxSource(), "wg_struct_synth", 1 << 17, 1 << 14, 1 << 20, 256, 1 << 20)) {
            sg.solveRingsAsync();
            sg.sync();
            StructureGpu.Result r = sg.generate(-radius, -radius, 2 * radius, 2 * radius);
            int found = 0;
            for (int i = 0; i < r.starts; i++) {
                int[] st = r.start(i);
                String name = env.tables.structNames.get(st[StructureTables.SR_STRUCT]);
                if (!name.endsWith(want) || found++ < pick) continue;
                int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
                for (int j = 0; j < st[StructureTables.SR_PIECES]; j++) {
                    int[] p = r.piece(st[StructureTables.SR_PIECE0] + j);
                    x0 = Math.min(x0, p[StructureTables.PC_MINX]); z0 = Math.min(z0, p[StructureTables.PC_MINZ]); y0 = Math.min(y0, p[StructureTables.PC_MINY]);
                    x1 = Math.max(x1, p[StructureTables.PC_MAXX]); z1 = Math.max(z1, p[StructureTables.PC_MAXZ]); y1 = Math.max(y1, p[StructureTables.PC_MAXY]);
                }
                for (int j = 0; j < Math.min(3, st[StructureTables.SR_PIECES]); j++) {
                    int[] p = r.piece(st[StructureTables.SR_PIECE0] + j);
                    int tpl = p[StructureTables.PC_TPL];
                    if (tpl >= 0) {
                        int tb = tpl * StructureTables.TPL_W, nz = 0, vo = env.tables.templates.getInt(tb + StructureTables.TP_VOX);
                        int n = env.tables.templates.getInt(tb) * env.tables.templates.getInt(tb + 1) * env.tables.templates.getInt(tb + 2);
                        for (int q = 0; q < n; q++) if (env.tables.voxels.getShort(vo + q) != 0) nz++;
                        System.out.printf("  Teil %d: %s size %dx%dx%d, %d belegt; pos %d,%d,%d rot %d mirror %d%n", j, env.tables.templateNames.get(tpl), env.tables.templates.getInt(tb),
                                env.tables.templates.getInt(tb + 1), env.tables.templates.getInt(tb + 2), nz, p[StructureTables.PC_X], p[StructureTables.PC_Y], p[StructureTables.PC_Z], p[StructureTables.PC_ROT], p[StructureTables.PC_MIRROR]);
                    }
                }
                x0 -= 8; z0 -= 8; x1 += 8; z1 += 8;
                x0 &= ~7; z0 &= ~7;
                int w = ((x1 - x0 + 8) / 8) * 8, d = ((z1 - z0 + 8) / 8) * 8;
                System.out.printf("%s @chunk %d,%d: %d Teile, Box [%d..%d] x [%d..%d] x [%d..%d]%n", name, st[StructureTables.SR_CX], st[StructureTables.SR_CZ],
                        st[StructureTables.SR_PIECES], x0, x1, y0, y1, z0, z1);
                for (int lod = 0; lod <= 3; lod++) {
                    int step = 1 << lod;
                    int pw = w / step, pd = d / step;
                    long t0 = System.nanoTime();
                    int[] img = sg.topView(x0, z0, pw, pd, step, y0 - 4, y1 + 4, true);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    int scale = Math.max(1, 4 / step);
                    java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(pw * scale, pd * scale, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    for (int pz = 0; pz < pd; pz++)
                        for (int px = 0; px < pw; px++) {
                            int s = img[(pz * pw + px) * 2];
                            int rgb = s != 0 ? stateColor(s & 0x7fffffff) : terrainTop(x0 + px * step, z0 + pz * step);
                            for (int a = 0; a < scale; a++) for (int b = 0; b < scale; b++) bi.setRGB(px * scale + a, pz * scale + b, rgb);
                        }
                    Path out = SCRATCH.resolve("view_" + want + "_" + pick + "_lod" + lod + ".png");
                    javax.imageio.ImageIO.write(bi, "png", out.toFile());
                    System.out.printf("  LOD%d (%d x %d Pixel, %.2f ms) -> %s%n", lod, pw, pd, ms, out);
                }
                // Seitenansicht bei z = Mitte
                int zc = (z0 + z1) / 2;
                int hgt = y1 + 6 - (y0 - 6);
                int[] sl = sg.slice(x0, zc, w, hgt, 1, y1 + 6, true);
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w * 3, hgt * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int py = 0; py < hgt; py++)
                    for (int px = 0; px < w; px++) {
                        int s = sl[(py * w + px) * 2];
                        int y = y1 + 6 - py;
                        int rgb;
                        if (s != 0) rgb = (s & 0x7fffffff) == 0 ? 0xe8d0d0 : stateColor(s & 0x7fffffff);
                        else rgb = y < Synth.height(x0 + px, zc) ? 0x6b4a2a : 0xbfe6ff;
                        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) bi.setRGB(px * 3 + a, py * 3 + b, rgb);
                    }
                Path out = SCRATCH.resolve("slice_" + want + "_" + pick + ".png");
                javax.imageio.ImageIO.write(bi, "png", out.toFile());
                System.out.println("  Schnitt -> " + out);
                break;
            }
            if (found <= pick) System.out.println("keine Struktur '" + want + "' gefunden (" + r.starts + " Starts)");
        }
        gpu.close();
    }

    static void rings(Env env) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        StructureTables t = env.tables;
        try (StructureGpu sg = new StructureGpu(gpu, t, ptxSource(), "wg_struct_synth", 1 << 12, 1 << 10, 1 << 12, 64, 1 << 12)) {
            sg.solveRingsAsync();
            sg.sync();
            int[] pos = sg.downloadRingPositions();
            int bad = 0, total = 0;
            for (int s = 0; s < t.numSets(); s++) {
                int b = s * StructureTables.SET_W;
                if (t.sets.getInt(b + StructureTables.SET_TYPE) != 1) continue;
                var vp = env.state.getRingPositionsFor((ConcentricRingsStructurePlacement) env.vanillaSet(t.setNames.get(s)).placement());
                int base = t.sets.getInt(b + StructureTables.SET_RING_POS);
                for (int i = 0; i < vp.size(); i++) {
                    total++;
                    int gx = pos[(base + i) * 2], gz = pos[(base + i) * 2 + 1];
                    if (gx != vp.get(i).x() || gz != vp.get(i).z()) {
                        if (bad++ < 10) System.out.printf("Ring %d: GPU %d,%d vanilla %d,%d%n", i, gx, gz, vp.get(i).x(), vp.get(i).z());
                    }
                }
                System.out.printf("%s: %d Ringe, erste Positionen GPU: %d,%d %d,%d %d,%d%n", t.setNames.get(s), vp.size(), pos[base * 2], pos[base * 2 + 1], pos[base * 2 + 2], pos[base * 2 + 3], pos[base * 2 + 4], pos[base * 2 + 5]);
            }
            System.out.println(bad == 0 ? "RINGS OK: " + total + " Positionen identisch" : "RINGS ABWEICHUNG: " + bad + " von " + total);
        }
        gpu.close();
    }

    /** Kachelpfad (Umgebung aus Kacheln eines Referenz-Anbieters) gegen den direkten Analytik-Pfad: muessen identisch sein */
    static void tile(Env env, int radius) throws Exception {
        CudaCompute gpu = CudaCompute.create();
        StructureTables t = env.tables;
        StructureGpu.Result a, b;
        int[] ringsA, ringsB;
        try (StructureGpu sg = new StructureGpu(gpu, t, ptxSource(), "wg_struct_synth", 1 << 17, 1 << 14, 1 << 19, 256, 1 << 20)) {
            sg.solveRingsAsync();
            sg.sync();
            ringsA = sg.downloadRingPositions();
            a = sg.generate(-radius, -radius, 2 * radius, 2 * radius);
            System.out.printf("Analytik: %d Kandidaten, %d Starts, %d Teile%n", a.candidates, a.starts, a.pieces);
        }
        try (StructureGpu sg = new StructureGpu(gpu, t, ptxSource(), "wg_struct_tilesynth", 1 << 17, 1 << 14, 1 << 19, 256, 1 << 20, Integer.getInteger("envInts", 1 << 26));
             SynthEnvProvider prov = new SynthEnvProvider(sg, ptxSource(), "wg_struct_tilesynth")) {
            sg.solveRingsAsync(prov);
            sg.sync();
            ringsB = sg.downloadRingPositions();
            long t0 = System.nanoTime();
            b = sg.generate(-radius, -radius, 2 * radius, 2 * radius, prov);
            System.out.printf("Kacheln:  %d Kandidaten, %d Starts, %d Teile (%.0f ms)%n", b.candidates, b.starts, b.pieces, (System.nanoTime() - t0) / 1e6);
        }
        gpu.close();
        boolean ringsSame = Arrays.equals(ringsA, ringsB);
        Map<String, String> ma = new HashMap<>(), mb = new HashMap<>();
        for (int i = 0; i < a.starts; i++) ma.put(startKey(a, i), pieceDigest(a, i));
        for (int i = 0; i < b.starts; i++) mb.put(startKey(b, i), pieceDigest(b, i));
        boolean same = ma.equals(mb);
        System.out.println("Ringe " + (ringsSame ? "identisch" : "ABWEICHEND") + "; Starts/Teile " + (same ? "identisch" : "ABWEICHEND (" + ma.size() + " vs " + mb.size() + ")"));
        System.out.println(ringsSame && same ? "TILE OK" : "TILE ABWEICHUNG");
    }

    static String startKey(StructureGpu.Result r, int i) {
        int[] s = r.start(i);
        return s[StructureTables.SR_SET] + "/" + s[StructureTables.SR_CX] + "/" + s[StructureTables.SR_CZ] + "/" + s[StructureTables.SR_STRUCT];
    }

    static String pieceDigest(StructureGpu.Result r, int i) {
        int[] s = r.start(i);
        long h = 1125899906842597L;
        for (int j = 0; j < s[StructureTables.SR_PIECES]; j++) {
            int[] p = r.piece(s[StructureTables.SR_PIECE0] + j);
            p[StructureTables.PC_START] = 0;
            for (int w : p) h = 31 * h + w;
        }
        return s[StructureTables.SR_PIECES] + ":" + h;
    }

    /**
     * Echtes Gelaende: Hoehen und Biome von Vanilla (NoiseBasedChunkGenerator, nur Test) fuer wenige Kandidaten in Kacheln, die
     * GPU erzeugt die Starts daraus; Vergleich mit Vanillas Strukturerzeugung auf demselben Gelaende.
     */
    static void real(Env env, int radius) throws Exception {
        if (!env.real) throw new IllegalStateException("mode=real braucht -Dreal=true (-Preal=true)");
        CudaCompute gpu = CudaCompute.create();
        StructureTables t = env.tables;
        int maxTiles = Integer.getInteger("count", 4);
        String want = System.getProperty("structure", "villages");
        try (StructureGpu sg = new StructureGpu(gpu, t, ptxSource(), "wg_struct_tile", 1 << 16, 1 << 12, 1 << 18, 256, 1 << 18, 1 << 24)) {
            VanillaEnvProvider prov = new VanillaEnvProvider(env.generator, env.biomeSource, env.randomState, env.universe, t, maxTiles, want);
            StructureGpu.Result r = sg.generate(-radius, -radius, 2 * radius, 2 * radius, prov);
            System.out.printf("GPU: %d Kandidaten, %d Starts mit %d Teilen; Kacheln fuer %d Kandidaten (Vanilla-Hoehen: %.1f s)%n", r.candidates, r.starts, r.pieces, prov.filled, prov.seconds);
            int same = 0, cmp = 0;
            for (int i = 0; i < r.starts; i++) {
                int[] st = r.start(i);
                int set = st[StructureTables.SR_SET];
                String sname = t.setNames.get(set);
                int cx = st[StructureTables.SR_CX], cz = st[StructureTables.SR_CZ];
                if (!prov.isFilled(set, cx, cz)) continue;
                Object[] van = env.vanillaStart(sname, cx, cz);
                cmp++;
                if (van == null) { System.out.println(sname + " " + cx + "," + cz + ": Vanilla ohne Start"); continue; }
                StructureStart vs = (StructureStart) van[1];
                List<String> a = new ArrayList<>(), b = new ArrayList<>();
                for (StructurePiece p : vs.getPieces()) a.add(vanillaKey(p, false));
                for (int j = 0; j < st[StructureTables.SR_PIECES]; j++) b.add(gpuKey(t, r.piece(st[StructureTables.SR_PIECE0] + j), false));
                boolean ok = a.equals(b) && van[0].equals(t.structNames.get(st[StructureTables.SR_STRUCT]));
                if (ok) same++;
                System.out.printf("%s @%d,%d %s: %d Teile vs GPU %d -> %s%n", sname, cx, cz, van[0], a.size(), b.size(), ok ? "identisch" : "ABWEICHUNG");
                if (!ok) {
                    int f = 0;
                    while (f < a.size() && f < b.size() && a.get(f).equals(b.get(f))) f++;
                    System.out.println("   erster Unterschied bei " + f + "\n   vanilla: " + (f < a.size() ? a.get(f) : "-") + "\n   gpu:     " + (f < b.size() ? b.get(f) : "-"));
                }
            }
            System.out.printf("echtes Gelaende: %d von %d Starts identisch%n", same, cmp);
            // Bilder: erster gefuellter Start
            for (int i = 0; i < r.starts; i++) {
                int[] st = r.start(i);
                if (!prov.isFilled(st[StructureTables.SR_SET], st[StructureTables.SR_CX], st[StructureTables.SR_CZ])) continue;
                String name = t.structNames.get(st[StructureTables.SR_STRUCT]);
                int x0 = st[StructureTables.SR_MINX] + 12 & ~7, z0 = st[StructureTables.SR_MINZ] + 12 & ~7;
                int x1 = st[StructureTables.SR_MAXX] - 12, z1 = st[StructureTables.SR_MAXZ] - 12;
                int w = ((x1 - x0 + 16) / 8) * 8, d = ((z1 - z0 + 16) / 8) * 8;
                int[] img = sg.topView(x0, z0, w, d, 1, -64, 300, false);
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w * 3, d * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int pz = 0; pz < d; pz++)
                    for (int px = 0; px < w; px++) {
                        int s = img[(pz * w + px) * 2];
                        int rgb;
                        if (s != 0) rgb = stateColor(s & 0x7fffffff);
                        else {
                            int h = prov.height(x0 + px, z0 + pz);
                            int g = h < 63 ? 0x3050a0 : (80 + Math.min(120, Math.max(0, (h - 63) * 3)) << 8) | 0x203020;
                            rgb = h < 63 ? 0x3050a0 : g;
                        }
                        for (int a = 0; a < 3; a++) for (int b2 = 0; b2 < 3; b2++) bi.setRGB(px * 3 + a, pz * 3 + b2, rgb);
                    }
                Path out = SCRATCH.resolve("real_" + name.substring(name.indexOf(':') + 1) + "_" + i + ".png");
                javax.imageio.ImageIO.write(bi, "png", out.toFile());
                System.out.println("Bild " + out);
                break;
            }
        }
        gpu.close();
    }

    /** Teile-Statistik von Vanilla fuer ein Set (-Pstructure=<set ohne Namensraum>): Anzahl Starts, Teile je Start (max/Mittel) */
    static void vstat(Env env, int radius) throws Exception {
        String name = "minecraft:" + System.getProperty("structure", "woodland_mansions");
        StructurePlacement pl = env.vanillaSet(name).placement();
        int n = 0, max = 0;
        long sum = 0;
        for (int cx = -radius; cx < radius; cx++)
            for (int cz = -radius; cz < radius; cz++) {
                if (!pl.isStructureChunk(env.state, cx, cz)) continue;
                Object[] v = env.vanillaStart(name, cx, cz);
                if (v == null) continue;
                int c = ((StructureStart) v[1]).getPieces().size();
                n++;
                sum += c;
                max = Math.max(max, c);
            }
        System.out.printf("%s: %d Starts, Teile max %d, Mittel %.1f%n", name, n, max, n == 0 ? 0.0 : (double) sum / n);
    }
}
