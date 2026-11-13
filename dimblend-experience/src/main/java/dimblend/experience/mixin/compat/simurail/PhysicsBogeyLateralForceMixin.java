package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.api.math.Basis3d;
import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.ChassisRailLock;
import dimblend.experience.compat.simurail.TrainForceGroups;
import dimblend.experience.compat.simurail.TrainLateralForceMath;
import dimblend.experience.compat.simurail.TrainSpeedGateMath;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E8 车架随机横向力（服务端，逐车架，全维度；{@code DECISIONS.md} {@code sway_plus_micro_roll}）。
 *
 * <p>针对标准车架外侧转向架 {@code allowLateralOffset = false}（{@code LINEAR_Z} 硬锁 {@code [0, 0]}）
 * 与 {@code ChassisRailLock} 共同锁死全车横向/侧倾自由度、以及重型车厢质量远超 {@code 1200 pN} 的问题：
 * <ul>
 *   <li>当横向抖动开启时，为 {@code !allowLateralOffset} 的挂轨转向架放开 {@code ±0.12 m} 水平蛇行游隙，
 *       并在 {@code updateForces} 内施加按法向等效质量缩放的水平回中弹簧阻尼；</li>
 *   <li>脉冲触发时同步通知 {@link ChassisRailLock#notifyLateralShakePulse(ServerSubLevel)} 在
 *       {@code dimblend:rotating} 内临时放开 {@code ±2.5°} 弹簧阻尼微侧倾窗口；</li>
 *   <li>同时施加质量归一化的水平激振冲量与转动惯量归一化的纵向侧倾力矩冲量，各自在达到
 *       {@code ±0.12 m} / {@code ±2.5°} 上限时停止同向继续猛推。</li>
 * </ul>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyLateralForceMixin {

    @Shadow(remap = false)
    private double visualSpeed;

    @Shadow(remap = false)
    private PhysicsBogeyOptions options;

    @Shadow(remap = false)
    private GenericConstraintHandle pivotJoint;

    @Shadow(remap = false)
    private Vector3dc localCenter;

    @Shadow(remap = false)
    private Vector3d localPivotOffset;

    @Shadow(remap = false)
    private Basis3d globalBasis;

    @Shadow(remap = false)
    private Vector3d globalRelLinVel;

    @Shadow(remap = false)
    private Vector3d queuedForce;

    @Shadow(remap = false)
    private boolean isActive() {
        throw new AssertionError();
    }

    /** 距下次判定剩余 tick；-1 表示未计时（速度未超门槛）。 */
    @Unique
    private int dimblend$lateralCountdown = -1;

    /** 本次横向力剩余 tick；&gt;0 时物理子步施力。 */
    @Unique
    private int dimblend$lateralPushTicks;

    /** 横向力方向：+1 沿 {@code getLateral()}，-1 反向。 */
    @Unique
    private double dimblend$lateralSign;

    @Unique
    private int dimblend$lastInterval;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$lateralForceTick(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (dimblend$lateralPushTicks > 0) {
            dimblend$lateralPushTicks--;
        }
        int interval = TrainLateralForceMath.intervalTicks(Config.TRAIN_SHAKE_INTERVAL_SECONDS.get());
        if (interval != dimblend$lastInterval) {
            dimblend$lateralCountdown = -1;
            dimblend$lastInterval = interval;
        }
        if (!dimblend$isLateralShakeConfigured(interval)
                || !(Sable.HELPER.getContaining(self) instanceof ServerSubLevel subLevel)) {
            dimblend$lateralCountdown = -1;
            dimblend$lateralPushTicks = 0;
            return;
        }
        double speed = Math.abs(this.visualSpeed);
        if (!TrainSpeedGateMath.isFast(speed)) {
            dimblend$lateralCountdown = -1;
            return;
        }
        RandomSource random = level.getRandom();
        if (dimblend$lateralCountdown < 0) {
            dimblend$lateralCountdown = interval;
        }
        if (--dimblend$lateralCountdown > 0) {
            return;
        }
        dimblend$lateralCountdown = interval;
        if (TrainLateralForceMath.shouldTrigger(speed, random.nextDouble())) {
            dimblend$lateralPushTicks = TrainLateralForceMath.PUSH_DURATION_TICKS;
            dimblend$lateralSign = random.nextBoolean() ? 1.0D : -1.0D;
            ChassisRailLock.notifyLateralShakePulse(subLevel);
        }
    }

    @Inject(
            method = "updatePivotLimits(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;D)V",
            at = @At("RETURN"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$openHorizontalSwayPlay(
            ServerSubLevel subLevel, double timeStep, CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (this.pivotJoint == null
                || !this.options.enabled
                || !self.hasTrack()
                || !dimblend$isLateralShakeEnabled()) {
            return;
        }
        if (!this.options.allowLateralOffset) {
            this.pivotJoint.setLimit(
                    ConstraintJointAxis.LINEAR_Z,
                    -TrainLateralForceMath.SWAY_LIMIT_METERS,
                    TrainLateralForceMath.SWAY_LIMIT_METERS);
        }
        if (!this.options.allowYawOffset || !this.options.allowPitchOffset) {
            this.pivotJoint.setLimit(
                    ConstraintJointAxis.ANGULAR_X,
                    -TrainLateralForceMath.MAX_MICRO_ROLL_RADIANS,
                    TrainLateralForceMath.MAX_MICRO_ROLL_RADIANS);
        }
    }

    @Inject(
            method = "updateForces(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Quaterniond;transformInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
                    ordinal = 0),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$applyHorizontalSwayCenteringSpring(
            ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep, CallbackInfo ci) {
        if (this.options.allowLateralOffset
                || !this.options.enabled
                || !this.isActive()
                || this.localPivotOffset == null
                || !dimblend$isLateralShakeEnabled()) {
            return;
        }
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        Vector3dc lateral = self.getLateral();
        double invNormalMass = subLevel.getMassTracker().getInverseNormalMass(this.localCenter, lateral);
        if (!(invNormalMass > 1.0E-9D) || !Double.isFinite(invNormalMass)) {
            return;
        }
        double normalMass = 1.0D / invNormalMass;
        double offset = this.localPivotOffset.dot(lateral);
        double velocity = this.globalRelLinVel.dot(this.globalBasis.lateral);
        int lockedCount = dimblend$countLockedLateralBogeys(subLevel, self);
        double forceMag = TrainLateralForceMath.swaySpringForce(offset, velocity, normalMass, lockedCount);
        this.queuedForce.fma(forceMag * timeStep, this.globalBasis.lateral);
    }

    @Inject(
            method = "sable$physicsTick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At("RETURN"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$applyLateralForce(
            ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep, CallbackInfo ci) {
        if (dimblend$lateralPushTicks <= 0 || !dimblend$isLateralShakeEnabled()) {
            return;
        }
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!this.options.enabled || !this.isActive()) {
            return;
        }
        ChassisRailLock.notifyLateralShakePulse(subLevel);
        int configuredForcePn = Config.TRAIN_SHAKE_FORCE_PN.get();
        Vector3dc lateral = self.getLateral();
        var forceGroup = subLevel.getOrCreateQueuedForceGroup(TrainForceGroups.LATERAL_FORCE.get());

        if (this.localPivotOffset == null
                || TrainLateralForceMath.canPushFurther(
                        this.localPivotOffset.dot(lateral), dimblend$lateralSign)) {
            double invNormalMass = subLevel.getMassTracker().getInverseNormalMass(this.localCenter, lateral);
            double normalMass = invNormalMass > 1.0E-9D && Double.isFinite(invNormalMass)
                    ? 1.0D / invNormalMass
                    : TrainLateralForceMath.REFERENCE_NORMAL_MASS_KG;
            double impulseMag = TrainLateralForceMath.impulsePerStep(
                    timeStep, configuredForcePn, normalMass) * dimblend$lateralSign;
            if (impulseMag != 0.0D) {
                Vector3d impulse = new Vector3d(lateral).mul(impulseMag);
                Vec3 center = self.getBlockPos().getCenter();
                forceGroup.applyAndRecordPointForce(new Vector3d(center.x, center.y, center.z), impulse);
            }
        }

        double rollSign = TrainLateralForceMath.rollSignForLateralPush(dimblend$lateralSign);
        if (rollSign != 0.0D) {
            Vector3dc localDir = self.getDirection();
            Quaterniondc orientation = subLevel.logicalPose().orientation();
            Vector3d carUp = orientation.transform(new Vector3d(SimurailMath.DIR_YP));
            Vector3d carForward = orientation.transform(new Vector3d(localDir));
            double currentRollRad = SimurailMath.angle(SimurailMath.DIR_YP, carUp, carForward);
            if (TrainLateralForceMath.canRollFurther(currentRollRad, rollSign)) {
                double rollMoment = SimurailMath.moment(
                        subLevel.getMassTracker(), this.localCenter, localDir);
                int bogeyCount = dimblend$countSameAxisBogeys(subLevel, self);
                double rollImpulseMag = TrainLateralForceMath.rollImpulsePerStep(
                        timeStep, configuredForcePn, rollMoment, bogeyCount) * rollSign;
                if (rollImpulseMag != 0.0D) {
                    forceGroup.getForceTotal().applyTorqueImpulse(
                            new Vector3d(localDir).mul(rollImpulseMag));
                }
            }
        }
    }

    @Unique
    private boolean dimblend$isLateralShakeEnabled() {
        if (!Config.isLoaded()) {
            return false;
        }
        int interval = TrainLateralForceMath.intervalTicks(Config.TRAIN_SHAKE_INTERVAL_SECONDS.get());
        return dimblend$isLateralShakeConfigured(interval);
    }

    @Unique
    private boolean dimblend$isLateralShakeConfigured(int intervalTicks) {
        return Config.TRAIN_LATERAL_FORCE.get()
                && intervalTicks > 0
                && Config.TRAIN_SHAKE_FORCE_PN.get() > 0;
    }

    @Unique
    private int dimblend$countLockedLateralBogeys(
            ServerSubLevel subLevel, PhysicsBogeyBlockEntity self) {
        int count = 0;
        var selfAxis = self.getFacing().getAxis();
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (actor instanceof PhysicsBogeyBlockEntity bogey
                    && bogey.getOptions().enabled
                    && !bogey.getOptions().allowLateralOffset
                    && bogey.hasTrack()
                    && bogey.getFacing().getAxis() == selfAxis) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    @Unique
    private int dimblend$countSameAxisBogeys(
            ServerSubLevel subLevel, PhysicsBogeyBlockEntity self) {
        int count = 0;
        var selfAxis = self.getFacing().getAxis();
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (actor instanceof PhysicsBogeyBlockEntity bogey
                    && bogey.getOptions().enabled
                    && bogey.hasTrack()
                    && bogey.getFacing().getAxis() == selfAxis) {
                count++;
            }
        }
        return Math.max(1, count);
    }
}
