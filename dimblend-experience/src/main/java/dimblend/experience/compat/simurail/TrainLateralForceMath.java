package dimblend.experience.compat.simurail;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * E8 车架随机横向力的纯函数（{@code DECISIONS.md} {@code train-lateral-shake-mode = sway_plus_micro_roll}）。
 *
 * <p>速度单位与转向架 {@code visualSpeed} 一致（1 b/s = 1 m/s）；力单位为 sable 的 pN
 * （与 {@code BlockEntityPropeller#getThrust} 同单位，施加时按物理子步乘 timeStep 成冲量）。
 * 速度门槛复用 {@link TrainSpeedGateMath#isFast}（&gt;4 m/s，不含等于）。</p>
 *
 * <p>标准 {@code CARRIAGE} / {@code POWER} 外侧转向架默认 {@code allowLateralOffset = false}
 * （{@code pivotJoint} 横向自由度锁死为 {@code [0, 0]}），配合 {@code ChassisRailLock} 锁死横滚后，
 * 刚体在水平横向与侧倾上零自由度，且重型车厢（数万至数十万 kg）远重于原始 {@code 200 pN} 量级。
 * 因此在开启横向抖动时（用户拍板「水平微晃 + 限幅 ±2.5° 轻微侧倾」）：
 * <ol>
 *   <li>为 {@code !allowLateralOffset} 的挂轨转向架放开 {@code ±SWAY_LIMIT_METERS} 的水平蛇行游隙，
 *       并施加按转向架法向等效质量归一化的水平回中弹簧阻尼（保持甲板高度 {@code Y=66} 与零俯仰锁不动）；</li>
 *   <li>在横向抖动脉冲触发及回正窗口（{@link #MICRO_ROLL_WINDOW_TICKS}）内，{@code ChassisRailLock}
 *       临时放开纵向翻滚轴至 {@code ±MAX_MICRO_ROLL_DEGREES}（{@code ±2.5°}）并挂载弹簧阻尼回中电机，
 *       脉冲结束后重新收紧回 {@code [0°, 0°]} 硬锁（静止偏载不歪斜）；</li>
 *   <li>将横向激振力与横滚激振力矩分别按转向架法向等效质量与车体横滚转动惯量归一化，
 *       使轻重编组获得一致的「轻微晃动 + 车体轻颤侧倾」体感，且仍受 {@code trainLateralShakeForcePn} 线性调节；</li>
 *   <li>每次抽签以每个 Sable 结构（{@code ServerSubLevel}）为单位统一计时，仅随机挑选该结构上一个
 *       高速启用车架抽取 {@link #shouldTrigger}，不再逐车架独立抽签叠加。</li>
 * </ol>
 */
public final class TrainLateralForceMath {

    /** 横向力默认基准大小（pN）。 */
    public static final double FORCE_PN = 200.0D;
    /** 单次横向力持续时长（游戏 tick）。 */
    public static final int PUSH_DURATION_TICKS = 10;
    /** 默认判定间隔：2 秒，运行时取 Config.TRAIN_SHAKE_INTERVAL_SECONDS。 */
    public static final int INTERVAL_TICKS = 40;
    /** 概率 = 速度 / 20，达到此速度即 100%。 */
    public static final double FULL_CHANCE_SPEED = 20.0D;

    /** 锁横向转向架在开启抖动时放开的水平横向游隙上限（米，约 2 像素宽，零脱轨/擦站台风险）。 */
    public static final double SWAY_LIMIT_METERS = 0.12D;
    /** 基准转向架法向等效质量（kg）：默认 200 pN 对应 0.5 m/s² 水平激振加速度。 */
    public static final double REFERENCE_NORMAL_MASS_KG = 400.0D;
    /** 水平蛇行回中弹簧固有角频率（rad/s）。 */
    public static final double SWAY_SPRING_OMEGA = 6.0D;
    /** 水平蛇行回中阻尼比（略欠阻尼，受激后约 0.5 s 平顺回中）。 */
    public static final double SWAY_SPRING_DAMPING_RATIO = 0.65D;
    /** 单转向架回中弹簧最大等效加速度上限（m/s²），防止极端数值异常。 */
    public static final double SWAY_SPRING_MAX_ACCEL = 25.0D;

    /** 横向抖动微侧倾最大角度上限（度，对应 5 格宽车体边缘仅起伏约 ±0.11 格）。 */
    public static final double MAX_MICRO_ROLL_DEGREES = 2.5D;
    /** 横向抖动微侧倾最大弧度上限（{@code ±2.5°}）。 */
    public static final double MAX_MICRO_ROLL_RADIANS = Math.toRadians(MAX_MICRO_ROLL_DEGREES);
    /** 基准纵向横滚转动惯量（kg·m²）：默认 200 pN 对应单转向架 0.4 rad/s² 角加速度。 */
    public static final double REFERENCE_ROLL_MOMENT_KG_M2 = 500.0D;
    /** 极轻小型测试车体的横滚转动惯量下限（kg·m²），防止除零或过小值。 */
    public static final double MIN_ROLL_MOMENT_KG_M2 = 50.0D;
    /** 单次微侧倾放开总窗口（10 tick 施力 + 30 tick 阻尼平顺回正 = 40 tick / 2.0 s）。 */
    public static final int MICRO_ROLL_WINDOW_TICKS = 40;
    /** 微侧倾打开期间的世界关节回正电机刚度（AccelerationBased: ω = 6.0 rad/s -> ω² = 36）。 */
    public static final double MICRO_ROLL_MOTOR_STIFFNESS = 36.0D;
    /** 微侧倾打开期间的世界关节回正电机阻尼（AccelerationBased: 2ζω = 8.4, ζ = 0.70）。 */
    public static final double MICRO_ROLL_MOTOR_DAMPING = 8.4D;
    /** 非抖动静止/巡航期的世界关节归零电机刚度（配合 [0, 0] 硬限位消除偏载静态侧倾）。 */
    public static final double IDLE_ROLL_MOTOR_STIFFNESS = 120.0D;
    /** 非抖动静止/巡航期的世界关节归零电机阻尼。 */
    public static final double IDLE_ROLL_MOTOR_DAMPING = 22.0D;

    /** 触发概率：|速度| / 20，钳在 [0, 1]。 */
    public static double triggerChance(double absSpeed) {
        return Math.max(0.0D, Math.min(1.0D, absSpeed / FULL_CHANCE_SPEED));
    }

    /**
     * 本次是否触发。
     *
     * @param roll 均匀分布 [0, 1) 随机数
     */
    public static boolean shouldTrigger(double absSpeed, double roll) {
        return roll < triggerChance(absSpeed);
    }

    /** 单个物理子步的冲量大小（pN·s）：力 × 子步时长，子步数量变化时总冲量不变。 */
    public static double impulsePerStep(double timeStep) {
        return impulsePerStep(timeStep, FORCE_PN);
    }

    public static double impulsePerStep(double timeStep, double forcePn) {
        return forcePn * timeStep;
    }

    /**
     * 按转向架法向等效质量归一化后的单步冲量（pN·s）。
     */
    public static double impulsePerStep(double timeStep, double configuredForcePn, double normalMassKg) {
        return massNormalizedForcePn(configuredForcePn, normalMassKg) * timeStep;
    }

    /**
     * 将面板配置的 {@code trainShakeForcePn} 按转向架处法向等效质量等比放大；
     * 小于等于基准质量时不缩小，配置为 0 时严格返回 0。
     */
    public static double massNormalizedForcePn(double configuredForcePn, double normalMassKg) {
        if (configuredForcePn <= 0.0D || !Double.isFinite(configuredForcePn)) {
            return 0.0D;
        }
        if (!Double.isFinite(normalMassKg) || normalMassKg <= REFERENCE_NORMAL_MASS_KG) {
            return configuredForcePn;
        }
        return configuredForcePn * (normalMassKg / REFERENCE_NORMAL_MASS_KG);
    }

    /**
     * 判断当前车体相对轨道枢轴的水平偏移是否仍未达到本次施力方向的游隙上限。
     *
     * @param pivotOffsetLateral {@code localPivotOffset.dot(getLateral())}（车体向 {@code +lateral} 偏时为负）
     * @param sign               本次施力方向（{@code +1} 或 {@code -1}）
     */
    public static boolean canPushFurther(double pivotOffsetLateral, double sign) {
        if (sign == 0.0D || !Double.isFinite(pivotOffsetLateral)) {
            return false;
        }
        double carDisplacementAlongPush = (-pivotOffsetLateral) * Math.signum(sign);
        return carDisplacementAlongPush < SWAY_LIMIT_METERS;
    }

    /**
     * 计算为 {@code !allowLateralOffset} 转向架补上的水平回中弹簧阻尼力（N，沿世界横向基向量）。
     */
    public static double swaySpringForce(
            double pivotOffsetLateral,
            double relLateralVelocity,
            double normalMassKg,
            int lockedLateralBogeys) {
        if (!Double.isFinite(pivotOffsetLateral)
                || !Double.isFinite(relLateralVelocity)
                || !Double.isFinite(normalMassKg)
                || normalMassKg <= 0.0D) {
            return 0.0D;
        }
        int divisor = Math.max(1, lockedLateralBogeys);
        double stiffness = normalMassKg * SWAY_SPRING_OMEGA * SWAY_SPRING_OMEGA;
        double damping = normalMassKg * SWAY_SPRING_OMEGA * SWAY_SPRING_DAMPING_RATIO * 2.0D;
        double rawForce = (stiffness * pivotOffsetLateral - damping * relLateralVelocity) / divisor;
        double maxForce = normalMassKg * SWAY_SPRING_MAX_ACCEL;
        return Math.clamp(rawForce, -maxForce, maxForce);
    }

    /**
     * 按车体纵向横滚转动惯量与同轴启用转向架数归一化后的单步侧倾扭矩冲量（N·m·s）。
     */
    public static double rollImpulsePerStep(
            double timeStep, double configuredForcePn, double rollMomentKgM2, int activeBogeyCount) {
        return inertiaNormalizedRollTorque(configuredForcePn, rollMomentKgM2, activeBogeyCount) * timeStep;
    }

    /**
     * 将面板配置的 {@code trainShakeForcePn} 按车体纵向横滚转动惯量等比缩放，
     * 并按同轴转向架数的平方根分摊随机扭矩方差，使 1 转向架轻车、2 转向架客车与 6 转向架机车
     * 获得一致的 {@code ~1.8°–2.3°} 微侧倾响应。
     */
    public static double inertiaNormalizedRollTorque(
            double configuredForcePn, double rollMomentKgM2) {
        return inertiaNormalizedRollTorque(configuredForcePn, rollMomentKgM2, 1);
    }

    public static double inertiaNormalizedRollTorque(
            double configuredForcePn, double rollMomentKgM2, int activeBogeyCount) {
        if (configuredForcePn <= 0.0D || !Double.isFinite(configuredForcePn)) {
            return 0.0D;
        }
        double effectiveMoment = Double.isFinite(rollMomentKgM2) && rollMomentKgM2 > 0.0D
                ? Math.max(MIN_ROLL_MOMENT_KG_M2, rollMomentKgM2)
                : REFERENCE_ROLL_MOMENT_KG_M2;
        double bogeyScale = Math.sqrt(Math.max(1, activeBogeyCount));
        return configuredForcePn * (effectiveMoment / REFERENCE_ROLL_MOMENT_KG_M2) / bogeyScale;
    }

    /**
     * 由转向架底部横向推力方向求车体纵向翻滚扭矩符号（全部四种水平朝向均有 {@code UP × lateral == +direction}，
     * 底部向 {@code +lateral} 推时车顶向 {@code -lateral} 轻倾，对应绕 {@code +direction} 的负向旋转）。
     */
    public static double rollSignForLateralPush(double lateralSign) {
        if (lateralSign == 0.0D || !Double.isFinite(lateralSign)) {
            return 0.0D;
        }
        return -Math.signum(lateralSign);
    }

    /**
     * 判断当前纵向侧倾角是否仍未达到本次侧倾方向的 {@code ±2.5°} 上限。
     *
     * @param currentRollRadians {@code SimurailMath.angle(DIR_YP, carUp, carForward)}（右手法则绕 {@code +direction}）
     * @param rollSign           本次侧倾力矩方向（{@code +1} 或 {@code -1}）
     */
    public static boolean canRollFurther(double currentRollRadians, double rollSign) {
        if (rollSign == 0.0D || !Double.isFinite(currentRollRadians) || !Double.isFinite(rollSign)) {
            return false;
        }
        return currentRollRadians * Math.signum(rollSign) < MAX_MICRO_ROLL_RADIANS;
    }

    public static int intervalTicks(double seconds) {
        return seconds <= 0.0D ? 0 : Math.max(1, (int) Math.round(seconds * 20.0D));
    }

    /** 每个 Sable 结构在服务端主线程上的抽签倒计时状态。 */
    public static final class SubLevelDrawState {
        private int countdown = -1;
        private int lastInterval = -1;

        public int countdown() {
            return countdown;
        }

        public void reset() {
            countdown = -1;
        }
    }

    /** 单个 Sable 结构的单次抽签命中结果（选中候选车架下标与施力方向）。 */
    public record DrawOutcome(int candidateIndex, double sign) {
    }

    /**
     * 推进一步单个 Sable 结构的随机晃动倒计时：每隔 {@code intervalTicks}，
     * 仅从所有处于高速（{@link TrainSpeedGateMath#isFast}）状态的启用车架中
     * 均等随机挑选 1 个，并仅对该车架做一次 {@link #shouldTrigger} 抽取。
     *
     * @return 本 tick 触发施力时返回选中的车架下标与方向；未到抽签时间、无高速车架或未抽中返回 {@code null}
     */
    public static @Nullable DrawOutcome stepSubLevelDraw(
            SubLevelDrawState state,
            int intervalTicks,
            double[] candidateSpeeds,
            IntUnaryOperator indexSelector,
            DoubleSupplier rollSupplier,
            BooleanSupplier positiveSignSupplier) {
        if (intervalTicks != state.lastInterval) {
            state.countdown = -1;
            state.lastInterval = intervalTicks;
        }
        if (intervalTicks <= 0 || candidateSpeeds.length == 0) {
            state.countdown = -1;
            return null;
        }
        int fastCount = 0;
        for (double speed : candidateSpeeds) {
            if (TrainSpeedGateMath.isFast(speed)) {
                fastCount++;
            }
        }
        if (fastCount == 0) {
            state.countdown = -1;
            return null;
        }
        if (state.countdown < 0) {
            state.countdown = intervalTicks;
        }
        if (--state.countdown > 0) {
            return null;
        }
        state.countdown = intervalTicks;

        int fastPick = Math.floorMod(indexSelector.applyAsInt(fastCount), fastCount);
        int chosenIndex = -1;
        int seenFast = 0;
        for (int i = 0; i < candidateSpeeds.length; i++) {
            if (TrainSpeedGateMath.isFast(candidateSpeeds[i])) {
                if (seenFast == fastPick) {
                    chosenIndex = i;
                    break;
                }
                seenFast++;
            }
        }
        if (chosenIndex < 0 || !shouldTrigger(candidateSpeeds[chosenIndex], rollSupplier.getAsDouble())) {
            return null;
        }
        double sign = positiveSignSupplier.getAsBoolean() ? 1.0D : -1.0D;
        return new DrawOutcome(chosenIndex, sign);
    }

    private TrainLateralForceMath() {
    }
}
