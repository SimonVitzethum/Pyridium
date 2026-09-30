package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import simon.pyridium.client.gpu.Native;

/**
 * Textur im Grafikspeicher (CUDA), linear: Mip für Mip, darin Ebene für Ebene,
 * Zeilen dicht gepackt. Zeile 0 ist die erste Zeile der hochgeladenen Daten
 * (Render-Targets: unten, wie bei OpenGL; die Ausgabe dreht).
 */
public class PydTexture extends BaseGpuTexture {
	public final long ptr;
	public final int bpp;
	private boolean closed;

	public PydTexture(@GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height, int layers, int mips) {
		super(usage, label, format, width, height, layers, mips);
		this.bpp = bytesPerPixel(format);
		this.ptr = Native.alloc(this.mipOffset(mips));
	}

	public static int bytesPerPixel(GpuFormat f) {
		return switch (f) {
			case D32_FLOAT_S8_UINT -> 8;
			case D32_FLOAT, D24_UNORM_S8_UINT, D16_UNORM -> 4;
			case S8_UINT -> 1;
			default -> Math.max(f.blockSize(), 1);
		};
	}

	/** Bytes vor Mip `level` */
	public long mipOffset(int level) {
		long off = 0;
		for (int k = 0; k < level; k++) {
			off += (long)this.getWidth(k) * this.getHeight(k) * this.getDepthOrLayers() * this.bpp;
		}
		return off;
	}

	public long levelLayer(int level, int layer) {
		return this.ptr + this.mipOffset(level) + (long)layer * this.getWidth(level) * this.getHeight(level) * this.bpp;
	}

	@Override
	public int getWidth(int mipLevel) {
		return Math.max(super.getWidth(mipLevel), 1);
	}

	@Override
	public int getHeight(int mipLevel) {
		return Math.max(super.getHeight(mipLevel), 1);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			Native.free(this.ptr);
		}
	}
}
