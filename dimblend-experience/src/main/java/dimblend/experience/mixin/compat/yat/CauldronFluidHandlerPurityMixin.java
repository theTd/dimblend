package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

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
 * <p><b>两个 drain 重载都要包</b>：{@code drain(FluidStack, FluidAction)} 与
 * {@code drain(int, FluidAction)} 在 Create: Fluid 2.0.1 里是两份独立实现
 * （{@code javap -c} 核实，后者 {@code new FluidStack(WATER, min(amount, 1000))}，
 * 不委托前者），只包一个会让管道/水龙头的"按量抽"漏打纯度。两处都写全描述符并各钉
 * {@code require = 1, allow = 1}——<b>绝对不能</b>退化成裸 {@code "drain"}，那会把两个重载
 * 的三个返回点合并计数，{@code allow} 一旦写错就是启动期 {@code InjectionError}。</p>
 *
 * <p>注意：本类自己缓存了 {@code state} 字段，我们只改纯度不改 {@code LEVEL}，
 * 所以缓存的容量/层数判断不受影响。</p>
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.handler.CauldronFluidHandler", remap = false)
public abstract class CauldronFluidHandlerPurityMixin {

    @Shadow(remap = false)
    @Final
    private Level level;

    @Shadow(remap = false)
    @Final
    private BlockPos pos;

    @WrapMethod(method = "drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampCauldronDrain(FluidStack resource, IFluidHandler.FluidAction action,
            Operation<FluidStack> original) {
        int purity = dimblend$readBlockPurity();
        FluidStack drained = original.call(resource, action);
        dimblend$stampDrainedWater(drained, purity, action);
        return drained;
    }

    @WrapMethod(method = "drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampCauldronDrainByAmount(int maxDrain, IFluidHandler.FluidAction action,
            Operation<FluidStack> original) {
        int purity = dimblend$readBlockPurity();
        FluidStack drained = original.call(maxDrain, action);
        dimblend$stampDrainedWater(drained, purity, action);
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

    /**
     * 抽之前读这一格的纯度：抽完锅可能已经空了，回头再读就是 0；
     * YAT 缺席时退回"无限水源"的脏水纯度。
     */
    @Unique
    private int dimblend$readBlockPurity() {
        return DirtyWaterCompat.purityAvailable() && !this.level.isClientSide
                ? WaterPurity.getBlockPurity(this.level, this.pos)
                : WaterSourcePurityRules.INFINITE_SOURCE_PURITY;
    }

    @Unique
    private static void dimblend$stampDrainedWater(FluidStack drained, int purity, IFluidHandler.FluidAction action) {
        if (DirtyWaterCompat.purityAvailable()
                && WaterSourcePurityRules.isWater(drained.isEmpty(), drained.is(Fluids.WATER))
                && !action.simulate()) {
            DirtyWaterPurity.stampFluidPurity(drained, purity);
        }
    }
}
