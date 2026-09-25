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
 * D5 交流发电机无输入自放电（2026-09-23 新条目；2026-09-25 用户回标收敛）：
 * 仅当本 tick 不产电时，内部储存 FE 自行减少约每秒 5000（每 tick 250）。
 *
 * <p>字节码基线（运行 jar createaddition 1.5.10，javap 核实）：
 * {@code AlternatorBlockEntity.tick()V} 为目标类自有方法；offset 4-11
 * {@code level==null} 早退、12-22 {@code isClientSide} 早退，之后为服务端主体
 * （产能 + 六面输出）。注入点取 HEAD 自带 ServerLevel 守卫，与产出逻辑
 * （offset 39 {@code getSpeed()} 读数、产能门
 * {@code |speed|>0 && isSpeedRequirementFulfilled()}）同 tick 内并存。</p>
 *
 * <p>漏电判据取产能门的精确取反——{@code |getSpeed|>0 && isSpeedRequirementFulfilled()}
 * 为真即产电、本 tick 不漏电；为假（停转 0rpm / 过载·冻结网络读数归零 /
 * 最低转速门未满足）才漏电。有机械输入即产电（哪怕 1rpm 也有约 1FE/t），
 * 不再与产电并发扣减；扣到 0 为止
 * （{@code internalConsumeEnergy} 按存量钳定）。此前 |rpm|&lt;16 口径下，
 * 1~35rpm 区间产电（约 1.4FE/t/rpm）跑不赢 50FE/t 漏电、净减少，
 * 且 16~35rpm 同样入不敷出，表现为"有输入也一直放"，故收敛为无输入才放。</p>
 */
@Mixin(AlternatorBlockEntity.class)
public abstract class AlternatorIdleDrainMixin {

    /** 每 tick 流失量：250 FE × 20 tick = 5000 FE/s。 */
    @Unique
    private static final int dimblend$DRAIN_PER_TICK = 250;

    /** 目标为 protected final（构造器初始化），shadow 按只读使用、不匹配 finality。 */
    @Shadow(remap = false)
    protected InternalEnergyStorage energy;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$drainWhenIdle(CallbackInfo ci) {
        AlternatorBlockEntity self = (AlternatorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!Config.ALTERNATOR_IDLE_DRAIN.get()) {
            return;
        }
        if (dimblend$isProducing(self)) {
            return;
        }
        this.energy.internalConsumeEnergy(dimblend$DRAIN_PER_TICK);
    }

    /**
     * 与 {@code AlternatorBlockEntity.tick} 产能门逐字一致：
     * {@code |speed|>0 && isSpeedRequirementFulfilled()}。
     * 为真 = 本 tick 有产电，不漏电；为假 = 无有效输入，才漏电。
     */
    @Unique
    private static boolean dimblend$isProducing(AlternatorBlockEntity self) {
        return Math.abs(self.getSpeed()) > 0 && self.isSpeedRequirementFulfilled();
    }
}
