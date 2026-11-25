package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 缺口 3（后半）：软管滑轮内部水箱"混合取 {@code min}"。
 *
 * <p>软管滑轮抽上来的水进入 {@code internalTank}；管子里已经存着另一种纯度的水时，
 * {@code SmartFluidTank#fill} 直接用新栈的组件覆盖，会出现"半箱 2 变 3"这种把脏水洗干净的
 * 假象。这里在 {@code fill} 成功后把箱内水按 {@code min(现有, 新进)} 打标：
 * 脏水只会更脏，不会凭空变干净（与 {@code WaterSourcePurityRules#blendMin} 同口径）。</p>
 */
@Mixin(HosePulleyFluidHandler.class)
public abstract class HosePulleyFluidHandlerPurityMixin {

    @Shadow(remap = false)
    private SmartFluidTank internalTank;

    @WrapMethod(method = "fill", remap = false, require = 1, allow = 1)
    private int dimblend$blendInternalTankPurity(FluidStack resource, IFluidHandler.FluidAction action,
            Operation<Integer> original) {
        int filled = original.call(resource, action);
        if (filled <= 0
                || action.simulate()
                || !DirtyWaterCompat.purityAvailable()
                || !WaterSourcePurityRules.isWater(resource.isEmpty(), resource.is(Fluids.WATER))) {
            return filled;
        }
        FluidStack tank = this.internalTank.getFluid();
        if (tank.isEmpty() || !tank.is(Fluids.WATER)) {
            return filled;
        }
        int existing = WaterSourcePurityRules.effectivePurity(FluidPurityHelper.hasPurity(tank),
                FluidPurityHelper.getPurity(tank));
        int incoming = WaterSourcePurityRules.effectivePurity(FluidPurityHelper.hasPurity(resource),
                FluidPurityHelper.getPurity(resource));
        DirtyWaterPurity.stampFluidPurity(tank, WaterSourcePurityRules.blendMin(existing, incoming));
        return filled;
    }
}
