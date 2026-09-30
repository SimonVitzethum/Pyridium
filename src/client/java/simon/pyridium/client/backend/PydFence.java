package simon.pyridium.client.backend;

import com.mojang.renderpearl.api.commands.GpuFence;
import simon.pyridium.client.gpu.Native;

/** Zaun = CUDA-Ereignis am Ende der bis dahin eingereihten Arbeit; wartet nur auf diese. */
public class PydFence implements GpuFence {
	private long event = Native.eventRecord();
	private boolean done;

	@Override
	public void close() {
		if (this.event != 0) {
			Native.eventFree(this.event);
			this.event = 0;
		}
	}

	@Override
	public boolean awaitCompletion(long timeoutNS) {
		if (!this.done) {
			this.done = Native.eventWait(this.event, timeoutNS != 0L);
		}
		return this.done;
	}
}
