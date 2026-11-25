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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    /**
     * 缺口 9（后半）：无限模式的水槽 = 取之不尽的水，按 YAT 口径就是 {@code Purity: 0}（脏）。
     *
     * <p>上游 {@code fillInfiniteWater()} 每次把箱内水栈重灌成无水组件的 {@code WATER}；
     * TAIL 里把 {@code Purity: 0} 显式打上，这样无论是管道抽、瓶子装还是 HUD 读数，
     * 拿到的都是"脏水"，不会再因为下游补了组件而变成好水。有限模式（玩家自己往里倒水）
     * 不走这个分支，保留原有纯度。</p>
     */
    @Inject(method = "fillInfiniteWater()V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$dirtyInfiniteWater(CallbackInfo ci) {
        if (!CopperSinkSettings.infiniteAllowed(CFCommonConfig.isCopperSinkInfinite())) {
            return;
        }
        if (!dimblend.experience.compat.dirtywater.DirtyWaterCompat.purityAvailable()) {
            return;
        }
        FluidStack stored = this.getFluid();
        if (stored != null && !stored.isEmpty() && stored.is(net.minecraft.world.level.material.Fluids.WATER)) {
            dimblend.experience.compat.dirtywater.DirtyWaterPurity.stampFluidPurity(stored, 0);
        }
    }
}
