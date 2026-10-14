package dimblend.experience.mixin.compat.cca;

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
 * Mth.equal 早退）。本机照常空转产 FE（只摘需求、不断产能）。</p>
 *
 * <p>退出：理论转速归零（网络停驱；过载冻结不动 speed 字段，不会误判）→ 清锁存、
 * {@code updateStressFor} 恢复真实 impact（转速 0 下账本不变、无 sync 抖动）、
 * sendData。开关关闭同样清锁存恢复。纯内存不写磁盘 NBT，区块卸载/重进按新进入重判。</p>
 */
@Mixin(AlternatorBlockEntity.class)
public abstract class AlternatorOverstressLatchMixin implements AlternatorOverstressLatchAccess {

    /** D8 锁存标志（服务端权威；客户端仅经同步包回写，用于护目镜显示）。 */
    @Unique
    private boolean dimblend$overstressLatched;

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
            if (AlternatorOverstressLatch.shouldReset(self.getTheoreticalSpeed())) {
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
