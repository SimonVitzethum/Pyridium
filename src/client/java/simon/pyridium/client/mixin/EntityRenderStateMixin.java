package simon.pyridium.client.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import simon.pyridium.client.world.PyritEntityState;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements PyritEntityState {
    @Unique private long pyridium$id;
    @Override public long pyridium$id() { return pyridium$id; }
    @Override public void pyridium$id(long value) { pyridium$id = value; }
}
