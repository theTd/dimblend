package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.alternator.AlternatorBlockEntity;
import com.mrh0.createaddition.energy.InternalEnergyStorage;
import dimblend.experience.Config;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D5 交流发电机低转速流失（2026-09-23 新条目，2026-09-23 用户改量）：
 * 转速输入 |rpm| &lt; 16 时，内部储存 FE 自行减少约每秒 1000（每 tick 50）。
 *
 * <p>字节码基线（运行 jar createaddition 1.5.10，javap 核实）：
 * {@code AlternatorBlockEntity.tick()V} 为目标类自有方法；offset 4-11
 * {@code level==null} 早退、12-22 {@code isClientSide} 早退，之后为服务端主体
 * （产能 + 六面输出）。注入点取 HEAD 自带 ServerLevel 守卫，与产出逻辑
 * （offset 39 {@code getSpeed()} 读数、产能门
 * {@code |speed|>0 && isSpeedRequirementFulfilled()}）同 tick 内并存：
 * &lt;16rpm 时产能本就因整型截断≈0，扣减独立于产能分支。</p>
 *
 * <p>读数口径与产能门一致用 {@code getSpeed()}（过载/冻结网络返回 0）：
 * "不出力的发电机漏电"与"转速不足漏电"归并为同一判据——
 * {@code |getSpeed()| < 16} 涵盖停转（0）与低速两态，扣到 0 为止
 * （{@code internalConsumeEnergy} 按存量钳定）。</p>
 */
@Mixin(AlternatorBlockEntity.class)
public abstract class AlternatorIdleDrainMixin {

    /** 转速输入低于此值开始流失（rpm，绝对值；16 恰好不流失）。 */
    @Unique
    private static final float dimblend$DRAIN_BELOW_RPM = 16.0F;

    /** 每 tick 流失量：50 FE × 20 tick = 1000 FE/s。 */
    @Unique
    private static final int dimblend$DRAIN_PER_TICK = 50;

    /** 目标为 protected final（构造器初始化），shadow 按只读使用、不匹配 finality。 */
    @Shadow(remap = false)
    protected InternalEnergyStorage energy;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$drainBelowRatedInput(CallbackInfo ci) {
        AlternatorBlockEntity self = (AlternatorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!Config.ALTERNATOR_IDLE_DRAIN.get()) {
            return;
        }
        if (Math.abs(self.getSpeed()) < dimblend$DRAIN_BELOW_RPM) {
            this.energy.internalConsumeEnergy(dimblend$DRAIN_PER_TICK);
        }
    }
}
