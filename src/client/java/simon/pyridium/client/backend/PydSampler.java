package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public record PydSampler(AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter, FilterMode getMagFilter,
		int getMaxAnisotropy, OptionalDouble getMaxLod) implements GpuSampler {
	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public void close() {
	}
}
