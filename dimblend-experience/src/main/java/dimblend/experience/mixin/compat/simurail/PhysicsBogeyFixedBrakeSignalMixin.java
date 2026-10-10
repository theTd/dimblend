package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * E9 刹车红石信号视为 1 级（全维度）：转向架收到任意强度红石信号，一律按 1 级信号处理。
 *
 * <p>字节码事实（运行 jar 0.0.0-a 反汇编）：{@code getControlStrength()D} =
 * clamp({@code level.getSignal(above 或 below, UP/DOWN)} / 15, 0, 1)，是转向架红石信号的
 * 唯一归一化入口——{@code getBrakeStrength()D} 按 {@code options.controlMode} 取其原值
 * （正控）或 {@code 1 -} 其（反控），{@code calculateStressApplied} 同按 controlMode 三分支
 * 取其 × {@code options.stress} 计动力网络应力（正控 control、反控 1−control、默认恒 1.0
 * 与信号无关——默认模式下本 mixin 零效果）。本 mixin 在 {@code getControlStrength} RETURN 把任意正强度压到 1/15
 * （即 1 级信号的归一值），0 保持 0：刹车力度（正控 1/15、反控 14/15）与应力同时按
 * 1 级信号生效，施力方（{@code PhysicsBogeyAxle}）与 E3/E4 沿检测
 * （{@link PhysicsBogeyBrakeSoundMixin} 读 {@code getBrakeStrength}）自动一致。
 * 注意反控模式二阶后果：刹车恒 ≥14/15 不归零，E3/E4 施闸/松闸沿不再触发（本条目接受项）。</p>
 *
 * <p>{@code getSteerValue} 直接读各面 {@code getSignal} 差值做转向，不经
 * {@code getControlStrength}，不在本条目范围。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyFixedBrakeSignalMixin {

    /** 1 级红石信号的归一化强度。 */
    private static final double LEVEL_ONE_STRENGTH = 1.0D / 15.0D;

    @ModifyReturnValue(method = "getControlStrength()D", at = @At("RETURN"), remap = false)
    private double dimblend$signalAsLevelOne(double original) {
        if (!Config.TRAIN_FIXED_BRAKE_SIGNAL.get() || original <= 0.0D) {
            return original;
        }
        return LEVEL_ONE_STRENGTH;
    }
}
