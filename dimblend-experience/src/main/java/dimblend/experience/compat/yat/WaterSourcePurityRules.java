package dimblend.experience.compat.yat;

/**
 * 全模组范围内"世界水源纯度"的口径集中处（Audit Default #7 + report-w3 §11.5）。
 *
 * <p>YAT 的 {@code WaterPurity.getBlockPurity} 只会给原版水源/炼药锅算纯度，任何第三方
 * 模组（Create 开口管、集水器、软管滑轮、消防取水器；Fluid 铜龙头/铜水槽；
 * Sophisticated Core 泵升级；Cold Sweat 水袋）自己搬运水时都会丢掉 {@code Purity} 组件，
 * 于是"抽上来的河水"在被 YAT 读取时按 0（脏水）处理、却又在 UI 里显示为可饮用水。
 * 本类统一三条口径：</p>
 *
 * <ol>
 *   <li><b>世界水源</b>：一律以 {@code WaterPurity.getBlockPurity} 为准（雨/滴水为 1，
 *       流动水按 YAT 规则抬升，其余为 0）；</li>
 *   <li><b>无限水源</b>（消防取水器、水浸方块铜龙头、铜水槽无限模式）：
 *       直接按 <b>0（肮脏）</b> 处理；</li>
 *   <li><b>混合</b>：容器里已有水与新进水纯度不同时取 {@code min}（脏水只会把水变脏，
 *       不会把脏水"洗"干净；与 YAT 滤芯的语义一致）。</li>
 * </ol>
 *
 * <p>纯函数，零 Minecraft 依赖，便于单元测试（{@code WaterSourcePurityRulesTest}）。</p>
 */
public final class WaterSourcePurityRules {

    /** YAT 纯度取值范围（0 脏 / 1 微脏 / 2 可接受 / 3 纯净）。 */
    public static final int MIN_PURITY = 0;
    public static final int MAX_PURITY = 3;
    /** 雨/滴水等"天上来的水"纯度。 */
    public static final int RAIN_PURITY = 1;
    /** 无限水源（取之不尽的水）纯度。 */
    public static final int INFINITE_SOURCE_PURITY = 0;

    /**
     * 水流体栈没有 {@code Purity} 组件时的有效纯度：按 0（脏水）计
     * （与 YAT {@code FluidPurityHelper.hasPurity} 判据、本仓库
     * {@code DirtyWaterPurity.isDirtyWaterFluid} 同口径）。
     */
    public static int effectivePurity(boolean hasPurity, int purity) {
        return hasPurity ? clampPurity(purity) : INFINITE_SOURCE_PURITY;
    }

    /** 夹到 {@code [0, 3]}。 */
    public static int clampPurity(int purity) {
        if (purity < MIN_PURITY) {
            return MIN_PURITY;
        }
        return Math.min(purity, MAX_PURITY);
    }

    /** 两股水混合后的纯度：取 {@code min}（脏的赢）。 */
    public static int blendMin(int first, int second) {
        return Math.min(clampPurity(first), clampPurity(second));
    }

    /** 只降不升：把纯度压到不超过 {@code ceiling}（雨 1、无限源 0）。 */
    public static int capAt(int purity, int ceiling) {
        return Math.min(clampPurity(purity), clampPurity(ceiling));
    }

    /**
     * YAT 方块态 {@code BLOCK_PURITY} 的编码：{@code 0 = 未设置}，真实纯度写
     * {@code purity + 1}（见 {@code WaterPurity.getBlockPurity} 的解码）。
     */
    public static int encodeBlockPurity(int purity) {
        return clampPurity(purity) + 1;
    }

    /** 解码：{@code raw <= 0} 视为未设置，返回 {@code -1}（调用方决定用默认值）。 */
    public static int decodeBlockPurity(int raw) {
        return raw <= 0 ? -1 : clampPurity(raw - 1);
    }

    /**
     * 方块纯度合并：{@code existing} 为 {@code -1}（未设置）时直接取 {@code incoming}，
     * 否则取 {@code min}。
     */
    public static int mergeBlockPurity(int existing, int incoming) {
        return existing < 0 ? clampPurity(incoming) : blendMin(existing, incoming);
    }

    /** 是否是需要打纯度的水（非空且是水）。 */
    public static boolean isWater(boolean empty, boolean water) {
        return !empty && water;
    }

    private WaterSourcePurityRules() {
    }
}
