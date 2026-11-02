package dimblend.band;

import java.util.Collection;
import java.util.OptionalDouble;

/**
 * 区域进度条白线的纯逻辑：候选筛选（force-load 票据 + |z| ≤ 7 + 与玩家水平距离 ≤ 320）、
 * “最前一辆”的选择（|x| 最大）、收不到新位置时保留最后一次，以及按创造位区分的推送去重 key。
 * 不引用任何 Minecraft / Sable 类型，单测直接覆盖（{@code BandTrainMathTest}）。
 */
public final class BandTrainMath {
    /** 列车世界包围盒中心允许的 z 半宽（需求 #7）。与只控制 HUD 文字显隐的 16 无关。 */
    public static final int TRAIN_HALF_WIDTH = 7;

    /**
     * 白线候选与玩家的水平距离上限（格）。320 与 sable {@code SubLevelTrackingSystem} 的
     * 默认网络跟踪范围 {@code sub_level_tracking_range = 320} 对齐，不是随便取的：超出后
     * 客户端本来就丢掉整个 sublevel，所以候选窗口与今天的可见范围一致，远处站场车厢不会
     * 顶替玩家身边那辆车的白线。
     */
    public static final int TRAIN_TRACKING_RANGE = 320;

    /** 一个候选：sable 世界包围盒质心的 x/z，以及它是否有 force-load 票据。 */
    public record Candidate(double centerX, double centerZ, boolean forceLoaded) {
    }

    private BandTrainMath() {
    }

    /** 候选是否合格（不含与玩家的距离）：必须有 force-load 票据，且质心 z 落在 [-7, 7]。 */
    public static boolean isEligible(Candidate candidate) {
        return candidate != null && candidate.forceLoaded() && isWithinTrainCorridor(candidate.centerZ());
    }

    /** 质心 z 是否落在列车走廊内（含端点）；NaN 一律不算。 */
    public static boolean isWithinTrainCorridor(double centerZ) {
        return Math.abs(centerZ) <= TRAIN_HALF_WIDTH;
    }

    /** 候选与玩家的水平（XZ 平面）距离是否在 {@link #TRAIN_TRACKING_RANGE} 内（含端点）。 */
    public static boolean isWithinTrackingWindow(Candidate candidate, double playerX, double playerZ) {
        if (candidate == null) {
            return false;
        }
        double dx = candidate.centerX() - playerX;
        double dz = candidate.centerZ() - playerZ;
        return dx * dx + dz * dz <= (double) TRAIN_TRACKING_RANGE * TRAIN_TRACKING_RANGE;
    }

    /**
     * 玩家窗口内合格候选中 |x| 最大者的 x（“最前一辆”，离出生点最远）；一个都没有 → empty。
     * 玩家走远到窗口内没有车时返回 empty，由 {@link #keepLast} 沿用上一次的值（需求 #6）。
     */
    public static OptionalDouble selectFrontmostX(Collection<Candidate> candidates, double playerX,
            double playerZ) {
        if (candidates == null || candidates.isEmpty()) {
            return OptionalDouble.empty();
        }
        double bestX = Double.NaN;
        double bestAbs = -1.0;
        for (Candidate candidate : candidates) {
            if (!isEligible(candidate) || !isWithinTrackingWindow(candidate, playerX, playerZ)) {
                continue;
            }
            double abs = Math.abs(candidate.centerX());
            if (abs > bestAbs) {
                bestAbs = abs;
                bestX = candidate.centerX();
            }
        }
        return bestAbs >= 0.0 ? OptionalDouble.of(bestX) : OptionalDouble.empty();
    }

    /** 本次没有结果时沿用上一次收到的位置（需求 #6）；从未收到过 → empty。 */
    public static OptionalDouble keepLast(OptionalDouble fresh, double previous) {
        if (fresh != null && fresh.isPresent()) {
            return fresh;
        }
        return Double.isFinite(previous) ? OptionalDouble.of(previous) : OptionalDouble.empty();
    }

    /** {@code BandLaneSync} 的去重 key：区域号 + 创造位，原地切模式必须重发。 */
    public static int lanePushKey(int region, boolean creative) {
        return region * 2 + (creative ? 1 : 0);
    }
}
