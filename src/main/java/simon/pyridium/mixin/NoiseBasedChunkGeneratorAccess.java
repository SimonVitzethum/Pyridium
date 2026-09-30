package simon.pyridium.mixin;

import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Zugriff auf die privaten Terrain-Schritte des Generators (GPU-Pfad, Selbsttest, Carver). */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccess {
    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> pyridium$fluidPicker();

    @Invoker("createNoiseChunk")
    NoiseChunk pyridium$createNoiseChunk(ChunkAccess chunk, StructureManager structureManager, Blender blender, RandomState randomState,
                                           NoiseSettings noiseSettings);

    @Invoker("doFill")
    void pyridium$doFill(NoiseChunk noiseChunk, ChunkAccess chunk);

    @Invoker("buildSurface")
    void pyridium$buildSurface(ChunkAccess protoChunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager,
                                 Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule);

    @Invoker("generateCarvers")
    void pyridium$generateCarvers(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk, RandomState randomState,
                                    BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion, MaterialRule materialRule);
}
