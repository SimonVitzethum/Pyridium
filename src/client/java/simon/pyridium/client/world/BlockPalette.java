package simon.pyridium.client.world;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import simon.pyridium.client.mixin.SpriteContentsAccessor;

/**
 * Blockzustand -> Pyrit-Voxel. Materialien entstehen aus den Vanilla-Texturen
 * (Ober- und Seitenflaeche des gebackenen Blockmodells); beleuchtet wird allein
 * mit Pyrits Shading. Attribut: Material in Bits 0-7, sRGB-Farbe in 8-31
 * (Tint fuer Gras/Laub, sonst Weiss, damit die Textur ihre Farbe traegt).
 */
public final class BlockPalette {
	private static final Logger LOGGER = LogUtils.getLogger();
	static final int SOLID = 1, OPAQUE = 2, WATER = 4, TINT_GRASS = 8, TINT_FOLIAGE = 16;
	// Flags von pyd_material_define
	static final int F_CUTOUT = 1, F_WATER = 2, F_SIDE_OWN = 4, F_EMISSIVE = 8, F_GLASS = 16, F_NO_TEX = 32, F_NATURAL = 64, F_TREE = 128,
		F_SNOW = 256, F_GRASS = 512;
	static final int MAT_WATER = 1, MAT_FALLBACK = 255;
	static final int MAX_MATERIALS = 254;

	/** Attribut je Zustands-ID (nach upload) */
	static int[] ATTR = new int[0];

	private BlockPalette() {
	}

	public static int attrOf(BlockState s) {
		prepare();
		int id = Block.getId(s);
		return id >= 0 && id < ATTR.length ? ATTR[id] : 0;
	}

	private record Face(int[] rgba, boolean tinted) {
	}

	private record MatKey(String top, String side, int flags) {
	}

	private record MatDef(int index, int[] top, int[] side, int flags, float rough, float emission) {
	}

	private static final List<MatDef> DEFS = new ArrayList<>();
	private static int[] FLAGS = new int[0];
	private static volatile boolean prepared;

	/** Zuordnung und Texturen berechnen (ohne GPU); darf frueh und auf einem anderen Thread laufen. */
	public static synchronized void prepare() {
		if (prepared) {
			return;
		}
		long t0 = System.nanoTime();
		int n = Block.BLOCK_STATE_REGISTRY.size();
		IntBuffer attr = MemoryUtil.memAllocInt(n);
		IntBuffer flags = MemoryUtil.memAllocInt(n);
		DEFS.clear();
		Map<MatKey, Integer> materials = new HashMap<>();
		int[] nextMat = {2};
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		DEFS.add(new MatDef(MAT_WATER, null, null, F_WATER, 0.04f, 0f));
		DEFS.add(new MatDef(MAT_FALLBACK, null, null, F_NO_TEX, 0.85f, 0f));
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
							long r = material(s, models, materials, nextMat, leaves, glass, lava);
							a = (int)r;
							// getoente Oberseite: Biomfarbe je Spalte (Sektions-Kern)
							if (a >>> 8 != 0xFFFFFF && (a & 0xff) != MAT_FALLBACK) {
								if (leaves && !s.is(Blocks.BIRCH_LEAVES) && !s.is(Blocks.SPRUCE_LEAVES)) {
									f |= TINT_FOLIAGE;
								} else if (!leaves) {
									f |= TINT_GRASS;
								}
							}
							if ((a & 0xff) == MAT_FALLBACK) {
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
			prepared = true;
			LOGGER.info("Pyridium: {} Materialien aus Vanilla-Texturen, {} Zustaende texturiert, {} einfarbig ({} ms)", nextMat[0] - 2, textured, fallback,
				(System.nanoTime() - t0) / 1000000);
		} finally {
			MemoryUtil.memFree(attr);
			MemoryUtil.memFree(flags);
		}
	}

