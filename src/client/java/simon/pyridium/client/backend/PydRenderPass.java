package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import simon.pyridium.client.gpu.Native;

/**
 * Ein Render-Pass. GUI-Draws werden auf der CPU in 2D-Sprites übersetzt und
 * am Ende des Passes vom Compositor (CUDA) ins Farbziel geblendet. Draws, die
 * keine GUI-Sprites sind, werden ausgelassen: die Welt zeichnet Pyrit.
 */
public class PydRenderPass implements RenderPassBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Set<String> SKIPPED = ConcurrentHashMap.newKeySet();
	static final int SPRITE = 112;
	static final int TEX = 32;

	private final @Nullable PydTexture color;
	private final long colorPtr;
	private final int tw, th;
	private final int areaX, areaY, areaW, areaH;
	private int scX, scY, scW, scH;
	private @Nullable PydPipeline pipeline;
	private final Object[] uniforms = new Object[32];
	private final GpuBufferSlice[] vertex = new GpuBufferSlice[4];
	private @Nullable GpuBuffer index;
	private IndexType indexType = IndexType.SHORT;

	// Sprite-Liste (wächst), Texturtabelle
	private static ByteBuffer sprites = MemoryUtil.memAlloc(SPRITE * 1024);
	private static ByteBuffer texs = MemoryUtil.memAlloc(TEX * 64);
	private static final ByteBuffer region = MemoryUtil.memAlloc(16);
	private int count;
	private final Map<Object, Integer> texIndex = new java.util.HashMap<>();
	private int rx0 = Integer.MAX_VALUE, ry0 = Integer.MAX_VALUE, rx1 = Integer.MIN_VALUE, ry1 = Integer.MIN_VALUE;

	public PydRenderPass(RenderPassDescriptor d) {
		PydTexture c = null;
		PydTextureView cv0 = null;
		for (RenderPassDescriptor.Attachment<java.util.Optional<org.joml.Vector4fc>> a : d.colorAttachments()) {
			if (a != null) {
				c = ((PydTextureView)a.textureView()).pyd();
				PydTextureView v = (PydTextureView)a.textureView();
				cv0 = v;
				a.clearValue().ifPresent(cv -> PydCommandEncoder.fillColor(c(v), v.baseMipLevel(), cv, 0, 0, v.getWidth(0), v.getHeight(0)));
				break;
			}
		}
		if (d.depthAttachment() != null) {
			PydTextureView dv = (PydTextureView)d.depthAttachment().textureView();
			d.depthAttachment().clearValue().ifPresent(v -> PydCommandEncoder.fillDepth(dv.pyd(), v));
		}
		this.color = c != null && c.getFormat() == GpuFormat.RGBA8_UNORM ? c : null;
		int lvl = cv0 != null ? cv0.baseMipLevel() : 0;
		this.colorPtr = c != null ? c.levelLayer(lvl, 0) : 0L;
		this.tw = c != null ? c.getWidth(lvl) : 0;
		this.th = c != null ? c.getHeight(lvl) : 0;
		var ra = d.renderArea();
		this.areaX = ra.x();
		this.areaY = ra.y();
		this.areaW = ra.width();
		this.areaH = ra.height();
		this.disableScissor();
	}

	private static PydTexture c(PydTextureView v) {
		return v.pyd();
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline p) {
		this.pipeline = (PydPipeline)p;
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		if (index >= 0 && index < this.uniforms.length) {
			this.uniforms[index] = value;
		}
	}

	@Override
	public void pushConstants(ByteBuffer value) {
	}

	@Override
	public void enableScissor(int x, int y, int w, int h) {
		this.scX = x;
		this.scY = y;
		this.scW = w;
		this.scH = h;
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.areaX, this.areaY, this.areaW, this.areaH);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vb) {
		if (slot < this.vertex.length) {
			this.vertex[slot] = vb;
		}
	}

	@Override
	public void setIndexBuffer(GpuBuffer ib, IndexType type) {
		this.index = ib;
		this.indexType = type;
	}

	// --- Draws ------------------------------------------------------------

	private boolean usable() {
		PydPipeline p = this.pipeline;
		if (p == null || this.color == null) {
			return false;
		}
		if (p.kind == PydPipeline.Kind.SKIP) {
			if (SKIPPED.add(p.name)) {
				LOGGER.info("Pyridium: Pipeline {} wird nicht gezeichnet (keine GUI-Sprites)", p.name);
			}
			return false;
		}
		if (p.kind == PydPipeline.Kind.SPRITE_BLIT || p.kind == PydPipeline.Kind.SPRITE_INTERP) {
			return this.uniforms[p.animIndex] instanceof GpuBufferSlice;
		}
		return this.vertex[0] != null && this.uniforms[p.projIndex] instanceof GpuBufferSlice;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (!this.usable() || this.index == null) {
			return;
		}
		long ib = ((PydBuffer)this.index).address();
		int isz = this.indexType.bytes;
		for (int q = 0; q + 6 <= indexCount; q += 6) {
			long base = ib + (long)(firstIndex + q) * isz;
			int a = idx(base, 0, isz) + vertexOffset;
			int b = idx(base, 1, isz) + vertexOffset;
			int c = idx(base, 2, isz) + vertexOffset;
			int d = idx(base, 4, isz) + vertexOffset;
			this.quad(a, b, c, d);
		}
	}

	private static int idx(long base, int i, int isz) {
		return isz == 2 ? MemoryUtil.memGetShort(base + 2L * i) & 0xffff : MemoryUtil.memGetInt(base + 4L * i);
	}

	@Override
	public void multiDrawIndexed(IntBuffer p, int instanceCount, int firstInstance, int drawCount) {
		for (int i = 0; i < drawCount; i++) {
			int o = p.position() + i * 3;
			this.drawIndexed(p.get(o), instanceCount, p.get(o + 1), p.get(o + 2), firstInstance);
		}
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		int isz = this.indexType.bytes;
		for (int i = 0; i < drawCount; i++) {
			this.drawIndexed(indexCounts.get(indexCounts.position() + i), 1, (int)(firstIndexOffsets.get(firstIndexOffsets.position() + i) / isz),
				vertexOffsets.get(vertexOffsets.position() + i), 0);
		}
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.usable()) {
			return;
		}
		if (this.pipeline.kind == PydPipeline.Kind.SPRITE_BLIT || this.pipeline.kind == PydPipeline.Kind.SPRITE_INTERP) {
			this.animSprite(firstVertex >> 3);
			return;
		}
		for (int q = 0; q + 4 <= vertexCount; q += 4) {
			int v = firstVertex + q;
			this.quad(v, v + 1, v + 2, v + 3);
		}
	}

	@Override
	public void multiDraw(IntBuffer p, int instanceCount, int firstInstance, int drawCount) {
		for (int i = 0; i < drawCount; i++) {
			int o = p.position() + i * 2;
			this.draw(p.get(o), instanceCount, p.get(o + 1), firstInstance);
		}
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		for (int i = 0; i < drawCount; i++) {
			this.draw(vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
		}
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	// --- Sprites ----------------------------------------------------------

	private final float[] mvp = new float[16];
	private final float[] mod = new float[4];

	private void quad(int a, int b, int c, int d) {
		PydPipeline p = this.pipeline;
		GpuBufferSlice vb = this.vertex[0];
		long vbase = ((PydBuffer)vb.buffer()).address() + vb.offset();
		long vend = vbase + vb.length();
		int stride = p.stride;
		int[] vs = {a, b, c, d};
		for (int v : vs) {
			if (v < 0 || vbase + (long)(v + 1) * stride > vend) {
				return;
			}
		}
		this.prepareTransforms();
		if (this.count * SPRITE + SPRITE > sprites.capacity()) {
			sprites = MemoryUtil.memRealloc(sprites, sprites.capacity() * 2);
		}
		long s = MemoryUtil.memAddress(sprites) + (long)this.count * SPRITE;
		float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE;
		for (int k = 0; k < 4; k++) {
			long vp = vbase + (long)vs[k] * stride;
			float x = MemoryUtil.memGetFloat(vp + p.posOffset);
			float y = MemoryUtil.memGetFloat(vp + p.posOffset + 4);
			float z = MemoryUtil.memGetFloat(vp + p.posOffset + 8);
			float[] m = this.mvp;
			float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
			float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
			float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
			if (cw <= 1e-6f) {
				return;
			}
			float px = (cx / cw * 0.5f + 0.5f) * this.tw;
			float py = (cy / cw * 0.5f + 0.5f) * this.th;
			MemoryUtil.memPutFloat(s + 4L * k, px);
			MemoryUtil.memPutFloat(s + 16 + 4L * k, py);
			minx = Math.min(minx, px);
			maxx = Math.max(maxx, px);
			miny = Math.min(miny, py);
			maxy = Math.max(maxy, py);
			float u = 0, v = 0;
			if (p.uvOffset >= 0) {
				u = MemoryUtil.memGetFloat(vp + p.uvOffset);
				v = MemoryUtil.memGetFloat(vp + p.uvOffset + 4);
			}
			MemoryUtil.memPutFloat(s + 32 + 4L * k, u);
			MemoryUtil.memPutFloat(s + 48 + 4L * k, v);
			int col = p.colorOffset >= 0 ? MemoryUtil.memGetInt(vp + p.colorOffset) : 0xffffffff;
			if (p.kind == PydPipeline.Kind.ITEM) {
				col = this.itemLight(vp, col);
			}
			MemoryUtil.memPutInt(s + 64 + 4L * k, modulate(col, this.mod));
		}
		if (p.cull && p.kind == PydPipeline.Kind.ITEM) {
			// Rueckseiten weglassen (Maler-Reihenfolge ohne Tiefenpuffer)
			float ax = MemoryUtil.memGetFloat(s), ay = MemoryUtil.memGetFloat(s + 16);
			float bx = MemoryUtil.memGetFloat(s + 4), by = MemoryUtil.memGetFloat(s + 20);
			float cx2 = MemoryUtil.memGetFloat(s + 8), cy2 = MemoryUtil.memGetFloat(s + 24);
			float dx = MemoryUtil.memGetFloat(s + 12), dy = MemoryUtil.memGetFloat(s + 28);
			float area = (ax * by - bx * ay) + (bx * cy2 - cx2 * by) + (cx2 * dy - dx * cy2) + (dx * ay - ax * dy);
			if (area <= 0f) {
				return;
			}
		}
		int bx0 = Math.max((int)Math.floor(minx), this.scX);
		int by0 = Math.max((int)Math.floor(miny), this.scY);
		int bx1 = Math.min((int)Math.ceil(maxx) + 1, this.scX + this.scW);
		int by1 = Math.min((int)Math.ceil(maxy) + 1, this.scY + this.scH);
		bx0 = Math.max(bx0, 0);
		by0 = Math.max(by0, 0);
		bx1 = Math.min(bx1, this.tw);
		by1 = Math.min(by1, this.th);
		if (bx1 <= bx0 || by1 <= by0) {
			return;
		}
		MemoryUtil.memPutInt(s + 80, bx0);
		MemoryUtil.memPutInt(s + 84, by0);
		MemoryUtil.memPutInt(s + 88, bx1);
		MemoryUtil.memPutInt(s + 92, by1);
		int tex = 0;
		if (p.kind != PydPipeline.Kind.COLOR && p.samplerIndex >= 0 && this.uniforms[p.samplerIndex] instanceof TextureViewAndSampler ts) {
			tex = this.texture(ts) + 1;
			if (tex == 0) {
				return;
			}
		}
		MemoryUtil.memPutInt(s + 96, tex);
		int mode = switch (p.kind) {
			case COLOR -> 0;
			case TEX -> 1;
			case TEXT -> 2;
			case TEXT_GRAY -> 3;
			case ITEM -> 2;
			default -> 0;
		};
		MemoryUtil.memPutInt(s + 100, mode);
		MemoryUtil.memPutInt(s + 104, p.blend);
		float x0 = MemoryUtil.memGetFloat(s), x1 = MemoryUtil.memGetFloat(s + 4), x2 = MemoryUtil.memGetFloat(s + 8), x3 = MemoryUtil.memGetFloat(s + 12);
		float y0 = MemoryUtil.memGetFloat(s + 16), y1 = MemoryUtil.memGetFloat(s + 20), y2 = MemoryUtil.memGetFloat(s + 24), y3 = MemoryUtil.memGetFloat(s + 28);
		boolean rect = (x0 == x1 && x2 == x3 && y0 == y3 && y1 == y2) || (y0 == y1 && y2 == y3 && x0 == x3 && x1 == x2);
		MemoryUtil.memPutInt(s + 108, rect ? 1 : 0);
		this.count++;
		this.rx0 = Math.min(this.rx0, bx0);
		this.ry0 = Math.min(this.ry0, by0);
		this.rx1 = Math.max(this.rx1, bx1);
		this.ry1 = Math.max(this.ry1, by1);
	}

	/** animate_sprite: Sprite (oder Überblendung zweier Bilder) in einen Atlas kopieren */
	private void animSprite(int progress) {
		PydPipeline p = this.pipeline;
		GpuBufferSlice ubo = (GpuBufferSlice)this.uniforms[p.animIndex];
		long u = ((PydBuffer)ubo.buffer()).address() + ubo.offset();
		float[] proj = readMat(ubo, 0);
		float[] spr = readMat(ubo, 64);
		float pu = MemoryUtil.memGetFloat(u + 128), pv = MemoryUtil.memGetFloat(u + 132);
		int mip = MemoryUtil.memGetInt(u + 136);
		if (!(this.uniforms[p.samplerIndex] instanceof TextureViewAndSampler t0)) {
			return;
		}
		int tex = this.texture(t0, mip) + 1;
		if (tex == 0) {
			return;
		}
		int tex2 = 0;
		if (p.kind == PydPipeline.Kind.SPRITE_INTERP) {
			if (!(this.uniforms[p.sprite2Index] instanceof TextureViewAndSampler t1)) {
				return;
			}
			tex2 = this.texture(t1, mip) + 1;
			if (tex2 == 0) {
				return;
			}
		}
		if (this.count * SPRITE + SPRITE > sprites.capacity()) {
			sprites = MemoryUtil.memRealloc(sprites, sprites.capacity() * 2);
		}
		long s = MemoryUtil.memAddress(sprites) + (long)this.count * SPRITE;
		float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
		float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE;
		for (int k = 0; k < 4; k++) {
			float x = corners[k][0], y = corners[k][1];
			// s = Sprite * (x, y, 0, 1); c = Proj * s
			float sx = spr[0] * x + spr[4] * y + spr[12], sy = spr[1] * x + spr[5] * y + spr[13];
			float sz = spr[2] * x + spr[6] * y + spr[14], sw = spr[3] * x + spr[7] * y + spr[15];
			float cx = proj[0] * sx + proj[4] * sy + proj[8] * sz + proj[12] * sw;
			float cy = proj[1] * sx + proj[5] * sy + proj[9] * sz + proj[13] * sw;
			float cw = proj[3] * sx + proj[7] * sy + proj[11] * sz + proj[15] * sw;
			float px = (cx / cw * 0.5f + 0.5f) * this.tw, py = (cy / cw * 0.5f + 0.5f) * this.th;
			MemoryUtil.memPutFloat(s + 4L * k, px);
			MemoryUtil.memPutFloat(s + 16 + 4L * k, py);
			MemoryUtil.memPutFloat(s + 32 + 4L * k, x + pu * (x * 2 - 1));
			MemoryUtil.memPutFloat(s + 48 + 4L * k, y + pv * (y * 2 - 1));
			minx = Math.min(minx, px);
			maxx = Math.max(maxx, px);
			miny = Math.min(miny, py);
			maxy = Math.max(maxy, py);
		}
		MemoryUtil.memPutInt(s + 64, tex2);
		MemoryUtil.memPutFloat(s + 68, progress / 1000.0f);
		MemoryUtil.memPutInt(s + 72, -1);
		MemoryUtil.memPutInt(s + 76, -1);
		int bx0 = Math.max(Math.max((int)Math.floor(minx), this.scX), 0), by0 = Math.max(Math.max((int)Math.floor(miny), this.scY), 0);
		int bx1 = Math.min(Math.min((int)Math.ceil(maxx) + 1, this.scX + this.scW), this.tw);
		int by1 = Math.min(Math.min((int)Math.ceil(maxy) + 1, this.scY + this.scH), this.th);
		if (bx1 <= bx0 || by1 <= by0) {
			return;
		}
		MemoryUtil.memPutInt(s + 80, bx0);
		MemoryUtil.memPutInt(s + 84, by0);
		MemoryUtil.memPutInt(s + 88, bx1);
		MemoryUtil.memPutInt(s + 92, by1);
		MemoryUtil.memPutInt(s + 96, tex);
		MemoryUtil.memPutInt(s + 100, tex2 != 0 ? 5 : 4);
		MemoryUtil.memPutInt(s + 104, p.blend);
		MemoryUtil.memPutInt(s + 108, 1);
		this.count++;
		this.rx0 = Math.min(this.rx0, bx0);
		this.ry0 = Math.min(this.ry0, by0);
		this.rx1 = Math.max(this.rx1, bx1);
		this.ry1 = Math.max(this.ry1, by1);
	}

	/** minecraft_mix_light: zwei Lichtrichtungen, 0,4 Umgebung + 0,6 diffus */
	private int itemLight(long vp, int abgr) {
		PydPipeline p = this.pipeline;
		if (p.normalOffset < 0 || p.lightIndex < 0 || !(this.uniforms[p.lightIndex] instanceof GpuBufferSlice ls)) {
			return abgr;
		}
		float nx = MemoryUtil.memGetByte(vp + p.normalOffset) / 127f;
		float ny = MemoryUtil.memGetByte(vp + p.normalOffset + 1) / 127f;
		float nz = MemoryUtil.memGetByte(vp + p.normalOffset + 2) / 127f;
		long a = ((PydBuffer)ls.buffer()).address() + ls.offset();
		float d0 = Math.max(0f, nx * MemoryUtil.memGetFloat(a) + ny * MemoryUtil.memGetFloat(a + 4) + nz * MemoryUtil.memGetFloat(a + 8));
		float d1 = Math.max(0f, nx * MemoryUtil.memGetFloat(a + 16) + ny * MemoryUtil.memGetFloat(a + 20) + nz * MemoryUtil.memGetFloat(a + 24));
		float l = Math.min(1f, (d0 + d1) * 0.6f + 0.4f);
		int r = clamp8((abgr & 255) * l), g = clamp8(((abgr >>> 8) & 255) * l), b = clamp8(((abgr >>> 16) & 255) * l);
		return r | g << 8 | b << 16 | (abgr & 0xff000000);
	}

	private static int modulate(int abgr, float[] m) {
		int r = abgr & 255, g = (abgr >>> 8) & 255, b = (abgr >>> 16) & 255, a = abgr >>> 24;
		if (m[0] != 1f || m[1] != 1f || m[2] != 1f || m[3] != 1f) {
			r = clamp8(r * m[0]);
			g = clamp8(g * m[1]);
			b = clamp8(b * m[2]);
			a = clamp8(a * m[3]);
		}
		return r | g << 8 | b << 16 | a << 24;
	}

	private static int clamp8(float v) {
		return Math.max(0, Math.min(255, Math.round(v)));
	}

	private void prepareTransforms() {
		PydPipeline p = this.pipeline;
		float[] proj = readMat(this.uniforms[p.projIndex], 0);
		float[] mv = p.dynIndex >= 0 ? readMat(this.uniforms[p.dynIndex], 0) : identity();
		this.mod[0] = this.mod[1] = this.mod[2] = this.mod[3] = 1f;
		if (p.dynIndex >= 0 && this.uniforms[p.dynIndex] instanceof GpuBufferSlice ds) {
			long a = ((PydBuffer)ds.buffer()).address() + ds.offset() + 128;
			for (int i = 0; i < 4; i++) {
				this.mod[i] = MemoryUtil.memGetFloat(a + 4L * i);
			}
		}
		// mvp = proj * mv (spaltenweise)
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				float sum = 0;
				for (int k = 0; k < 4; k++) {
					sum += proj[k * 4 + r] * mv[c * 4 + k];
				}
				this.mvp[c * 4 + r] = sum;
			}
		}
	}

	private static float[] identity() {
		return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	private static float[] readMat(Object o, int off) {
		if (!(o instanceof GpuBufferSlice s)) {
			return identity();
		}
		long a = ((PydBuffer)s.buffer()).address() + s.offset() + off;
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = MemoryUtil.memGetFloat(a + 4L * i);
		}
		return m;
	}

	private int texture(TextureViewAndSampler ts) {
		return this.texture(ts, 0);
	}

	private int texture(TextureViewAndSampler ts, int extraLevel) {
		Object key = extraLevel == 0 ? ts : java.util.List.of(ts, extraLevel);
		Integer known = this.texIndex.get(key);
		if (known != null) {
			return known;
		}
		PydTextureView view = (PydTextureView)ts.view();
		PydTexture t = view.pyd();
		GpuFormat f = t.getFormat();
		int bpp = f == GpuFormat.R8_UNORM ? 1 : (f == GpuFormat.RGBA8_UNORM ? 4 : 0);
		if (bpp == 0 || t.isClosed()) {
			return -1;
		}
		int i = this.texIndex.size();
		if ((i + 1) * TEX > texs.capacity()) {
			texs = MemoryUtil.memRealloc(texs, texs.capacity() * 2);
		}
		long a = MemoryUtil.memAddress(texs) + (long)i * TEX;
		int lvl = Math.min(view.baseMipLevel() + Math.max(extraLevel, 0), t.getMipLevels() - 1);
		MemoryUtil.memPutLong(a, t.levelLayer(lvl, 0));
		MemoryUtil.memPutInt(a + 8, t.getWidth(lvl));
		MemoryUtil.memPutInt(a + 12, t.getHeight(lvl));
		MemoryUtil.memPutInt(a + 16, bpp);
		PydSampler smp = (PydSampler)ts.sampler();
		MemoryUtil.memPutInt(a + 20, smp.getMagFilter() == FilterMode.LINEAR ? 1 : 0);
		MemoryUtil.memPutInt(a + 24, smp.getAddressModeU() == AddressMode.REPEAT ? 0 : 1);
		MemoryUtil.memPutInt(a + 28, 0);
		this.texIndex.put(key, i);
		return i;
	}

	/** Am Ende des Passes: alle Sprites in einem Aufruf blenden */
	public void finish() {
		if (this.count > 0 && this.color != null) {
			long r = MemoryUtil.memAddress(region);
			MemoryUtil.memPutInt(r, this.rx0);
			MemoryUtil.memPutInt(r + 4, this.ry0);
			MemoryUtil.memPutInt(r + 8, this.rx1);
			MemoryUtil.memPutInt(r + 12, this.ry1);
			Native.composite(this.colorPtr, this.tw, this.th, MemoryUtil.memAddress(sprites), this.count, MemoryUtil.memAddress(texs),
				this.texIndex.size(), r);
		}
		this.count = 0;
	}
}
