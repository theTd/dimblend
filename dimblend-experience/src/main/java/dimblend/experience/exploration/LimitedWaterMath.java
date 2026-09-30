package dimblend.experience.exploration;

/**
 * G3 有限水的纯判定（不访问世界，单测覆盖）。世界侧取数见 {@link LimitedWaterRules}。
 */
public final class LimitedWaterMath {

    /** 水平四邻中「源水或冰/浮冰/蓝冰」达到此格数即保留源水。 */
    public static final int MIN_NEIGHBOURS_TO_KEEP = 2;

    /** 决定检测与否的玩家搜索半径（格，三维直线距离，自源水格中心量起）。 */
    public static final double DECIDER_RADIUS = 8.0;

    /**
     * @param waterOrIceNeighbours 水平四邻中源水/冰/浮冰/蓝冰的格数
     * @param deciderCreative 半径内最近的非旁观玩家是否为创造模式；半径内无人时为 false
     * @return true 原样写入源水；false 改写为流动 water7
     */
    public static boolean keepSource(int waterOrIceNeighbours, boolean deciderCreative) {
        return deciderCreative || waterOrIceNeighbours >= MIN_NEIGHBOURS_TO_KEEP;
    }

    /** 平方距离是否落在决定半径内（含边界）。 */
    public static boolean withinDeciderRadius(double distanceSqr) {
        return distanceSqr <= DECIDER_RADIUS * DECIDER_RADIUS;
    }

    private LimitedWaterMath() {
    }
}
