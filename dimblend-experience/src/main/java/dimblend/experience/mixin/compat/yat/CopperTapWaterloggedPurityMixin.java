package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 缺口 9（前半）：含水方块铜龙头 {@code CopperTapBlockEntity$WaterloggedBlockFluidHandler}。
 *
 * <p>这个 handler 的 {@code WATER} 是类里的常量水源（"取之不尽"），本质是脏水：抽出来的
 * {@code FluidStack} 没有任何 {@code Purity} 组件时会被 YAT 当 0，但只要某个下游给它补了
 * 组件就会变成"好水"。这里强制每次 drain 都打 {@code Purity: 0}，两个 {@code drain} 重载
 * 返回类型一致，用 {@code ModifyReturnValue} 一次覆盖。</p>
 *
 * <p>返回点数：{@code drain(FluidStack, FluidAction)} 有 2 个 {@code areturn}（非水返回
 * {@code FluidStack.EMPTY} + 水返回等量 {@code WATER}），{@code drain(int, FluidAction)}
 * 是独立实现（{@code new FluidStack(WATER, min(amount, 1000))}）另有 1 个 {@code areturn}，
 * 合计 <b>3</b> 条（fluid 2.0.1 运行 jar 字节码核实）。{@code require = 2}（两个方法都得命中）
 * 配 {@code allow = 3} 把三条返回点全部覆盖。{@code FluidStack.EMPTY} 那条被
 * {@code isWater} 拦掉，不会污染这个全局单例。</p>
 */
@Pseudo
@Mixin(targets = "com.adonis.fluid.block.CopperTap.CopperTapBlockEntity$WaterloggedBlockFluidHandler", remap = false)
public abstract class CopperTapWaterloggedPurityMixin {

    @ModifyReturnValue(method = "drain", at = @At("RETURN"), remap = false, require = 2, allow = 3)
    private FluidStack dimblend$stampInfiniteWaterloggedWater(FluidStack original) {
        if (DirtyWaterCompat.purityAvailable()
                && WaterSourcePurityRules.isWater(original.isEmpty(), original.is(Fluids.WATER))) {
            DirtyWaterPurity.stampFluidPurity(original, WaterSourcePurityRules.INFINITE_SOURCE_PURITY);
        }
        return original;
    }
}
