package simon.pyridium.client.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import simon.pyridium.client.gpu.Native;

/** Befehle in Aufrufreihenfolge auf dem CUDA-Stream von libpyridium. */
public class PydCommandEncoder implements CommandEncoderBackend {
	private final PydTransientMemory transientMemory = new PydTransientMemory();
	private @Nullable PydRenderPass pass;
	private static final ByteBuffer VALUE = MemoryUtil.memAlloc(16);

	@Override
	public void submit() {
		this.transientMemory.rotate();
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		this.pass = new PydRenderPass(descriptor);
		return this.pass;
	}

	@Override
	public void submitRenderPass() {
		if (this.pass != null) {
			this.pass.finish();
			this.pass = null;
		}
	}

	static void fillColor(PydTexture t, int level, Vector4fc c, int x, int y, int w, int h) {
		long v = MemoryUtil.memAddress(VALUE);
		GpuFormat f = t.getFormat();
		switch (f) {
			case RGBA8_UNORM -> MemoryUtil.memPutInt(v, u8(c.x()) | u8(c.y()) << 8 | u8(c.z()) << 16 | u8(c.w()) << 24);
			case R8_UNORM -> MemoryUtil.memPutInt(v, u8(c.x()) * 0x01010101);
			case RGBA32_FLOAT, R32_FLOAT, RG32_FLOAT -> {
				MemoryUtil.memPutFloat(v, c.x());
				MemoryUtil.memPutFloat(v + 4, c.y());
				MemoryUtil.memPutFloat(v + 8, c.z());
				MemoryUtil.memPutFloat(v + 12, c.w());
			}
			default -> MemoryUtil.memSet(v, 0, 16);
		}
		int w0 = t.getWidth(level);
		long base = t.levelLayer(level, 0);
		for (int layer = 0; layer < t.getDepthOrLayers(); layer++) {
			Native.fill(base + (long)layer * w0 * t.getHeight(level) * t.bpp, w0, t.bpp, x, y, Math.min(w, w0 - x), Math.min(h, t.getHeight(level) - y), v);
		}
	}

	static void fillDepth(PydTexture t, double d) {
		long v = MemoryUtil.memAddress(VALUE);
		MemoryUtil.memPutFloat(v, (float)d);
		MemoryUtil.memPutInt(v + 4, 0);
		Native.fill(t.ptr, t.getWidth(0), t.bpp, 0, 0, t.getWidth(0), t.getHeight(0), v);
	}

	private static int u8(float x) {
		return Math.max(0, Math.min(255, Math.round(x * 255f)));
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		PydTexture t = (PydTexture)colorTexture;
		fillColor(t, 0, clearColor, 0, 0, t.getWidth(0), t.getHeight(0));
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		this.clearColorTexture(colorTexture, clearColor);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth, int x, int y, int w,
		int h, int mipLevel) {
		fillColor((PydTexture)colorTexture, mipLevel, clearColor, x, y, w, h);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		fillDepth((PydTexture)depthTexture, clearDepth);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		long dst = ((PydBuffer)destination.buffer()).address() + destination.offset();
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst, Math.min(data.remaining(), destination.length()));
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		long src = ((PydBuffer)source.buffer()).address() + source.offset();
		long dst = ((PydBuffer)target.buffer()).address() + target.offset();
		MemoryUtil.memCopy(src, dst, Math.min(source.length(), target.length()));
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int layer, int x, int y, int width, int height) {
		PydTexture t = (PydTexture)destination;
		int bpp = t.bpp;
		int w = t.getWidth(mipLevel);
		long dst = t.levelLayer(mipLevel, layer) + ((long)y * w + x) * bpp;
		Native.upload2d(dst, (long)w * bpp, MemoryUtil.memAddress(source), (long)width * bpp, (long)width * bpp, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination, int dx,
		int dy, int cw, int ch, int mipLevel, int layer) {
		PydTexture t = (PydTexture)destination;
		int bpp = t.bpp;
		int w = t.getWidth(mipLevel);
		long src = ((PydBuffer)source.buffer()).address() + source.offset() + ((long)sourceY * sourceWidth + sourceX) * bpp;
		long dst = t.levelLayer(mipLevel, layer) + ((long)dy * w + dx) * bpp;
		Native.upload2d(dst, (long)w * bpp, src, (long)sourceWidth * bpp, (long)cw * bpp, ch);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int w, int h) {
		PydTexture t = (PydTexture)source;
		int bpp = t.bpp;
		int tw = t.getWidth(mipLevel);
		long src = t.levelLayer(mipLevel, 0) + ((long)y * tw + x) * bpp;
		long dst = ((PydBuffer)destination).address() + offset;
		Native.download2d(dst, (long)w * bpp, src, (long)tw * bpp, (long)w * bpp, h);
		RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int dx, int dy, int sx, int sy, int w, int h) {
		PydTexture s = (PydTexture)source, d = (PydTexture)destination;
		int bpp = s.bpp;
		long src = s.levelLayer(mipLevel, 0) + ((long)sy * s.getWidth(mipLevel) + sx) * bpp;
		long dst = d.levelLayer(mipLevel, 0) + ((long)dy * d.getWidth(mipLevel) + dx) * bpp;
		Native.copy2d(dst, (long)d.getWidth(mipLevel) * bpp, src, (long)s.getWidth(mipLevel) * bpp, (long)w * bpp, h);
	}

	@Override
	public GpuFence createFence() {
		return new PydFence();
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}
}
