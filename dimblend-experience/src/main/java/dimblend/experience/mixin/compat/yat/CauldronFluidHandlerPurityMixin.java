package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.adonis.fluid.handler.CauldronFluidHandler;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.CauldronPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 缺口 12：Create: Fluid 的 {@code CauldronFluidHandler}（炼药锅流体能力，Spout/管道都走它）。
 *
 * <ul>
 *   <li>{@code drain}：抽之前用 {@code WaterPurity.getBlockPurity} 读这一格的纯度，
 *       打到抽出来的 {@code FluidStack} 上（抽完锅可能空了，必须<b>先读</b>）；</li>
 *   <li>{@code fill}：灌进去的水按 {@code hasPurity ? getPurity : 0} 取纯度，
 *       灌成功就并入方块态（{@link CauldronPurity#blend}，{@code min} 合并）。</li>
 * </ul>
 *
 * <p>注意：本类自己缓存了 {@code state} 字段，我们只改纯度不改 {@code LEVEL}，
 * 所以缓存的容量/层数判断不受影响。</p>
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.handler.CauldronFluidHandler", remap = false)
public abstract class CauldronFluidHandlerPurityMixin {

    @Shadow(remap = false)
    private Level level;

    @Shadow(remap = false)
    private BlockPos pos;

    @WrapMethod(method = "drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampCauldronDrain(FluidStack resource, IFluidHandler.FluidAction action,
            Operation<FluidStack> original) {
        int purity = DirtyWaterCompat.purityAvailable() && !this.level.isClientSide
                ? WaterPurity.getBlockPurity(this.level, this.pos)
                : WaterSourcePurityRules.INFINITE_SOURCE_PURITY;
        FluidStack drained = original.call(resource, action);
        if (DirtyWaterCompat.purityAvailable()
                && WaterSourcePurityRules.isWater(drained.isEmpty(), drained.is(Fluids.WATER)) && !action.simulate()) {
            DirtyWaterPurity.stampFluidPurity(drained, purity);
        }
        return drained;
    }

    @WrapMethod(method = "fill", remap = false, require = 1, allow = 1)
    private int dimblend$blendCauldronFill(FluidStack resource, IFluidHandler.FluidAction action,
            Operation<Integer> original) {
        int filled = original.call(resource, action);
        if (filled <= 0
                || action.simulate()
                || !DirtyWaterCompat.purityAvailable()
                || !WaterSourcePurityRules.isWater(resource.isEmpty(), resource.is(Fluids.WATER))) {
            return filled;
        }
        int purity = WaterSourcePurityRules.effectivePurity(FluidPurityHelper.hasPurity(resource),
                FluidPurityHelper.getPurity(resource));
        CauldronPurity.blend(this.level, this.pos, purity);
        return filled;
    }
}
