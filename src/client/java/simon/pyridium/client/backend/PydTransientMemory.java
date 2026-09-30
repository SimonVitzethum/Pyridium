package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Kurzlebiger Speicher für einen Frame. Weil alle Puffer im Hauptspeicher
 * liegen, ist "GPU"-Speicher hier derselbe wie CPU-Speicher; Daten werden
 * direkt beim Zeichnen gelesen, nach dem Frame ist alles frei.
 */
public class PydTransientMemory implements TransientMemory {
	private static final long BLOCK = 1L << 20;
	private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> alloc =
		new TransientBlockAllocator<>(BLOCK, 256L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());

	public void rotate() {
		this.alloc.rotate().run();
	}

	private GpuBufferSlice slice(TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> a, @GpuBuffer.Usage int usage) {
		PydBuffer b = new PydBuffer(usage, a.block().address(), a.offset() + a.size());
		return new GpuBufferSlice(b, a.offset(), a.size());
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		var a = this.alloc.allocate(size, alignment, minimumAllocation, elementSize);
		return MemoryUtil.memByteBuffer(a.block().address() + a.offset(), (int)a.size());
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		var a = this.alloc.allocate(size, alignment, minimumAllocation, elementSize);
		GpuBufferSlice s = this.slice(a, usage);
		return new GpuBufferSlice.MappedView(s, MemoryUtil.memByteBuffer(a.block().address() + a.offset(), (int)a.size()), () -> {});
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.slice(this.alloc.allocate(size, alignment, minimumAllocation, elementSize), usage);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
		long total = 0;
		for (ByteBuffer b : data) {
			total = Mth.roundToward(total + b.remaining(), Math.max(alignment, 1L));
		}
		var a = this.alloc.allocate(Math.max(total, minimumAllocation), Math.max(alignment, 1L), Math.max(total, minimumAllocation), elementSize);
		long base = a.block().address() + a.offset();
		long off = 0;
		for (ByteBuffer b : data) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(b), base + off, b.remaining());
			off = Mth.roundToward(off + b.remaining(), Math.max(alignment, 1L));
		}
		return this.slice(a, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		return this.multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> r = new ArrayList<>(data.size());
		for (ByteBuffer b : data) {
			r.add(this.uploadGpu(List.of(b), alignment, usage, b.remaining(), 1L));
		}
		return r;
	}
}
