package dimblend.experience.mixin.compat.accessories;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import io.wispforest.accessories.client.AccessoriesClient;
import io.wispforest.accessories.compat.config.ScreenType;
import io.wispforest.owo.ui.layers.Layer;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client lock for the Accessories top-right inventory button:
 * <ul>
 *   <li>wraps the {@code Layers.add} initializer in {@code initLayer} so the survival
 *       {@code InventoryScreen} never adds the button (invisible and unclickable);</li>
 *   <li>blocks {@code attemptToOpenScreen(boolean, ScreenType)} in survival (keybind &amp; fallback).</li>
 * </ul>
 */
@Mixin(value = AccessoriesClient.class, remap = false)
public abstract class AccessoriesClientSurvivalMixin {

    @ModifyArg(
            method = "initLayer()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wispforest/owo/ui/layers/Layers;add(Ljava/util/function/BiFunction;Ljava/util/function/Consumer;[Ljava/lang/Class;)Lio/wispforest/owo/ui/layers/Layer;"),
            index = 1,
            remap = false,
            require = 1,
            allow = 1)
    private static Consumer<Layer<?, ?>.Instance> dimblend$skipSurvivalInventoryButton(
            Consumer<Layer<?, ?>.Instance> upstreamInitializer) {
        return AccessoriesSurvivalRules.wrapLayerInitializer(upstreamInitializer);
    }

    @Inject(method = "attemptToOpenScreen(ZLio/wispforest/accessories/compat/config/ScreenType;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private static void dimblend$blockSurvivalOpen(boolean targetLook, ScreenType screenType,
            CallbackInfoReturnable<Boolean> cir) {
        if (AccessoriesSurvivalRules.isClientSurvival()) {
            cir.setReturnValue(false);
        }
    }
}
