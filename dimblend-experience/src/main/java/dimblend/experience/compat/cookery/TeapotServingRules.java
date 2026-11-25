package dimblend.experience.compat.cookery;

/**
 * 茶壶（Kaleidoscope Cookery {@code teapot}）一份 = 4 杯的数值口径
 * （Audit Default #9：保持 1000 mB 流体 + 12 份原料不变，只改 5 处硬编码 12）。
 *
 * <p>原版：一份原料产出 12 个成品，物品条按 {@code 13.0f * count / 12.0f} 画。
 * 改成 4 之后，同一份原料只产出 4 个成品，物品条必须按 4 归一；旧存档里 count 仍可能
 * 大于 4，所以条宽还要再夹到 {@code [0, 13]}，避免画出格。</p>
 *
 * <p>纯函数，零 Minecraft 依赖，便于单元测试（{@code TeapotServingRulesTest}）。</p>
 */
public final class TeapotServingRules {

    /** 一壶（一份原料）产出杯数。 */
    public static final int SERVINGS_PER_POT = 4;

    /** 物品条总宽（与原版 {@code getBarWidth} 一致，13 格）。 */
    public static final int BAR_FULL_WIDTH = 13;

    /** 物品条归一用的分母（原版为 12.0f）。 */
    public static final float BAR_DIVISOR = (float) SERVINGS_PER_POT;

    /** 按杯数算条宽（与原版同一公式，只换分母），并夹到 {@code [0, 13]}。 */
    public static int barWidth(int servings) {
        if (servings <= 0) {
            return 0;
        }
        return clampBarWidth(Math.round(BAR_FULL_WIDTH * (float) servings / BAR_DIVISOR));
    }

    /** 旧存档条宽兜底：夹到 {@code [0, 13]}（超过 4 杯的旧数据不画出格）。 */
    public static int clampBarWidth(int width) {
        if (width < 0) {
            return 0;
        }
        return Math.min(width, BAR_FULL_WIDTH);
    }

    /** 产出数量：替换 5 处硬编码 {@code 12 -> 4} 的唯一取值口。 */
    public static int outputCount() {
        return SERVINGS_PER_POT;
    }

    private TeapotServingRules() {
    }
}
