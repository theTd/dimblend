package dimblend.experience.chassis;

import java.util.EnumSet;
import java.util.Set;

/**
 * 生产轨锁的纯规则：自由度、资格窗口、生命周期判定。
 *
 * <p><b>物理目标。</b>标准车架在 {@code dimblend:rotating} 的直线 X 轨道上只允许沿世界 X
 * 平移：锁 {@code LINEAR_Y}、{@code LINEAR_Z} 与全部 {@code ANGULAR_X/Y/Z}，只留
 * {@code LINEAR_X} 自由。不是“锁高度 + 放 yaw”，也不是探针阶段的 {@code hold} 口径。</p>
 *
 * <p><b>关节帧。</b>Rapier 的 generic joint 锁定的是关节帧轴（sable_rapier
 * {@code joints.rs:660-665}），不是世界轴。因此调用方必须把
 * {@code orientation1} 设成目标车体朝向对应的世界帧（0° 或 180° 的绕 Y 旋转），
 * {@code orientation2} 设成车体局部单位帧；锁定后的目标关系是
 * {@code R_body = R_y(targetYaw)}，即 pitch/roll 归零、yaw 固定为 0°/180°。</p>
 *
 * <p><b>为什么允许 0°/180°。</b>蓝本在 180° 下对称，标准装配路径不旋转方块；车体局部
 * X 轴可能朝 +X 或 -X。两者都只允许沿世界 X 线平移，所以 180° 也是合法目标。90° 之类
 * 的朝向必须拒绝，否则会把车按住一个斜角。</p>
 *
 * <p><b>资格。</b>只认已登记、蓝图标准、车体有效、必要转向架集合精确一致、全部挂轨且
 * 未脱轨、车体中心下方的轨道块是 {@code TrackShape.XO}、所有已加载的转向架下方也是
 * XO，并且当前 deck 与标称高度、轨中心与车体中心的误差都在小窗口内。窗口之外不建
 * 约束，避免把一辆已经沉了/歪了的车猛地拉回。</p>
 *
 * <p><b>生命周期。</b>{@link #decide} 只在连续 {@link #STABLE_CHECKS} 次资格检查通过后
 * 返回 {@code CREATE}；{@link #verify} 用于已建约束的每 tick 复核，超出保持窗口就
 * {@code RELEASE}。纯规则不读世界、不建 joint，可直接单测。</p>
 */
public final class ChassisRailLockRules {
    /** 新建约束允许的 deck 高度误差（格）。 */
    public static final double CREATE_MAX_VERTICAL_ERROR = 1.0;
    /** 已建约束允许的 deck 高度误差（格）；超出即释放，交给稳定重锁。 */
    public static final double HOLD_MAX_VERTICAL_ERROR = 1.0;
    /** 新建约束允许的车体中心相对轨中心横向误差（格）。 */
    public static final double CREATE_MAX_LATERAL_ERROR = 0.6;
    /** 已建约束允许的横向误差（格）。 */
    public static final double HOLD_MAX_LATERAL_ERROR = 0.75;
    /** 新建约束允许的 yaw 误差与 pitch/roll（度）。 */
    public static final double CREATE_MAX_ANGLE_DEGREES = 10.0;
    /** 已建约束允许的 yaw 误差与 pitch/roll（度）。 */
    public static final double HOLD_MAX_ANGLE_DEGREES = 15.0;
    /** 连续多少次资格检查通过才建约束。 */
    public static final int STABLE_CHECKS = 3;
    /** 资格扫描间隔（tick）；{@link #STABLE_CHECKS} 次约等于 30 tick。 */
    public static final int CHECK_INTERVAL_TICKS = 10;

    public enum Axis {
        LINEAR_X,
        LINEAR_Y,
        LINEAR_Z,
        ANGULAR_X,
        ANGULAR_Y,
        ANGULAR_Z
    }

    public enum Decision {
        /** 还不满足新建条件，继续等。 */
        WAIT,
        /** 连续稳定检查通过，可以新建。 */
        CREATE,
        /** 已建约束仍然合法，保持。 */
        HOLD,
        /** 已建约束不再合法，立即释放。 */
        RELEASE
    }

    /**
     * 轨道检查结果。
     *
     * @param centerLoaded                车体中心下方轨道块所在区块已加载
     * @param centerStraightX             中心下方是 {@code TrackShape.XO}
     * @param allBogeysLoaded             所有必要转向架下方轨道所在区块已加载
     * @param allLoadedBogeysStraightX    所有已加载的转向架下方都是 XO；未加载的不算失败
     */
    public record TrackState(boolean centerLoaded, boolean centerStraightX,
                             boolean allBogeysLoaded, boolean allLoadedBogeysStraightX) {
        /** 新建：中心必须已加载且是 XO，所有转向架轨道都必须已加载且是 XO。 */
        public boolean createReady() {
            return centerLoaded && centerStraightX && allBogeysLoaded && allLoadedBogeysStraightX;
        }

        /** 保持：中心已加载时必须是 XO；未加载视为暂不可判定，不因缺区块释放。 */
        public boolean holdOk() {
            return (!centerLoaded || centerStraightX) && allLoadedBogeysStraightX;
        }
    }

