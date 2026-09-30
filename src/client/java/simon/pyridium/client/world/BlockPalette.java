package simon.pyridium.client.world;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import simon.pyridium.client.gpu.Native;

/**
 * Blockzustand -> Pyrit-Voxel. Materialien entstehen aus den Vanilla-Texturen
 * (sechs UV-korrekte Flaechen samt aktiven LabPBR-Karten); beleuchtet wird allein
 * mit Pyrits Shading. Attribut: Material in Bits 0-15, RGB565-Tint in 16-31
 * (Tint fuer Gras/Laub, sonst Weiss, damit die Textur ihre Farbe traegt).
 */
public final class BlockPalette {
	private static final Logger LOGGER = LogUtils.getLogger();
	static final int SOLID = 1, OPAQUE = 2, WATER = 4, TINT_GRASS = 8, TINT_FOLIAGE = 16;
	// Flags von pyd_material_define
	static final int F_CUTOUT = 1, F_WATER = 2, F_SIDE_OWN = 4, F_EMISSIVE = 8, F_GLASS = 16, F_NO_TEX = 32, F_NATURAL = 64, F_TREE = 128,
		F_SNOW = 256, F_GRASS = 512;
    static final int MAT_WATER = 1, MAT_FALLBACK = 4095;
    static final int MAX_MATERIALS = 16383; // 4094/4095 remain reserved

	/** Attribut je Zustands-ID (nach upload) */
	static int[] ATTR = new int[0];

	private BlockPalette() {
	}

	public static int attrOf(BlockState s) {
		prepare();
		int id = Block.getId(s);
		if (id < 0 || id >= ATTR.length) return 0;
        requireState(id);
        return ATTR[id];
	}

	private record MatKey(List<BlockMaterialFaces.Face> faces, int flags) {
	}

	private record MatDef(int index, List<BlockMaterialFaces.Face> faces, int flags, float rough, float emission) {
	}

	private static final List<MatDef> DEFS = new ArrayList<>();
    private static final MatDef[] BY_INDEX = new MatDef[MAX_MATERIALS + 1];
    private static final BitSet REQUIRED = new BitSet(MAX_MATERIALS + 1), UPLOADED = new BitSet(MAX_MATERIALS + 1);
	private static int[] FLAGS = new int[0];
	private static volatile boolean prepared;
    private static BlockStateModelSet preparedModels;

    public static boolean resourcesChanged() {
        return prepared && preparedModels != Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }

