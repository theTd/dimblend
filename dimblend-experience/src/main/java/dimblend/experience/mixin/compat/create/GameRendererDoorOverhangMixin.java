package dimblend.experience.mixin.compat.create;

import dimblend.experience.compat.create.SlidingDoorOverhangPick;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class GameRendererDoorOverhangMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "pick(F)V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$pickPairedDoorOverhang(float partialTick, CallbackInfo ci) {
        SlidingDoorOverhangPick.updateCrosshairPick(this.minecraft, partialTick);
    }
}
