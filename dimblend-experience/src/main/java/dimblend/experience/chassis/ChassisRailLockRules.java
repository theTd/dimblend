package dimblend.experience.chassis;

import java.util.EnumSet;
import java.util.Set;

/**
 * 生产轨锁的纯规则：自由度、资格窗口、世界帧刷新与生命周期判定（{@code DECISIONS.md} #2）。
 *
 * <p><b>物理目标（Option B）。</b>在 {@code dimblend:rotating} 内，对挂轨且未脱轨的车体锁
 * {@code LINEAR_Y}、{@code ANGULAR_X}、{@code ANGULAR_Z}，保持 {@code LINEAR_X}、
 * {@code LINEAR_Z}、{@code ANGULAR_Y} 完全自由。车体因此锁死标称甲板高度（标准 {@code Y=66}）
 * 与零俯仰/零横滚，不发生垂向下沉或侧倾，同时可自由沿直线、道岔（{@code HAS_BE=true}）、
 * 贝塞尔弯道（{@code create:fake_track}）、Z 向侧线运行并自由转向偏航。</p>
 *
 * <p><b>关节帧与重锚。</b>Rapier 的 generic joint 锁定的是关节帧轴（sable_rapier
 * {@code joints.rs:660-665}）。{@code orientation1} 取只保留当前车体 yaw 的世界水平帧，
 * {@code orientation2} 取车体局部单位帧；当车体水平移动超过
 * {@link #FRAME_REFRESH_HORIZONTAL_DRIFT} 格或偏航变化超过
 * {@link #FRAME_REFRESH_YAW_DEGREES} 度时，原地刷新 {@code setFrame1}，避免远距离单精度退化
 * 与大角度关节奇异点。</p>
 *
 * <p><b>生命周期。</b>{@link #decide} 只在连续 {@link #STABLE_CHECKS} 次资格检查通过后返回
 * {@code CREATE}；{@link #verify} 用于已建约束的每 tick 复核，超出保持窗口立即
 * {@code RELEASE}。纯规则不读世界、不建 joint，可直接单测。</p>
 */
public final class ChassisRailLockRules {
    /** 未登记但带物理转向架的车体在 {@code dimblend:rotating} 的默认标称甲板高度。 */
    public static final double DEFAULT_NOMINAL_DECK_HEIGHT = 66.0;
    /** 新建约束允许的 deck 高度误差（格）。 */
    public static final double CREATE_MAX_VERTICAL_ERROR = 1.0;
    /** 已建约束允许的 deck 高度误差（格）；超出即释放，交给稳定重锁。 */
    public static final double HOLD_MAX_VERTICAL_ERROR = 1.5;
    /** 新建约束允许的 pitch/roll 误差（度）。 */
    public static final double CREATE_MAX_ANGLE_DEGREES = 10.0;
    /** 已建约束允许的 pitch/roll 误差（度）。 */
    public static final double HOLD_MAX_ANGLE_DEGREES = 15.0;
    /** 触发 {@code setFrame1} 世界锚刷新的水平位移阈值（格）。 */
    public static final double FRAME_REFRESH_HORIZONTAL_DRIFT = 32.0;
    /** 触发 {@code setFrame1} 世界帧刷新的偏航角变化阈值（度）。 */
    public static final double FRAME_REFRESH_YAW_DEGREES = 15.0;
    /** 横向抖动微侧倾打开期间的世界帧偏航刷新阈值（度），避免弯道上 yaw-roll 交叉耦合。 */
    public static final double MICRO_ROLL_FRAME_REFRESH_YAW_DEGREES = 3.0;
    /** 横向抖动允许的纵向微侧倾角上限（度，{@code DECISIONS.md} {@code sway_plus_micro_roll}）。 */
    public static final double MAX_MICRO_ROLL_DEGREES = 2.5;
    /** 横向抖动允许的纵向微侧倾角上限（弧度）。 */
    public static final double MAX_MICRO_ROLL_RADIANS = Math.toRadians(MAX_MICRO_ROLL_DEGREES);
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
     * 非阻塞区块就绪检查结果（不限制轨道形状、道岔或弯道）。
     *
     * @param centerChunkReady 车体中心所在世界区块已就绪
     * @param bogeyChunksReady 所有受检转向架投影所在世界区块均已就绪
     */
    public record TrackState(boolean centerChunkReady, boolean bogeyChunksReady) {
        public boolean chunksReady() {
            return centerChunkReady && bogeyChunksReady;
        }
    }

    /**
     * 一次资格检查所需的全部只读事实。所有误差均有符号；规则内部取绝对值。
     */
    public record Context(
            boolean rotatingDimension,
            boolean loaded,
            boolean bodyValid,
            int expectedBogeys,
            int trackedBogeys,
            boolean derailed,
            TrackState track,
            double deckY,
            double nominalY,
            double pitchDegrees,
            double rollDegrees) {

        public boolean finite() {
            return Double.isFinite(deckY)
                    && Double.isFinite(nominalY)
                    && Double.isFinite(pitchDegrees)
                    && Double.isFinite(rollDegrees);
        }

        /** 与几何窗口无关的硬条件；任何一条不满足都不允许建/保持。 */
        public boolean baseEligible() {
            return rotatingDimension
                    && loaded
                    && bodyValid
                    && expectedBogeys > 0
                    && trackedBogeys == expectedBogeys
                    && !derailed
                    && track != null
                    && track.chunksReady()
                    && finite();
        }
    }