	/** Materialien und Zustandstabellen an Pyrit geben (Render-Thread, Welt existiert) */
	public static void upload() {
		prepare();
		for (MatDef d : DEFS) {
			if (d.top == null) {
				Native.materialDefine(d.index, 0L, 0L, 0, 0, d.flags, d.rough, d.emission);
				continue;
			}
			ByteBuffer t = rgba(d.top, 16), sd = rgba(d.side, 16);
			try {
				Native.materialDefine(d.index, MemoryUtil.memAddress(t), MemoryUtil.memAddress(sd), 16, 16, d.flags, d.rough, d.emission);
			} finally {
				MemoryUtil.memFree(t);
				MemoryUtil.memFree(sd);
			}
		}
		int n = ATTR.length;
		IntBuffer a = MemoryUtil.memAllocInt(n), f = MemoryUtil.memAllocInt(n);
		try {
			a.put(0, ATTR);
			f.put(0, FLAGS);
			Native.secStates(MemoryUtil.memAddress(a), MemoryUtil.memAddress(f), n);
		} finally {
			MemoryUtil.memFree(a);
			MemoryUtil.memFree(f);
		}
	}

	/** Material (und Attribut) fuer einen festen Blockzustand */
	private static long material(BlockState s, BlockStateModelSet models, Map<MatKey, Integer> materials, int[] next, boolean leaves, boolean glass,
		boolean lava) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		models.get(s).collectParts(RandomSource.create(42L), parts);
		Face top = face(parts, Direction.UP);
		if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)
			&& s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY)) {
			List<BlockStateModelPart> snow = new ArrayList<>();
			models.get(Blocks.SNOW_BLOCK.defaultBlockState()).collectParts(RandomSource.create(42L), snow);
			Face st = face(snow, Direction.UP);
			if (st != null) {
				top = st;
			}
		}
		Face side = face(parts, Direction.NORTH);
		if (side == null) {
			side = face(parts, Direction.EAST);
		}
		if (top == null) {
			top = side;
		}
		if (side == null) {
			side = top;
		}
		if (top == null) {
			TextureAtlasSprite p = models.getParticleMaterial(s).sprite();
			int[] img = pixels(p);
			top = side = new Face(img, false);
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
		// Seite mit getoentem Overlay (Grasblock): Tint einbacken, eigene Farbe
		int[] sideImg = side.rgba;
		if (side.tinted && !top.tinted) {
			sideImg = tintImage(side.rgba, tint);
		} else if (side.tinted && top.tinted && side.rgba != top.rgba && !leaves) {
			sideImg = tintImage(side.rgba, tint);
			flags |= F_SIDE_OWN;
		}
		if (side.tinted && !top.tinted) {
			flags |= F_SIDE_OWN;
		}
		String topKey = hash(top.rgba), sideKey = hash(sideImg);
		MatKey key = new MatKey(topKey, sideKey, flags);
		Integer mat = materials.get(key);
		if (mat == null) {
			if (next[0] > MAX_MATERIALS) {
				return attr(MAT_FALLBACK, average(top.rgba, top.tinted ? tint : 0xFFFFFF));
			}
			mat = next[0]++;
			define(mat, top.rgba, sideImg, flags, emission > 0 || lava ? Math.max(emission, 12) / 15f * 4f : 0f, leaves);
			materials.put(key, mat);
		}
		int color = top.tinted ? tint : 0xFFFFFF;
		return attr(mat, color);
	}

	private static void define(int mat, int[] top, int[] side, int flags, float emission, boolean leaves) {
		float rough = leaves ? 0.88f : (flags & F_NATURAL) != 0 ? 0.9f : 0.85f;
		DEFS.add(new MatDef(mat, top, side, flags, rough, emission));
	}

	/**
	 * 16x16 RGBA8 (Zeile 0 oben). Pyrit liest nur RGB: durchsichtige Texel
	 * (Laub, Glas) bekaemen sonst ihre schwarze Fuellfarbe. Sie erhalten die
	 * mittlere Farbe der deckenden Texel; Loecher macht Pyrits Cutout.
	 */
	private static ByteBuffer rgba(int[] argb, int w) {
		int avg = average(argb, 0xFFFFFF);
		argb = argb.clone();
		for (int i = 0; i < argb.length; i++) {
			if ((argb[i] >>> 24) < 128) {
				argb[i] = 0xff000000 | avg;
			}
		}
		ByteBuffer b = MemoryUtil.memAlloc(w * w * 4);
		for (int i = 0; i < w * w; i++) {
			int c = argb[i];
			b.put(i * 4, (byte)(c >> 16));
			b.put(i * 4 + 1, (byte)(c >> 8));
			b.put(i * 4 + 2, (byte)c);
			b.put(i * 4 + 3, (byte)(c >>> 24));
		}
		return b;
	}

	/** Flaeche aus den Quads einer Richtung, uebereinander gelegt (Overlays) */
	private static Face face(List<BlockStateModelPart> parts, Direction d) {
		int[] img = null;
		boolean tinted = false;
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> quads = new ArrayList<>(part.getQuads(d));
			for (BakedQuad q : part.getQuads(null)) {
				if (q.direction() == d) {
					quads.add(q);
				}
			}
			for (BakedQuad q : quads) {
				int[] px = pixels(q.materialInfo().sprite());
				boolean qt = q.materialInfo().tintIndex() >= 0;
				if (img == null) {
					img = px.clone();
					tinted = qt;
				} else {
					// Overlay: getoent (Grasrand) ueber die Basis legen
					int[] src = qt ? tintImage(px, GrassColor.getDefaultColor()) : px;
					for (int i = 0; i < img.length; i++) {
						int sa = src[i] >>> 24;
						if (sa > 0) {
							img[i] = blend(img[i], src[i], sa / 255f);
						}
					}
					if (qt) {
						tinted = true;
					}
				}
			}
		}
		return img == null ? null : new Face(img, tinted);
	}

	private static int blend(int dst, int src, float a) {
		int r = Math.round(((src >> 16) & 255) * a + ((dst >> 16) & 255) * (1 - a));
		int g = Math.round(((src >> 8) & 255) * a + ((dst >> 8) & 255) * (1 - a));
		int b = Math.round((src & 255) * a + (dst & 255) * (1 - a));
		return (dst & 0xff000000) | r << 16 | g << 8 | b;
	}

	/** Erstes Bild eines Sprites als 16x16 ARGB (groessere Texturen werden abgetastet) */
	private static int[] pixels(TextureAtlasSprite sprite) {
		SpriteContents c = sprite.contents();
		NativeImage img = ((SpriteContentsAccessor)c).pyridium$originalImage();
		int fw = c.width(), fh = c.height();
		int[] out = new int[256];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				out[y * 16 + x] = img.getPixel(x * fw / 16, y * fh / 16);
			}
		}
		return out;
	}

	private static int[] tintImage(int[] argb, int tint) {
		int[] o = new int[argb.length];
		float tr = ((tint >> 16) & 255) / 255f, tg = ((tint >> 8) & 255) / 255f, tb = (tint & 255) / 255f;
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			o[i] = (c & 0xff000000) | Math.round(((c >> 16) & 255) * tr) << 16 | Math.round(((c >> 8) & 255) * tg) << 8 | Math.round((c & 255) * tb);
		}
		return o;
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

	private static int average(int[] argb, int tint) {
		long r = 0, g = 0, b = 0, n = 0;
		for (int c : argb) {
			if ((c >>> 24) < 16) {
				continue;
			}
			r += (c >> 16) & 255;
			g += (c >> 8) & 255;
			b += c & 255;
			n++;
		}
		if (n == 0) {
			return 0x808080;
		}
		int ir = (int)(r / n * ((tint >> 16) & 255) / 255), ig = (int)(g / n * ((tint >> 8) & 255) / 255), ib = (int)(b / n * (tint & 255) / 255);
		return ir << 16 | ig << 8 | ib;
	}

	private static String hash(int[] px) {
		return Integer.toHexString(java.util.Arrays.hashCode(px)) + ":" + px.length;
	}

	private static int attr(int mat, int rgb) {
		return (mat & 0xff) | (rgb & 0xffffff) << 8;
	}
}
