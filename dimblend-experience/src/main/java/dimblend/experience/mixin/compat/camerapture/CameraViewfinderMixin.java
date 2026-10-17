package dimblend.experience.mixin.compat.camerapture;

import dimblend.experience.compat.camerapture.PictureAspect;
import me.chrr.camerapture.gui.CameraViewFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value = CameraViewFinder.class, remap = false)
public abstract class CameraViewfinderMixin {
    @ModifyArgs(method = "drawCameraViewFinder", at = @At(value = "INVOKE",
            target = "Lme/chrr/camerapture/gui/CameraViewFinder;drawViewFinder(Lnet/minecraft/client/gui/GuiGraphics;IIIIII)V",
            ordinal = 0), require = 1, allow = 1)
    private static void dimblend$matchCaptureBounds(Args args) {
        GuiGraphics graphics = args.get(0);
        var target = Minecraft.getInstance().getMainRenderTarget();
        var crop = PictureAspect.crop(target.width, target.height);
        args.set(1, Math.round((float) crop.x() * graphics.guiWidth() / target.width));
        args.set(2, Math.round((float) crop.y() * graphics.guiHeight() / target.height));
        args.set(3, Math.round((float) (crop.x() + crop.width()) * graphics.guiWidth() / target.width));
        args.set(4, Math.round((float) (crop.y() + crop.height()) * graphics.guiHeight() / target.height));
    }
}