	/** Zuordnung und Texturen berechnen (ohne GPU); darf frueh und auf einem anderen Thread laufen. */
	public static synchronized void prepare() {
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		if (prepared && preparedModels == models) {
			return;
		}
		long t0 = System.nanoTime();
		int n = Block.BLOCK_STATE_REGISTRY.size();
		IntBuffer attr = MemoryUtil.memAllocInt(n);
		IntBuffer flags = MemoryUtil.memAllocInt(n);
		DEFS.clear();
        REQUIRED.clear(); UPLOADED.clear();
        java.util.Arrays.fill(BY_INDEX, null);
		Map<MatKey, Integer> materials = new HashMap<>();
		int[] nextMat = {2};
		BlockMaterialFaces baker = new BlockMaterialFaces();
		DEFS.add(new MatDef(MAT_WATER, null, F_WATER, 0.04f, 0f));
        DEFS.add(new MatDef(MAT_FALLBACK, null, F_NO_TEX, 0.85f, 0f));
        DEFS.add(new MatDef(4094, null, F_NO_TEX, 0.8f, 0f));
		int textured = 0, fallback = 0;
		try {
			for (int id = 0; id < n; id++) {
				BlockState s = Block.BLOCK_STATE_REGISTRY.byId(id);
				int a = 0, f = 0;
				if (s != null && !s.isAir()) {
					boolean water = s.is(Blocks.WATER);
					boolean leaves = s.is(BlockTags.LEAVES);
					boolean lava = s.getFluidState().is(FluidTags.LAVA) && s.is(Blocks.LAVA);
					boolean full = !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
					boolean thinSnow = s.is(Blocks.SNOW) && s.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) < 8;
					if (thinSnow) {
						// duenne Schneeschicht: der Block darunter traegt den Schnee (snowy=true)
					} else if (water) {
						a = attr(MAT_WATER, 0x1EAAD2);
						f = WATER;
					} else if (full || leaves || lava) {
						f = SOLID;
						boolean glass = s.is(BlockTags.IMPERMEABLE) || s.getBlock().getClass().getSimpleName().contains("Glass");
						if (s.canOcclude() && !leaves && !glass) {
							f |= OPAQUE;
						}
						try {
							long r = material(s, models, baker, materials, nextMat, leaves, glass, lava);
							a = (int)r;
							// getoente Oberseite: Biomfarbe je Spalte (Sektions-Kern)
                            if (a >>> 16 != 0xFFFF && (a & 0xffff) != MAT_FALLBACK) {
								if (leaves && !s.is(Blocks.BIRCH_LEAVES) && !s.is(Blocks.SPRUCE_LEAVES)) {
									f |= TINT_FOLIAGE;
								} else if (!leaves) {
									f |= TINT_GRASS;
								}
							}
                            if ((a & 0xffff) == MAT_FALLBACK) {
								fallback++;
							} else {
								textured++;
							}
						} catch (RuntimeException e) {
							a = attr(MAT_FALLBACK, 0x808080);
							fallback++;
						}
					}
				}
				attr.put(id, a);
				flags.put(id, f);
			}
			int[] copy = new int[n];
			attr.get(0, copy);
			int[] fc = new int[n];
			flags.get(0, fc);
			ATTR = copy;
			FLAGS = fc;
            for (MatDef d : DEFS) BY_INDEX[d.index] = d;
			prepared = true;
			preparedModels = models;
			LOGGER.info("Pyridium: {} Materialien aus Vanilla-Texturen, {} Zustaende texturiert, {} einfarbig ({} ms)", nextMat[0] - 2, textured, fallback,
				(System.nanoTime() - t0) / 1000000);
			LOGGER.info("Pyridium: Ressourcenpakete: {} PBR-Normal- und {} PBR-Materialkarten; sechs UV-korrekte Flächen, maximal {} Texel/Kante",
				baker.normalMaps, baker.specularMaps, Integer.getInteger("pyridium.maxTextureSize", 128));
		} finally {
			MemoryUtil.memFree(attr);
			MemoryUtil.memFree(flags);
		}
	}

	/** Materialien und Zustandstabellen an Pyrit geben (Render-Thread, Welt existiert) */
	public static void upload() {
		prepare();
        resetGpu();
        uploadPending(Integer.MAX_VALUE);
        int n = ATTR.length;
        IntBuffer a = MemoryUtil.memAllocInt(n), f = MemoryUtil.memAllocInt(n);
        try {
            a.put(0, ATTR); f.put(0, FLAGS);
            Native.secStates(MemoryUtil.memAddress(a), MemoryUtil.memAddress(f), n);
        } finally { MemoryUtil.memFree(a); MemoryUtil.memFree(f); }
    }

    /** CPU requests only: worker threads never call into the renderer context. */
    static synchronized void requireState(int stateId) {
        if (stateId >= 0 && stateId < ATTR.length && ATTR[stateId] != 0) REQUIRED.set(ATTR[stateId] & 0xffff);
    }

    public static synchronized void resetGpu() {
        UPLOADED.clear(); REQUIRED.set(MAT_WATER); REQUIRED.set(MAT_FALLBACK); REQUIRED.set(4094);
    }

    /** Upload only referenced materials. An unused 128px state must not evict
     * terrain/targets from VRAM. Shared tiles stay cached until world teardown. */
    static synchronized void uploadPending(int budget) {
        BitSet pending = (BitSet)REQUIRED.clone();
        pending.andNot(UPLOADED);
        int used = 0;
        for (int index = pending.nextSetBit(0); index >= 0 && used < budget; index = pending.nextSetBit(index + 1)) {
            MatDef d = BY_INDEX[index];
            if (d == null) continue;
            uploadMaterial(d);
            UPLOADED.set(index); used++;
        }
    }

    private static void uploadMaterial(MatDef d) {
			if (d.faces == null) {
				Native.materialDefine(d.index, 0L, 0L, 0, 0, d.flags, d.rough, d.emission);
				return;
			}
			// Pack one material at a time. Faces are deduplicated across block
			// states; retaining a whole atlas per state wastes gigabytes for PBR.
			var atlas = BlockMaterialFaces.atlas(d.faces);
			ByteBuffer t = rgba(atlas.color()), nm = atlas.normal() == null ? null : rgba(atlas.normal()), sm = atlas.specular() == null ? null : rgba(atlas.specular());
			try {
				Native.materialFaces(d.index, MemoryUtil.memAddress(t), nm == null ? 0 : MemoryUtil.memAddress(nm), sm == null ? 0 : MemoryUtil.memAddress(sm),
					atlas.size(), d.flags, d.rough, d.emission);
			} finally {
				MemoryUtil.memFree(t);
				if (nm != null) MemoryUtil.memFree(nm);
				if (sm != null) MemoryUtil.memFree(sm);
			}
	}

	/** Material (und Attribut) fuer einen festen Blockzustand */
	private static long material(BlockState s, BlockStateModelSet models, BlockMaterialFaces baker, Map<MatKey, Integer> materials, int[] next, boolean leaves, boolean glass,
		boolean lava) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		models.get(s).collectParts(RandomSource.create(42L), parts);
		List<BlockMaterialFaces.Face> faces = baker.faces(parts, models.getParticleMaterial(s).sprite());
		if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
			&& s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
			List<BlockStateModelPart> snow = new ArrayList<>();
			models.get(Blocks.SNOW_BLOCK.defaultBlockState()).collectParts(RandomSource.create(42L), snow);
			faces.set(3, baker.faces(snow, models.getParticleMaterial(Blocks.SNOW_BLOCK.defaultBlockState()).sprite()).get(3));
		}
		int tint = leaves ? tintLeaves(s) : GrassColor.getDefaultColor();
		int flags = 0;
		if (leaves) {
			flags |= F_CUTOUT;
		}
		if (glass) {
			flags |= F_GLASS;
		}
		// Kategorie wie in der Pyrit-Demo
		if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.MYCELIUM) || s.is(Blocks.PODZOL) || s.is(Blocks.MOSS_BLOCK)) {
			flags |= F_GRASS;
		} else if (leaves || s.is(BlockTags.LOGS)) {
			flags |= F_TREE;
		} else if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW) || s.is(BlockTags.ICE)) {
			flags |= F_SNOW;
		} else if (s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
			|| s.is(Blocks.SANDSTONE) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CLAY) || s.is(Blocks.MUD)) {
			flags |= F_NATURAL;
		}
		int emission = s.getLightEmission();
		if (emission > 0 || lava) {
			flags |= F_EMISSIVE;
		}
		boolean tinted = faces.stream().anyMatch(BlockMaterialFaces.Face::tinted);
		if (!glass && faces.stream().anyMatch(BlockMaterialFaces.Face::cutout)) flags |= F_CUTOUT;
		MatKey key = new MatKey(List.copyOf(faces), flags);
		Integer mat = materials.get(key);
		if (mat == null) {
			if (next[0] == 4094) next[0] = 4096;
			if (next[0] > MAX_MATERIALS) {
                throw new IllegalStateException("Minecraft-Materialpalette überschreitet " + MAX_MATERIALS);
			}
			mat = next[0]++;
			float rough = leaves ? .88f : (flags & F_NATURAL) != 0 ? .9f : .85f;
			DEFS.add(new MatDef(mat, key.faces, flags, rough,
				emission > 0 || lava ? Math.max(emission, 12) / 15f * 4f : 0f));
			materials.put(key, mat);
		}
		int color = tinted ? tint : 0xFFFFFF;
		return attr(mat, color);
	}

	/** RGBA8, alpha remains intact; native upload flips each face separately. */
	private static ByteBuffer rgba(int[] argb) {
		ByteBuffer b = MemoryUtil.memAlloc(argb.length * 4);
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			b.put(i * 4, (byte)(c >> 16));
			b.put(i * 4 + 1, (byte)(c >> 8));
			b.put(i * 4 + 2, (byte)c);
			b.put(i * 4 + 3, (byte)(c >>> 24));
		}
		return b;
	}

	private static int tintLeaves(BlockState s) {
		if (s.is(Blocks.BIRCH_LEAVES)) {
			return 0x80A755;
		}
		if (s.is(Blocks.SPRUCE_LEAVES)) {
			return 0x619961;
		}
		if (s.is(Blocks.CHERRY_LEAVES) || s.is(Blocks.AZALEA_LEAVES) || s.is(Blocks.FLOWERING_AZALEA_LEAVES) || s.is(Blocks.PALE_OAK_LEAVES)) {
			return 0xFFFFFF;
		}
		return FoliageColor.FOLIAGE_DEFAULT & 0xFFFFFF;
	}

    static int attr(int mat, int rgb) {
        int color = ((rgb >>> 19) & 31) << 11 | ((rgb >>> 10) & 63) << 5 | ((rgb >>> 3) & 31);
        return (mat & 0xffff) | color << 16;
	}
}
