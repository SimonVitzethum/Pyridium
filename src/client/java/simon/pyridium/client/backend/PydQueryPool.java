package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

public class PydQueryPool implements GpuQueryPool {
	private final int size;

	public PydQueryPool(int size) {
		this.size = size;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public OptionalLong getValue(int index) {
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		OptionalLong[] r = new OptionalLong[count];
		Arrays.fill(r, OptionalLong.empty());
		return r;
	}

	@Override
	public void close() {
	}
}
