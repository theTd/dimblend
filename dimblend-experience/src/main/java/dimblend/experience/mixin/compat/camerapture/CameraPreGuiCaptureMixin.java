package dimblend.experience.mixin.compat.camerapture;

import me.chrr.camerapture.picture.PictureTaker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read the finished world before GUI overlays can be baked into the photo. */
@Mixin(GameRenderer.class)
public abstract class CameraPreGuiCaptureMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"),
            require = 1, allow = 1)
    private void dimblend$captureBeforeGui(CallbackInfo ci) {
        // Upstream already guards the idle path and clears its pending flag before reading.
        // Its ordinary RenderFrameEvent.Post callback therefore does nothing a second time.
        PictureTaker.getInstance().renderTickEnd();
    }
}
