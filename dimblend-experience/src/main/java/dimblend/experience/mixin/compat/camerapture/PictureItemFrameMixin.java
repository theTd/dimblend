package dimblend.experience.mixin.compat.camerapture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.experience.compat.camerapture.FramedPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemFrameRenderer.class)
public abstract class PictureItemFrameMixin {
    @Inject(method = "getFrameModelResourceLoc", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$useMapFrame(ItemFrame frame, ItemStack stack,
            CallbackInfoReturnable<ModelResourceLocation> cir) {
        if (FramedPictureRenderer.isPicture(stack)) {
            cir.setReturnValue(ModelResourceLocation.vanilla(
                    frame.getType() == EntityType.GLOW_ITEM_FRAME ? "glow_item_frame" : "item_frame", "map=true"));
        }
    }

    @WrapOperation(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"),
            require = 1, allow = 1)
    private void dimblend$renderFullPicture(ItemRenderer renderer, ItemStack stack, ItemDisplayContext display,
            int light, int overlay, PoseStack poses, MultiBufferSource buffers, Level level, int seed,
            Operation<Void> original) {
        if (!FramedPictureRenderer.isPicture(stack) || !FramedPictureRenderer.render(stack, poses, buffers, light, overlay)) {
            original.call(renderer, stack, display, light, overlay, poses, buffers, level, seed);
        }
    }
}
