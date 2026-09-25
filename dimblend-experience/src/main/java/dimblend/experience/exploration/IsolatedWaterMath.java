package dimblend.experience.exploration;

/**
 * 孤立源水阈值（纯函数，无游戏依赖，可单测）：新写入的纯水源水平四邻源水数
 * <2 则降级为流动 water7，≥2 才保留源水。数值口径见 {@link IsolatedWaterRules}。
 */
public final class IsolatedWaterMath {

    /** 保留源水所需的最小水平四邻源水数（<2 降级，≥2 保留）。 */
    public static final int MIN_SOURCE_NEIGHBORS_TO_KEEP = 2;

    /** 四邻源水数达到保留线才保留源水。 */
    public static boolean keepSource(int sourceNeighbors) {
        return sourceNeighbors >= MIN_SOURCE_NEIGHBORS_TO_KEEP;
    }

    private IsolatedWaterMath() {
    }
}
