package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import simon.pyridium.client.gpu.Native;

/** Gerät des Pyridium-Backends: CUDA über libpyridium, Welt über Pyrit. */
public class PydDevice implements GpuDeviceBackend {
	private final DeviceInfo info;

	public PydDevice() {
		String name = Native.deviceName();
		this.info = new DeviceInfo(
			name,
			"NVIDIA",
			"CUDA + Pyrit (Sparse Voxel Hash-DAG)",
			true,
			"Pyridium",
			1.0F,
			new DeviceLimits(16, 16, 16384, 1L << 31, 0, 8, 0),
			new DeviceFeatures(false, false, false, false, false, false, true, true),
			Set.of(),
			new HintsAndWorkarounds(false, false, false, false),
			DeviceType.DISCRETE
		);
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new PydSurface(windowHandle, isIconified);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return new PydCommandEncoder();
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return new PydSampler(u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		return new PydTexture(usage, label == null ? "" : label, format, width, height, layers, mips);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		return new PydTextureView(texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		return new PydBuffer(usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		PydBuffer b = new PydBuffer(usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), b.address(), data.remaining());
		return b;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
		PydPipeline p = new PydPipeline(createInfo);
		return () -> p;
	}

	@Override
	public void close() {
		Native.sync();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new PydQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}
}
