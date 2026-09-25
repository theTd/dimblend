package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.block.GutterOutlet.GutterOutletBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 集水器降水收集降速（Create: Fluid 2.0.1 字节码基线，经 javap 反汇编核实）：
 * {@code GutterOutletBlockEntity.accumulateAndFill(FluidStack,Z)V} 内降水分支每 tick
 * 向 {@code precipitationAccumulator} 累加 {@code 50}，再以 {@code accumulator/20}
 * 换算成 mB 灌入，即 {@code 50 mB/s}；钟乳石分支累加 {@code 5}（{@code 5 mB/s}），
 * 除数一处与两分支余数回扣共三处 {@code 20} 不动。
 *
 * <p>本 Mixin 只改该方法内唯一的 {@code 50} 常量为 {@code 1}，即降水（含雨水的水和
 * 降雪的细雪流体，走的都是 {@code isPrecipitation=true} 分支）降为 {@code 1 mB/s}
 *（约 33 分 20 秒灌满 2000mB 内胆）。钟乳石 {@code 5}、三处 {@code 20}、
 * {@code handleDrainToBelow} 的 {@code 50 mB/tick} 向下排液均不受影响
 *（method 过滤器限定在 {@code accumulateAndFill} 内）。</p>
 *
 * <p>智能集水器无需第二个 Mixin：
 * {@code SmartGutterOutletBlockEntity.accumulateAndFill} 只是先做过滤器检查再
 * {@code invokespecial} 调父类本方法，父类单点修改即覆盖两者。</p>
 */
@Mixin(GutterOutletBlockEntity.class)
public abstract class GutterOutletPrecipitationMixin {

    /**
     * 降水累加量 50 → 1。handler 只收方法内唯一的 int 50（降水增量），
     * 方法内三处 20（一处除数、两处余数回扣）与钟乳石 5 不匹配本 selector。
     */
    @ModifyConstant(
            method = "accumulateAndFill(Lnet/neoforged/neoforge/fluids/FluidStack;Z)V",
            constant = @Constant(intValue = 50),
            remap = false)
    private int dimblend$precipitationOneMbPerSecond(int original) {
        return 1;
    }
}
