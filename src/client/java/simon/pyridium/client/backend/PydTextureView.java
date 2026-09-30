package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public class PydTextureView extends BaseGpuTextureView {
	private boolean closed;

	public PydTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
	}

	public PydTexture pyd() {
		return (PydTexture)this.texture();
	}

	@Override
	public boolean isClosed() {
		return this.closed || this.texture().isClosed();
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
