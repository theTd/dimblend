package dimblend.experience.mixin.compat.firefighting;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mikoalopex.createfirefightingadd.content.fluids.water_intake.WaterIntakeBlockEntity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.CauldronPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 缺口 8：消防取水器（{@code createfirefightingadd} 的 {@code WaterIntakeBlockEntity}）。
 *
 * <p><b>为什么必须显式写 {@code Purity: 0}</b>（{@code dimblend-dev} §8.61 的坑）：YAT
 * {@code FluidPurityHelper#getPurity(FluidStack)} 对<b>无标签</b>栈返回
 * {@code ThirstConfig.DEFAULT_PURITY = 2}（杀菌、可饮用）。取水器原实现两条进水路径都是
 * {@code new FluidStack(Fluids.WATER, n)} 裸栈（{@code javap -p -c}：{@code fillInternalTank}
 * offset 40-49、{@code tryWirelessTransfer} offset 110-120），一旦进空罐，读出来就是 2 ——
 * "取之不尽的河水被当成好水"正是要堵的漏洞。本片旧注释"无组件即按 0 判脏"只对
 * {@code DirtyWaterPurity#isDirtyWaterFluid} 成立，对 YAT 自身的 {@code getPurity} 不成立。</p>
 *
 * <p>三条路径按口径 2（无限水源 = 0）处理：</p>
 * <ol>
 *   <li>{@code fillInternalTank(int)}：在 {@code new FluidStack(Fluids.WATER, amount)} 这一次构造上
 *       打 0（{@code @ModifyExpressionValue} 单点）。这一步<b>先于</b> YAT 自己
 *       {@code MixinWaterIntake} 对该 {@code SmartFluidTank.fill} 的包装执行（YAT 那份按附近
 *       水源的 {@code getBlockPurity} 打标，高山湖会给 1）；YAT 那侧只在"无标签"时才动手，
 *       本处先打上标签即让它自动跳过，所以终值恒为 0，不受两侧注入顺序影响。</li>
 *   <li>{@code tryWirelessTransfer()}：同一手法打 0。YAT 只包了 {@code fillInternalTank} 和
 *       {@code tryFillCauldron}，<b>没包</b>无线传输（{@code MixinWaterIntake} 的
 *       {@code method} 串只有这两条），这处是本片独占的补丁。</li>
 *   <li>{@code tryFillCauldron(BlockPos, BlockState, int)}：返回 true 后把 {@code BLOCK_PURITY}
 *       并入纯度 0（{@link CauldronPurity#blend}，与已有值取 {@code min}）。</li>
 * </ol>
 *
 * <p>{@code SableWaterIntakeBlockEntity}（{@code integration/sable}）只有构造函数、不覆写任何
 * 父类方法（{@code javap -p} 只有 {@code <init>}），所以打在父类上就同时覆盖两种方块实体。</p>
 */
@Pseudo
@Mixin(targets = "com.mikoalopex.createfirefightingadd.content.fluids.water_intake.WaterIntakeBlockEntity", remap = false)
public abstract class WaterIntakePurityMixin {

    @ModifyExpressionValue(method = "fillInternalTank",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/world/level/material/Fluid;I)"
                            + "Lnet/neoforged/neoforge/fluids/FluidStack;"),
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$dirtyInternalTankFill(FluidStack filled) {
        return DirtyWaterCompat.purityAvailable()
                ? DirtyWaterPurity.stampFluidPurity(filled, WaterSourcePurityRules.INFINITE_SOURCE_PURITY)
                : filled;
    }

    @ModifyExpressionValue(method = "tryWirelessTransfer",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/world/level/material/Fluid;I)"
                            + "Lnet/neoforged/neoforge/fluids/FluidStack;"),
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$dirtyWirelessTransfer(FluidStack transferred) {
        return DirtyWaterCompat.purityAvailable()
                ? DirtyWaterPurity.stampFluidPurity(transferred, WaterSourcePurityRules.INFINITE_SOURCE_PURITY)
                : transferred;
    }

    /**
     * {@code tryFillCauldron(BlockPos, BlockState, int)} 有 <b>4</b> 条 {@code ireturn}
     * （javap {@code createfirefightingadd 0.2.1-beta}：offset 21 / 63 / 109 / 244），
     * 所以 {@code allow} 钉成 4（Slice 5 原来写 {@code allow = 1}，装包期会直接抛
     * {@code InjectionError: count 4 exceeds allow 1}）。四条出口共用一次 handler：
     * {@code cir.getReturnValue()} 为 false 时立刻返回，只有成功那条会落方块态。
     */
    @Inject(method = "tryFillCauldron", at = @At("RETURN"), remap = false, require = 1, allow = 4)
    private void dimblend$markCauldronDirty(BlockPos pos, BlockState state, int level,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || !DirtyWaterCompat.purityAvailable()) {
            return;
        }
        WaterIntakeBlockEntity self = (WaterIntakeBlockEntity) (Object) this;
        Level world = self.getLevel();
        CauldronPurity.blend(world, pos, WaterSourcePurityRules.INFINITE_SOURCE_PURITY);
    }
}
