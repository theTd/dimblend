package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dimblend.experience.compat.create.AndesiteFunnelRedstoneRules;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 安山漏斗的工作模式判定忽略 {@code POWERED}（2026-11-25 legacy 兜底）。
 *
 * <p>{@code FunnelBlockEntity#determineCurrentMode()} 第二段就是
 * "POWERED 为真 → {@code Mode.PAUSED}"（{@code javap -p -c} 核实）。这里只把这一个
 * "读 POWERED" 的结果替换掉：安山漏斗一律当成 {@code false}，其余分支
 *（皮带形态、EXTRACTING → EXTRACT/COLLECT、INVALID）原封不动。
 * 用 {@code @ModifyExpressionValue} 而不是重写整个方法，就是为了绝不碰别的漏斗。</p>
 */
@Mixin(FunnelBlockEntity.class)
public abstract class AndesiteFunnelModeMixin {

    @ModifyExpressionValue(
            method = "determineCurrentMode",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getOptionalValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/util/Optional;"),
            require = 1, allow = 1)
    private Optional<Boolean> dimblend$ignorePoweredForMode(Optional<Boolean> original) {
        FunnelBlockEntity self = (FunnelBlockEntity) (Object) this;
        if (self.getLevel() == null || !AndesiteFunnelRedstoneRules.isAndesiteFunnel(self.getBlockState())) {
            return original;
        }
        return Optional.of(Boolean.FALSE);
    }
}
