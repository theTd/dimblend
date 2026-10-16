package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.config.CFCommonConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import dimblend.experience.compat.fluid.CopperSinkSettings;
import java.util.function.Consumer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gate every virtual tank operation; finite operations keep the original FluidStack. */
@Mixin(targets = "com.adonis.fluid.block.CopperSink.CopperSinkBlockEntity$CopperSinkTank")
public abstract class CopperSinkTankMixin extends SmartFluidTank {
    private CopperSinkTankMixin(int capacity, Consumer<FluidStack> onChange) {
        super(capacity, onChange);
    }

    /** Upstream rejects non-water before its finite branch, allowing free hand-bottling. */
    @Inject(method = "drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$finiteResourceDrain(FluidStack resource, FluidAction action,
            CallbackInfoReturnable<FluidStack> cir) {
        if (!CopperSinkSettings.infiniteAllowed(CFCommonConfig.isCopperSinkInfinite())) {
            cir.setReturnValue(super.drain(resource, action));
        }
    }

    @ModifyExpressionValue(method = {"fillInfiniteWater()V",
            "fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I",
            "drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            "drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            "getFluidAmount()I", "getFluid()Lnet/neoforged/neoforge/fluids/FluidStack;"},
            at = @At(value = "INVOKE", target = "Lcom/adonis/fluid/config/CFCommonConfig;isCopperSinkInfinite()Z"),
            require = 6, allow = 6)
    private boolean dimblend$gateInfiniteTank(boolean original) {
        return CopperSinkSettings.infiniteAllowed(original);
    }
}
