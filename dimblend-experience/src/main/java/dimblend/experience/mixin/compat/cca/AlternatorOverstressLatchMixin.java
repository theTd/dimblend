package dimblend.experience.mixin.compat.cca;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mrh0.createaddition.blocks.alternator.AlternatorBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cca.AlternatorOverstressLatch;
import dimblend.experience.compat.cca.AlternatorOverstressLatchAccess;
import dimblend.experience.compat.cca.MotorOverstressLatch;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D8 交流发电机过载解列锁存（机制与字节码基线见
 * {@link AlternatorOverstressLatch}；2026-10-14 新条目，用户拍板）。
 *
 * <p>进入（tick HEAD，服务端 + 开关开启）：{@code isOverStressed()} 且理论转速非零
 * 且本机已在网络账本上（{@code members.containsKey(self)}——读档首 tick 抢入
 * members 会导致 addSilently 跳过存档份额结算、unloadedStress 幻影残留并落盘，
 * 机理见 {@link AlternatorOverstressLatch} 类注释）
 * → 置锁存、{@code updateStressFor(self, 0)} 即时清零网络账本中本机需求（同时触发
 * updateStress→sync，过载当 tick 解除）、{@code sendData()} 同步客户端、当 tick 即播
 * 粒子/音效（复用 D7 {@link MotorOverstressLatch#tickEffects}：每 tick 中心
 * enchanted_hit×5，每 20 tick motor_overstress 音量 0.5）。</p>
 *
 * <p>锁存期：{@code calculateStressApplied} 钩子在 RETURN 把读数压 0——网络
 * split/merge 重 {@code add()} 等重缓存路径仍得 0，锁不被拓扑变化顶掉；客户端经
 * 同步字段同判，护目镜应力段随 impact=0 整段隐藏（KBE.addToGoggleTooltip 的
 * Mth.equal 早退）。FE 产出同样锁 0（2026-10-15 拍板，原"锁存期照常空转产 FE"
 * 口径作废）：tick 内产能率 {@code getEnergyProductionRate} 调用点压 0
 * （{@code internalProduceEnergy(0)} 照走、等于不产；只锁产出、不锁输出——buffer
 * 存量（默认 5000 FE）继续经六面输出 loop 流出直至放空（约 1 tick），属拍板语义）；
 * 护目镜产能行经同签名 MEV 同压 0（客户端锁存字段经同步包回写，显示与实际一致）。
 * D5 漏电门（产能门 {@code |getSpeed|>0 && isSpeedRequirementFulfilled()} 的取反）
 * 不受影响——锁存期转速非零、门仍为真，不会并发漏电（交互已评估，见 spec D8）。</p>
 *
 * <p>退出（2026-10-15 拍板改口径，原"理论转速归零"作废）：理论转速与进入时记录值
 * （{@code dimblend$latchedSpeed}）不同即解锁——精确比较、不加阈值，停机归零天然满足
 * （记录值必非零），为旧判据超集；过载冻结不动 speed 字段，不会误判。解锁→清锁存、
 * {@code updateStressFor} 恢复真实 impact（转速 0 下账本不变、无 sync 抖动）、
 * sendData。开关关闭同样清锁存恢复。再次满足进入判据即重锁（重录记录值、重播进入
 * 音效/粒子、latchAge 归零）——柴油机 B3 波动造成的频繁解锁→重锁为拍板接受项
 * （锁存音效粒子本就常播提醒玩家处理，见 spec D8）。纯内存不写磁盘 NBT，
 * 区块卸载/重进按新进入重判。</p>
 */
@Mixin(AlternatorBlockEntity.class)
public abstract class AlternatorOverstressLatchMixin implements AlternatorOverstressLatchAccess {

    /** D8 锁存标志（服务端权威；客户端仅经同步包回写，用于护目镜显示）。 */
    @Unique
    private boolean dimblend$overstressLatched;

    /**
     * D8 进入锁存时的理论转速记录值（2026-10-15 拍板退出判据：与之不同即解锁；
     * 纯内存不落盘——锁存标志本身就不落盘，每次进入重录）。
     */
    @Unique
    private float dimblend$latchedSpeed;

    /** D8 锁存持续 tick 数（粒子每 tick，音效在 age%20==0 时播，含进入当 tick）。 */
    @Unique
    private int dimblend$latchAge;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$driveOverstressLatch(CallbackInfo ci) {
        AlternatorBlockEntity self = (AlternatorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.ALTERNATOR_OVERSTRESS_LATCH.get()) {
            dimblend$clearLatchAndRestore(self);
            return;
        }
        if (dimblend$overstressLatched) {
            if (AlternatorOverstressLatch.shouldReset(self.getTheoreticalSpeed(), dimblend$latchedSpeed)) {
                dimblend$clearLatchAndRestore(self);
                return;
            }
            MotorOverstressLatch.tickEffects(serverLevel, self.getBlockPos(), dimblend$latchAge);
            dimblend$latchAge++;
            return;
        }
        if (self.hasNetwork() && AlternatorOverstressLatch.shouldEnter(
                self.isOverStressed(), self.getTheoreticalSpeed(),
                self.getOrCreateNetwork().members.containsKey(self))) {
            dimblend$overstressLatched = true;
            dimblend$latchedSpeed = self.getTheoreticalSpeed();
            dimblend$latchAge = 0;
            self.getOrCreateNetwork().updateStressFor(self, 0.0F);
            self.sendData();
            MotorOverstressLatch.tickEffects(serverLevel, self.getBlockPos(), dimblend$latchAge);
            dimblend$latchAge++;
        }
    }

    /**
     * 锁存期应力需求归零的兜底钩子：{@code enter/exit} 已显式
     * {@code updateStressFor} 改 members 缓存，本钩子覆盖锁存期任何重缓存路径
     * （{@code KineticNetwork.add()} 重读本方法）；护目镜应力段随 impact=0 隐藏。
     * 锁存标志本身即开关（开关关闭时锁存已被清，客户端字段由同步包回写）。
     */
    @ModifyReturnValue(method = "calculateStressApplied()F", at = @At("RETURN"))
    private float dimblend$zeroStressWhenLatched(float original) {
        return dimblend$overstressLatched ? 0.0F : original;
    }

    /**
     * D8 锁存期 FE 产出锁 0（2026-10-15 拍板）：{@code tick()V} 内唯一
     * {@code getEnergyProductionRate(I)I} 调用点（createaddition 1.5.10 javap 核实：
     * offset 67 invokestatic，本类 public static 方法、全类无重载），锁存时产能率
     * 压 0——{@code internalProduceEnergy(0)} 照走、buffer 不再入账。只锁产出、不锁
     * 输出：存量继续经六面输出 loop 流出直至放空（约 1 tick）。产能门
     * （{@code |getSpeed|>0 && isSpeedRequirementFulfilled()}）不动，D5 漏电门不受影响。
     * 锁存标志本身即开关（开关关闭时锁存已在 HEAD 清掉，与应力钩子同例）。
     */
    @ModifyExpressionValue(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mrh0/createaddition/blocks/alternator/AlternatorBlockEntity;getEnergyProductionRate(I)I",
                    remap = false))
    private int dimblend$zeroProductionWhenLatched(int rate) {
        return dimblend$overstressLatched ? 0 : rate;
    }

    /**
     * D8 护目镜产能行与实产一致：{@code addToGoggleTooltip(Ljava/util/List;Z)Z} 为本类
     * 自有方法、内有一处同签名调用点（javap 核实 offset 47）；锁存期显示压 0——锁存时
     * 实产为 0，原样会按转速算出非零产能、与实际不符。客户端锁存字段经同步包回写
     * （见类注释），本钩子与 {@link #dimblend$zeroProductionWhenLatched} 同判定。
     */
    @ModifyExpressionValue(
            method = "addToGoggleTooltip(Ljava/util/List;Z)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mrh0/createaddition/blocks/alternator/AlternatorBlockEntity;getEnergyProductionRate(I)I",
                    remap = false))
    private int dimblend$zeroGoggleProductionWhenLatched(int rate) {
        return dimblend$overstressLatched ? 0 : rate;
    }

    /** 清锁存并恢复网络账本中本机真实需求（先清标志再读 calculateStressApplied）。 */
    @Unique
    private void dimblend$clearLatchAndRestore(AlternatorBlockEntity self) {
        if (!dimblend$overstressLatched) {
            return;
        }
        dimblend$overstressLatched = false;
        dimblend$latchAge = 0;
        if (self.hasNetwork()) {
            self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
        }
        self.sendData();
    }

    @Override
    public boolean dimblend$isOverstressLatched() {
        return dimblend$overstressLatched;
    }

    @Override
    public void dimblend$setOverstressLatchedFromSync(boolean latched) {
        this.dimblend$overstressLatched = latched;
    }
}
