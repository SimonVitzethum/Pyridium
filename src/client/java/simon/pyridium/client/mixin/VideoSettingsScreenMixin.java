package simon.pyridium.client.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import simon.pyridium.client.world.RendererSettings;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    protected VideoSettingsScreenMixin(Screen previous, Options options, Component title) { super(previous, options, title); }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void pyridium$options(CallbackInfo ci) {
        if (list == null) return;
        list.addHeader(Component.literal("Pyridium · CUDA"));
        for (var feature : RendererSettings.Feature.values()) {
            list.addSmall(OptionInstance.createBoolean(feature.label(),
                OptionInstance.cachedConstantTooltip(Component.translatable(feature.label() + ".tooltip")),
                RendererSettings.enabled(feature), value -> RendererSettings.enabled(feature, value)));
        }
    }
}
