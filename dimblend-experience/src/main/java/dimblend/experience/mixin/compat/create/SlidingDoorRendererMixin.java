package dimblend.experience.mixin.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorRenderer;
import dimblend.experience.compat.create.SlidingDoorPairedRenderer;
import dimblend.experience.compat.create.SlidingDoorPairing;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SlidingDoorRenderer.class)
public abstract class SlidingDoorRendererMixin {
    @Inject(
            method = "renderSafe(Lcom/simibubi/create/content/decoration/slidingDoor/SlidingDoorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$renderPairedDoor(
            SlidingDoorBlockEntity be,
            float partialTicks,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay,
            CallbackInfo ci) {
        if (SlidingDoorPairing.isPaired(be.getBlockState())) {
            SlidingDoorPairedRenderer.renderPaired(be, partialTicks, ms, buffer, light, overlay);
            ci.cancel();
        }
    }
}
