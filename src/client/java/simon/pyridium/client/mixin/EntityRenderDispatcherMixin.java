package simon.pyridium.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import simon.pyridium.client.world.PyritEntityState;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void pyridium$identity(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        ((PyritEntityState)cir.getReturnValue()).pyridium$id(Integer.toUnsignedLong(entity.getId()) + 1);
    }
}
