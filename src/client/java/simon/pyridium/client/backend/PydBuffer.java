package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Puffer im Hauptspeicher. Minecraft schreibt Vertex- und Uniform-Daten
 * hinein; gelesen werden sie auf der CPU (GUI-Sprites) – die Welt kommt von
 * Pyrit, es gibt keine Vertexverarbeitung auf der GPU.
 */
public class PydBuffer extends BaseGpuBuffer {
	private final long address;
	private final boolean owned;
	private boolean closed;

	public PydBuffer(@GpuBuffer.Usage int usage, long size) {
		super(usage, size);
		this.address = MemoryUtil.nmemCalloc(1L, Math.max(size, 1L));
		if (this.address == 0L) {
			throw new OutOfMemoryError("PydBuffer " + size);
		}
		this.owned = true;
	}

	/** Sicht auf fremden Speicher (Transient-Blöcke) */
	public PydBuffer(@GpuBuffer.Usage int usage, long address, long size) {
		super(usage, size);
		this.address = address;
		this.owned = false;
	}

	public long address() {
		return this.address;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.owned) {
				MemoryUtil.nmemFree(this.address);
			}
		}
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (this.closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (offset < 0 || length < 0 || offset + length > this.size()) {
			throw new IllegalArgumentException("map out of range");
		}
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, (int)length), () -> {});
	}
}
