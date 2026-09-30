package simon.pyridium.client.world;

/** Stable Minecraft identity for Pyrit motion history, independent of state allocation. */
public interface PyritEntityState {
    long pyridium$id();
    void pyridium$id(long value);
}