    /**
     * 一次资格检查所需的全部只读事实。所有误差均有符号；规则内部取绝对值。
     */
    public record Context(
            boolean registered,
            boolean standardBlueprint,
            boolean rotatingDimension,
            boolean loaded,
            boolean bodyValid,
            boolean standardBogeys,
            int expectedBogeys,
            int trackedBogeys,
            boolean derailed,
            TrackState track,
            double deckY,
            double nominalY,
            double lateralError,
            double yawErrorDegrees,
            double pitchDegrees,
            double rollDegrees) {

        public boolean finite() {
            return Double.isFinite(deckY)
                    && Double.isFinite(nominalY)
                    && Double.isFinite(lateralError)
                    && Double.isFinite(yawErrorDegrees)
                    && Double.isFinite(pitchDegrees)
                    && Double.isFinite(rollDegrees);
        }

        /** 与几何窗口无关的硬条件；任何一条不满足都不允许建/保持。 */
        public boolean baseEligible() {
            return registered
                    && standardBlueprint
                    && rotatingDimension
                    && loaded
                    && bodyValid
                    && standardBogeys
                    && expectedBogeys > 0
                    && trackedBogeys == expectedBogeys
                    && !derailed
                    && track != null
                    && finite();
        }
    }

    private static final Set<Axis> LOCKED_AXES = Set.copyOf(EnumSet.of(
            Axis.LINEAR_Y, Axis.LINEAR_Z,
            Axis.ANGULAR_X, Axis.ANGULAR_Y, Axis.ANGULAR_Z));

    public static Set<Axis> lockedAxes() {
        return LOCKED_AXES;
    }

    /** 恰好只留世界 X 平移；单测钉死，防止以后把 LINEAR_X 也误锁。 */
    public static boolean onlyWorldXFree() {
        return LOCKED_AXES.size() == 5
                && !LOCKED_AXES.contains(Axis.LINEAR_X)
                && LOCKED_AXES.contains(Axis.LINEAR_Y)
                && LOCKED_AXES.contains(Axis.LINEAR_Z)
                && LOCKED_AXES.contains(Axis.ANGULAR_X)
                && LOCKED_AXES.contains(Axis.ANGULAR_Y)
                && LOCKED_AXES.contains(Axis.ANGULAR_Z);
    }

    /** 只有两种标准蓝图可以进入生产轨锁；reward wagon 不是这两种登记身份。 */
    public static boolean standardBlueprint(ChassisBlueprint blueprint) {
        return blueprint == ChassisBlueprint.POWER || blueprint == ChassisBlueprint.CARRIAGE;
    }

    /**
     * 标准装配锚点方块（世界 y）到 deck 顶面的标称高度。
     *
     * <p>蓝图块层与控制台同高（y=65），块顶面是 y=66；轨道块在 y=64。因此
     * {@code 65 -> 66}。旧车/手工登记也只走同一个公式，绝不在运行时读已下沉的当前 y。</p>
     */
    public static double nominalDeckHeight(int anchorBlockY) {
        return anchorBlockY + 1.0;
    }

    /** 归一化到 (-180, 180]。 */
    public static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized <= -180.0) {
            normalized += 360.0;
        }
        if (normalized > 180.0) {
            normalized -= 360.0;
        }
        return normalized;
    }

    /** 最近的世界 X 对齐 yaw：0° 或 180°。90° 时取 0°，误差仍是 90°，资格会拒绝。 */
    public static double nearestXAlignedYaw(double yawDegrees) {
        return Math.abs(normalizeDegrees(yawDegrees)) <= 90.0 ? 0.0 : 180.0;
    }

    public static double yawErrorDegrees(double yawDegrees) {
        return normalizeDegrees(yawDegrees - nearestXAlignedYaw(yawDegrees));
    }

    public static boolean createEligible(Context context) {
        if (context == null || !context.baseEligible() || !context.track().createReady()) {
            return false;
        }
        return within(context, CREATE_MAX_VERTICAL_ERROR, CREATE_MAX_LATERAL_ERROR,
                CREATE_MAX_ANGLE_DEGREES);
    }

    public static boolean holdEligible(Context context) {
        if (context == null || !context.baseEligible() || !context.track().holdOk()) {
            return false;
        }
        return within(context, HOLD_MAX_VERTICAL_ERROR, HOLD_MAX_LATERAL_ERROR,
                HOLD_MAX_ANGLE_DEGREES);
    }

    /** 扫描用：连续通过 {@link #STABLE_CHECKS} 次才允许 CREATE。 */
    public static Decision decide(Context context, int stableChecks) {
        if (!createEligible(context)) {
            return Decision.WAIT;
        }
        return stableChecks >= STABLE_CHECKS ? Decision.CREATE : Decision.WAIT;
    }

    /** 已建约束复核用。 */
    public static Decision verify(Context context) {
        return holdEligible(context) ? Decision.HOLD : Decision.RELEASE;
    }

    private static boolean within(Context context, double vertical, double lateral, double angle) {
        return Math.abs(context.deckY() - context.nominalY()) <= vertical
                && Math.abs(context.lateralError()) <= lateral
                && Math.abs(context.yawErrorDegrees()) <= angle
                && Math.abs(context.pitchDegrees()) <= angle
                && Math.abs(context.rollDegrees()) <= angle;
    }

    private ChassisRailLockRules() {
    }
}
