package dimblend.experience.mixin.compat.sophisticatedcore;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.PumpUpgradeReflection;
import dimblend.experience.compat.yat.PurityStampingFluidHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 缺口 5：Sophisticated Core 泵升级的<b>进、出</b>两侧都要保住纯度。
 *
 * <p><b>抽水（进）</b>：{@code fillFromBlock(Level, BlockPos, IFluidHandler, Entity)} 是"从方块/流体源
 * 往背包储罐灌水"的实现点。这里不改它的内部字节码，而是在调用前把它的目标 {@code IFluidHandler}
 * 参数换成 {@link PurityStampingFluidHandler}：灌进去的水自动带上
 * {@code WaterPurity.getBlockPurity(level, pos)}（已含 Sable 投影），其余动作原样转发。</p>
 *
 * <p><b>放水（出）</b>：{@code manifest-s5.md} 原来断言"导出一侧天然保真：储罐里的 FluidStack 自带
 * 组件，{@code copyWithAmount} 会带走纯度"。<b>字节码否定</b>
 * （{@code sophisticatedcore-1.21.1-1.4.38.1847}，{@code javap -p -c}）：</p>
 * <ul>
 *   <li>导出走私有 3 参 {@code fillFluidHandler(destination, source, amount)}（字节码 offset 47-59），
 *       它拿 {@code new FluidStack(tankFluid.getFluid(), amount)} 当<b>请求栈</b>——只带流体名，
 *       把 {@code Purity} 组件扔了；</li>
 *   <li>这个请求栈进 {@code FluidUtil.tryFluidTransfer(destination, source, resource, true)}
 *       （{@code neoforge-21.1.249-merged} 字节码 offset 17-26）：内部先
 *       {@code source.drain(resource, SIMULATE)}，再用
 *       {@code FluidStack.isSameFluidSameComponents(drained, resource)}（offset 336 的
 *       {@code isSameFluidSameComponents} 是<b>连 components 一起比</b>）判等；储罐里的水带
 *       {@code Purity}、请求栈不带 → 判等失败 → 直接返回 {@code EMPTY}。</li>
 * </ul>
 * <p>也就是说，储罐里一旦是"打过纯度的水"，泵升级<b>一滴也放不出去</b>（静默失败，既不报错也不出
 * 水），不是"纯度丢了"这种温和退化。修法：把请求栈换成
 * {@code source.getFluidInTank(i).copyWithAmount(amount)}——逐罐取，纯度与那一罐严格一致
 * （多个不同纯度的水罐不会互相串味）。原版循环其余部分逐条照抄：无标签/非水时 {@code copyWithAmount}
 * 与 {@code new FluidStack(fluid, amount)} 完全等价，纯水罐无标签时行为零变化。</p>
 *
 * <p>只在 {@code DirtyWaterCompat.purityAvailable()} 为真时接管循环，YAT 缺席时整段退回原实现。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.pump.PumpUpgradeWrapper", remap = false)
public abstract class PumpUpgradeWrapperPurityMixin {

    @WrapMethod(method = "fillFromBlock", remap = false, require = 1, allow = 1)
    private boolean dimblend$stampPumpedWater(Level level, BlockPos pos, IFluidHandler handler, Entity entity,
            Operation<Boolean> original) {
        if (!DirtyWaterCompat.purityAvailable() || handler == null || level.isClientSide) {
            return original.call(level, pos, handler, entity);
        }
        int purity = WaterPurity.getBlockPurity(level, pos);
        return original.call(level, pos, new PurityStampingFluidHandler(handler, purity), entity);
    }

    /**
     * 导出（背包储罐 → 目标 handler）。参数顺序与原方法一致：第 1 个是 destination，第 2 个是
     * source（背包储罐），见字节码 {@code aload_1 = tryFluidTransfer 的 destination}。
     */
    @WrapMethod(method = "fillFluidHandler("
            + "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;"
            + "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;I)Z",
            remap = false, require = 1, allow = 1)
    private boolean dimblend$keepPurityOnExport(IFluidHandler destination, IFluidHandler source, int amount,
            Operation<Boolean> original) {
        if (!DirtyWaterCompat.purityAvailable() || destination == null || source == null) {
            return original.call(destination, source, amount);
        }
        for (int tank = 0; tank < source.getTanks(); tank++) {
            FluidStack stored = source.getFluidInTank(tank);
            if (stored.isEmpty()) {
                continue;
            }
            Boolean matched = PumpUpgradeReflection.fluidMatches(this, stored);
            if (matched == null) {
                return original.call(destination, source, amount);
            }
            if (!matched) {
                continue;
            }
            FluidStack request = stored.copyWithAmount(amount);
            if (!FluidUtil.tryFluidTransfer(destination, source, request, true).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
