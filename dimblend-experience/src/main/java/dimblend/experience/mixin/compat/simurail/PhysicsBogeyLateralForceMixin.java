package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.simurail.TrainForceGroups;
import dimblend.experience.compat.simurail.TrainLateralForceMath;
import dimblend.experience.compat.simurail.TrainOffStructureMath;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E8 车架随机横向力（服务端，逐车架，全维度）。
 *
 * <p>车架 |visualSpeed| &gt; 4 m/s 期间，每固定 2 秒判定一次，以 速度/20 的概率
 * （≥20 m/s 必中）对本车架所在子层级施加 1200 pN 横向力：方向为车架局部
 * {@code getLateral()}（水平且垂直于车架朝向），左右随机，持续 10 tick，
 * 作用点为车架方块中心（偏离质心，会带出摇摆/侧倾）。</p>
 *
 * <p>计时在服务端 {@code tick()V} RETURN（每游戏 tick）：速度不大于 4 m/s 即撤销计时，
 * 再次超速时重新开始 2 秒计时；已在施加中的 10 tick 不因降速中断。
 * 施力在 {@code sable$physicsTick}（每物理子步）：按 {@code FORCE × timeStep} 以冲量形式
 * 记入本模组力分组 {@link TrainForceGroups#LATERAL_FORCE}（plot 局部坐标 + 局部冲量，
 * 螺旋桨/simurail 牵引同型）；sable 在同一子步 actor 回调之后统一 {@code applyQueuedForces}，
 * 物理效果与直接施冲量等价，且 Simulated 力示意图可记录绘制。sable 物理子步在服务端主线程同步执行，
 * 与 tick 共享的计数字段无需同步。</p>
 *
 * <p>热关闭 {@code trainLateralForce} 时下一 tick 清空计时与进行中的施力。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyLateralForceMixin {

    @Shadow(remap = false)
    protected double visualSpeed;

    /** 距下次判定剩余 tick；-1 表示未计时（速度未超门槛）。 */
    @Unique
    private int dimblend$lateralCountdown = -1;

    /** 本次横向力剩余 tick；&gt;0 时物理子步施力。 */
    @Unique
    private int dimblend$lateralPushTicks;

    /** 横向力方向：+1 沿 {@code getLateral()}，-1 反向。 */
    @Unique
    private double dimblend$lateralSign;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$lateralForceTick(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (dimblend$lateralPushTicks > 0) {
            dimblend$lateralPushTicks--;
        }
        if (!Config.TRAIN_LATERAL_FORCE.get()
                || !(Sable.HELPER.getContaining(self) instanceof ServerSubLevel)) {
            dimblend$lateralCountdown = -1;
            dimblend$lateralPushTicks = 0;
            return;
        }
        double speed = Math.abs(this.visualSpeed);
        if (!TrainOffStructureMath.isFast(speed)) {
            dimblend$lateralCountdown = -1;
            return;
        }
        RandomSource random = level.getRandom();
        if (dimblend$lateralCountdown < 0) {
            dimblend$lateralCountdown = TrainLateralForceMath.INTERVAL_TICKS;
        }
        if (--dimblend$lateralCountdown > 0) {
            return;
        }
        dimblend$lateralCountdown = TrainLateralForceMath.INTERVAL_TICKS;
        if (TrainLateralForceMath.shouldTrigger(speed, random.nextDouble())) {
            dimblend$lateralPushTicks = TrainLateralForceMath.PUSH_DURATION_TICKS;
            dimblend$lateralSign = random.nextBoolean() ? 1.0D : -1.0D;
        }
    }

    @Inject(method = "sable$physicsTick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"
            + "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At("RETURN"), remap = false)
    private void dimblend$applyLateralForce(ServerSubLevel subLevel, RigidBodyHandle handle,
                                            double timeStep, CallbackInfo ci) {
        if (dimblend$lateralPushTicks <= 0) {
            return;
        }
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        // getLateral() 为子层级局部系下的水平横向单位向量；作用点用 plot 局部方块中心
        Vector3d impulse = new Vector3d(self.getLateral())
                .mul(TrainLateralForceMath.impulsePerStep(timeStep) * dimblend$lateralSign);
        Vec3 center = self.getBlockPos().getCenter();
        subLevel.getOrCreateQueuedForceGroup(TrainForceGroups.LATERAL_FORCE.get())
                .applyAndRecordPointForce(new Vector3d(center.x, center.y, center.z), impulse);
    }
}