    private static final Set<Axis> LOCKED_AXES = Set.copyOf(EnumSet.of(
            Axis.LINEAR_Y, Axis.ANGULAR_X, Axis.ANGULAR_Z));

    /**
     * 支持横向抖动微侧倾的车体世界关节硬锁轴：垂向 {@code LINEAR_Y} 与车头俯仰轴 {@code ANGULAR_Z}
     * 永久硬锁；纵向翻滚轴 {@code ANGULAR_X} 由关节限位动态控制（非抖动期 {@code [0, 0]} 硬锁，
     * 抖动窗口内放开至 {@code ±MAX_MICRO_ROLL_RADIANS} 并由弹簧阻尼电机拉回水平）。
     *
     * <p>Rapier 的 {@code locked_axes} 在求解器内优先级高于 {@code limit_axes}/{@code motor_axes}
     * （{@code joint_velocity_constraint.rs:141-143}），且 {@code setLimit} 不会清除已锁标志位，
     * 因此 {@code ANGULAR_X} 必须通过 {@code [0, 0]} 限位实现硬锁，不能写入初始 {@code lockedAxes}。</p>
     */
    private static final Set<Axis> MICRO_ROLL_LOCKED_AXES = Set.copyOf(EnumSet.of(
            Axis.LINEAR_Y, Axis.ANGULAR_Z));

    public static Set<Axis> lockedAxes() {
        return LOCKED_AXES;
    }

    public static Set<Axis> microRollLockedAxes() {
        return MICRO_ROLL_LOCKED_AXES;
    }

    /** 锁垂向与俯仰/横滚，保留水平面平移（X/Z）与偏航（Y）自由。 */
    public static boolean horizontalAndYawFree() {
        return LOCKED_AXES.size() == 3
                && !LOCKED_AXES.contains(Axis.LINEAR_X)
                && LOCKED_AXES.contains(Axis.LINEAR_Y)
                && !LOCKED_AXES.contains(Axis.LINEAR_Z)
                && LOCKED_AXES.contains(Axis.ANGULAR_X)
                && !LOCKED_AXES.contains(Axis.ANGULAR_Y)
                && LOCKED_AXES.contains(Axis.ANGULAR_Z);
    }

    /** 标准装配车架蓝图判定（供登记与预期转向架布局核对使用）。 */
    public static boolean standardBlueprint(ChassisBlueprint blueprint) {
        return blueprint == ChassisBlueprint.POWER || blueprint == ChassisBlueprint.CARRIAGE;
    }

    /**
     * 标准装配锚点方块（世界 y）到 deck 顶面的标称高度。
     *
     * <p>蓝图块层与控制台同高（y=65），块顶面是 y=66；轨道块在 y=64。因此 {@code 65 -> 66}。</p>
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

    /**
     * 判断活动约束是否需要调用 {@code setFrame1} 刷新世界锚点与偏航帧。
     */
    public static boolean needsFrameRefresh(
            double dx, double dz, double yawDegrees, double frameYawDegrees) {
        return needsFrameRefresh(dx, dz, yawDegrees, frameYawDegrees, false);
    }

    public static boolean needsFrameRefresh(
            double dx,
            double dz,
            double yawDegrees,
            double frameYawDegrees,
            boolean microRollOpen) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz)
                || !Double.isFinite(yawDegrees) || !Double.isFinite(frameYawDegrees)) {
            return false;
        }
        double maxDriftSq = FRAME_REFRESH_HORIZONTAL_DRIFT * FRAME_REFRESH_HORIZONTAL_DRIFT;
        if (dx * dx + dz * dz > maxDriftSq) {
            return true;
        }
        double maxYawDelta = microRollOpen
                ? MICRO_ROLL_FRAME_REFRESH_YAW_DEGREES
                : FRAME_REFRESH_YAW_DEGREES;
        return Math.abs(normalizeDegrees(yawDegrees - frameYawDegrees)) > maxYawDelta;
    }

    public static boolean createEligible(Context context) {
        if (context == null || !context.baseEligible()) {
            return false;
        }
        return within(context, CREATE_MAX_VERTICAL_ERROR, CREATE_MAX_ANGLE_DEGREES);
    }

    public static boolean holdEligible(Context context) {
        if (context == null || !context.baseEligible()) {
            return false;
        }
        return within(context, HOLD_MAX_VERTICAL_ERROR, HOLD_MAX_ANGLE_DEGREES);
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

    private static boolean within(Context context, double vertical, double angle) {
        return Math.abs(context.deckY() - context.nominalY()) <= vertical
                && Math.abs(context.pitchDegrees()) <= angle
                && Math.abs(context.rollDegrees()) <= angle;
    }

    private ChassisRailLockRules() {
    }
}
