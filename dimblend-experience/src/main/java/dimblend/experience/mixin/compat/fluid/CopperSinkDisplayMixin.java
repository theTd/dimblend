package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.block.CopperSink.CopperSinkBlockEntity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.compat.fluid.CopperSinkSettings;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CopperSinkBlockEntity.class)
public abstract class CopperSinkDisplayMixin {
    @ModifyExpressionValue(method = {"onLoad", "getFillState", "addToGoggleTooltip"},
            at = @At(value = "INVOKE", target = "Lcom/adonis/fluid/config/CFCommonConfig;isCopperSinkInfinite()Z"),
            require = 4, allow = 4)
    private boolean dimblend$gateInfiniteDisplay(boolean original) {
        return CopperSinkSettings.infiniteAllowed(original);
    }

    /** A live toggle must also update the client's interpolated water surface. */
    @Inject(method = "tick()V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$refreshSurface(CallbackInfo ci) {
        CopperSinkBlockEntity self = (CopperSinkBlockEntity) (Object) this;
        if (self.getLevel() != null && self.getLevel().isClientSide && self.getFluidLevel() != null) {
            self.getFluidLevel().chase(self.getFillState(), 0.5, LerpedFloat.Chaser.EXP);
        }
    }
}
