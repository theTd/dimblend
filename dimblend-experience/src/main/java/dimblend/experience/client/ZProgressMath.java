package dimblend.experience.client;

/**
 * z256 进度条纯函数（无游戏依赖，可单测）：段内进度与回程瞬时归零。
 * 数值口径见 {@link ZProgressHud} 与 docs/z256-bar-requirement.md。
 */
public final class ZProgressMath {

    /** 分段长度：每 256 一段。 */
    public static final int SEGMENT = 256;
    /** 段内分界：回程时段内位置大于此值强制 0%（恰等于 128 按公式自然显示 50%）。 */
    public static final int HALF = 128;

    /** 段内进度（回程瞬时归零，无锁存），范围 [0,1），要求 absZ≥0（调用方采样与渲染前均已 Math.abs）。 */
    public static float progressFor(double absZ, boolean returning) {
        double into = absZ % SEGMENT;
        if (returning && into > HALF) {
            return 0.0F;
        }
        return (float) (into / SEGMENT);
    }

    private ZProgressMath() {
    }
}
