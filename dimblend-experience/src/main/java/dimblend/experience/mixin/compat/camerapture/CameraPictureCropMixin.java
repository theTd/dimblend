package dimblend.experience.mixin.compat.camerapture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import dimblend.experience.compat.camerapture.PictureAspect;
import java.awt.image.BufferedImage;
import me.chrr.camerapture.picture.PictureTaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only camera captures pass here; uploads and ordinary screenshots retain their dimensions. */
@Mixin(value = PictureTaker.class, remap = false)
public abstract class CameraPictureCropMixin {
    @WrapOperation(method = "renderTickEnd", at = @At(value = "INVOKE",
            target = "Lme/chrr/camerapture/util/ImageUtil;fromNativeImage(Lcom/mojang/blaze3d/platform/NativeImage;)Ljava/awt/image/BufferedImage;"),
            require = 1, allow = 1)
    private BufferedImage dimblend$cropCameraShot(NativeImage image, Operation<BufferedImage> original) {
        return PictureAspect.crop(original.call(image));
    }
}
