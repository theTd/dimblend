package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import dimblend.experience.compat.simurail.TrainBrakeBroadcast;
import dimblend.experience.compat.simurail.TrainOffStructureMath;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E3/E4 刹车/松闸气阀触发音（服务端，沿触发整车广播）。
 * E3 施闸音另受 {@link TrainOffStructureMath#isBrakeAudible} 门控：速度不大于 4 m/s 不播。
 *
 * <p>字节码事实（运行 jar 0.0.0-a 反汇编）：spec 原拟的
 * {@code getGroupBrakeStrength()} 在新版源码中，<b>运行 jar 只有
 * {@code getBrakeStrength()()D}</b>（单车架强度，源自本车架红石信号；
 * 全 jar 仅 {@code PhysicsBogeyAxle} 施力时调用，无编组级传播），故沿只能在
 * 受电车架自身上看到。用户口径要求沿触发时<b>整列车架都播</b>（2026-09-24 实测：
 * 对单架施闸只有该架响，判失败），故沿触发后经 {@link TrainBrakeBroadcast}
 * 向同列其余车架位置补播（半径 120m + 速度窗 1.5m/s，同速邻车误播为接受项）。</p>
 *
 * <p>触发点挂服务端 {@code tick()V} RETURN（每游戏 tick，主线程），而非
 * {@code sable$physicsTick}：未组装/物理未激活的车架收不到物理回调
 * （物理管线只覆盖已激活子层级），其施闸/松闸沿会被漏掉；tick 分支是超集，
 * 每游戏 tick 常跑且与红石采样同线程，无此问题。</p>
 *
 * <p>播放坐标必须是<b>世界坐标</b>：转向架 BE 的 {@code getBlockPos()} 是子层级
 * 局部坐标（plot 区），服务端 {@code level.playSound} 不做局部→世界路由
 * （sable 的 sublevel_sounds 全是客户端混入；simurail 全 jar 唯一的服务端播音
 * {@code AutomaticCouplerBlockEntity} 同样先调
 * {@code Sable.HELPER.projectOutOfSubLevel} 投影到世界坐标再播——此前直接播
 * 局部坐标，声音落在 plot 区、车旁听不见，即"刹车/松闸无声"的根因，
 * 2026-09-23 双 jar 字节码核实）。未组装（不在子层级）时投影恒等，无副作用。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyBrakeSoundMixin {

    @Shadow(remap = false)
    protected double visualSpeed;

    private static final double EDGE_EPSILON = 1.0E-6;

    /** 施闸音量：2026-09-23 用户条目"刹车音效音量减半"。 */
    private static final float BRAKE_VOLUME = 0.5F;
    /** 松闸音量：维持原参数。 */
    private static final float RELEASE_VOLUME = 1.0F;

    @Unique
    private double dimblend$lastBrake;

    @Unique
    private boolean dimblend$brakeInitialized;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$brakeEdgeSound(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (self.getLevel().isClientSide()) {
            return;
        }
        double speed = Math.abs(this.visualSpeed);
        long now = self.getLevel().getGameTime();
        TrainBrakeBroadcast.updateMember(self, speed, now);
        double brake = self.getBrakeStrength();
        if (!dimblend$brakeInitialized) {
            // 世界加载瞬间刹车可能已施加：首个服务端 tick 只建立基线不判沿，
            // 避免误播一次 train_brake
            dimblend$lastBrake = brake;
            dimblend$brakeInitialized = true;
            return;
        }
        double last = dimblend$lastBrake;
        dimblend$lastBrake = brake;
        if (!Config.TRAIN_SOUNDS.get()) {
            return;
        }
        if (last <= EDGE_EPSILON && brake > EDGE_EPSILON) {
            // E3：刹车音效只在速度大于 4 m/s 时播放；沿基线仍更新，避免低速施闸后加速误补沿
            if (TrainOffStructureMath.isBrakeAudible(speed)) {
                dimblend$broadcast(self, speed, ModSounds.TRAIN_BRAKE.get(), BRAKE_VOLUME, 1.0F, now);
            }
        } else if (last > EDGE_EPSILON && brake <= EDGE_EPSILON) {
            dimblend$broadcast(self, speed, ModSounds.BRAKE_RELEASE.get(), RELEASE_VOLUME, 1.0F, now);
        }
    }

    @Unique
    private static void dimblend$broadcast(PhysicsBogeyBlockEntity bogey, double speed,
                                           SoundEvent event, float volume, float pitch, long nowTick) {
        // 车勾同型：局部中心先投影到世界坐标再播，否则声音落在 plot 区
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(bogey.getLevel(), bogey.getBlockPos().getCenter());
        TrainBrakeBroadcast.broadcast(bogey.getLevel(), world, speed, event, volume, pitch, nowTick);
    }
}
