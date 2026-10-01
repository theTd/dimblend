package dimblend.experience.client;

import dimblend.experience.exploration.DepthCurse;

/**
 * z256 进度条纯函数（无游戏依赖，可单测）。数值口径见 docs/z256-bar-requirement.md。
 *
 * <p>层级 = 进度条已满次数（与远行诅咒共用同一计数，服务端同步）：
 * 进度 = {@code (|z| − 256×层级) / 256}，为负显示 0%；{@code |z|≤128} 层级清零重新开始。
 */
public final class ZProgressMath {

    /** 段长：每满 256 层级 +1。 */
    public static final int SEGMENT = DepthCurse.BOUNDARY;
    /** 清零半径：|z|≤128 层级清零。 */
    public static final int RESET_RADIUS = DepthCurse.HOLD_BAND;

    /**
     * 进度，范围 [0,1]，要求 absZ≥0。
     * {@code |z|≤128} 时按层级 0 计（与服务端清零同口径，不等同步包到达）；
     * 上限钳到 1：越过下一边界、服务端层级 +1 尚未同步到的那几 tick 显示满条。
     */
    public static float progressFor(double absZ, int tier) {
        int effectiveTier = absZ <= RESET_RADIUS ? 0 : Math.max(0, tier);
        double progress = (absZ - (double) SEGMENT * effectiveTier) / SEGMENT;
        return (float) Math.min(1.0D, Math.max(0.0D, progress));
    }

    private ZProgressMath() {
    }
}
