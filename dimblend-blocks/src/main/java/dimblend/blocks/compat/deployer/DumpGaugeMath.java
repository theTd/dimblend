package dimblend.blocks.compat.deployer;

/**
 * 卸料仪表的纯数值映射（锁定判定 #19）。
 *
 * <p>滑条原始值 {@code raw ∈ 0..100}，语义整体右移一格：</p>
 * <pre>
 *   raw = 0        -> 未启用（仪表不下料；对齐 Create "count == 0 =&gt; !isActive"）
 *   raw = 1        -> 目标 0（库存 &gt; 0 就一直卸，卸空为止）
 *   raw = 2..100   -> 目标 units = raw - 1（1..99）
 * </pre>
 *
 * <p>目标件数：{@code inStacks ? units * maxStackSize : units}。卸料量
 * {@code min(inStorage - target, maxStackSize * 9)}，于是库存会停在 {@code == target}。</p>
 *
 * <p>外部 {@code NUMBERS} 信号直接给出 <b>units</b>：{@code < 0} 视为未启用，
 * {@code >= 0} 直接当目标（所以 {@code 0} 就是"卸空"）。</p>
 */
public final class DumpGaugeMath {

    /** 滑条最小值（= 未启用）。 */
    public static final int RAW_MIN = 0;
    /** 滑条最大值。 */
    public static final int RAW_MAX = 100;
    /** 未启用对应的原始值。 */
    public static final int RAW_INACTIVE = 0;
    /** 外部信号里"未启用"的哨兵值。 */
    public static final int SIGNAL_INACTIVE = -1;
    /** units 上限（raw 100 -> 99）。 */
    public static final int MAX_UNITS = 99;
    /** 单次最多发一整潜影盒的量（9 组）。 */
    public static final int MAX_BATCH_STACKS = 9;

    /** 夹取到滑条值域。 */
    public static int clampRaw(int raw) {
        if (raw < RAW_MIN) {
            return RAW_MIN;
        }
        return Math.min(raw, RAW_MAX);
    }

    /** 是否未启用。 */
    public static boolean isInactive(int raw) {
        return clampRaw(raw) == RAW_INACTIVE;
    }

    /** 原始值 -&gt; 目标 units（{@code 0..99}）；未启用时返回 {@link #SIGNAL_INACTIVE}。 */
    public static int targetUnits(int raw) {
        int clamped = clampRaw(raw);
        if (clamped == RAW_INACTIVE) {
            return SIGNAL_INACTIVE;
        }
        return clamped - 1;
    }

    /** 原始值 -&gt; 目标件数；未启用时返回 {@code -1}（调用方必须先判 {@link #isInactive}）。 */
    public static long targetItems(int raw, boolean inStacks, int maxStackSize) {
        int units = targetUnits(raw);
        if (units < 0) {
            return -1L;
        }
        return itemsOf(units, inStacks, maxStackSize);
    }

    /** units -&gt; 件数（外部信号路径也走这里）。 */
    public static long itemsOf(int units, boolean inStacks, int maxStackSize) {
        int clampedUnits = Math.max(0, Math.min(units, MAX_UNITS));
        if (!inStacks) {
            return clampedUnits;
        }
        int perStack = maxStackSize <= 0 ? 1 : maxStackSize;
        return (long) clampedUnits * (long) perStack;
    }

    /**
     * 本次应发送的件数：库存严格大于目标才发，发到"刚好剩 target"为止；
     * 单次不超过 {@code maxStackSize * 9}（一个潜影盒的量）。
     */
    public static int dumpAmount(int raw, boolean inStacks, int maxStackSize, int inStorage) {
        int units = targetUnits(raw);
        if (units < 0) {
            return 0;
        }
        return dumpAmountForUnits(units, inStacks, maxStackSize, inStorage);
    }

    /** 按 units 计算本次发送件数（滑条路径与外部信号路径共用）。 */
    public static int dumpAmountForUnits(int units, boolean inStacks, int maxStackSize, int inStorage) {
        if (units < 0 || inStorage <= 0) {
            return 0;
        }
        long target = itemsOf(units, inStacks, maxStackSize);
        if (inStorage <= target) {
            return 0;
        }
        long batch = (long) Math.max(1, maxStackSize) * MAX_BATCH_STACKS;
        return (int) Math.min(inStorage - target, batch);
    }

    /** 外部 {@code NUMBERS} 信号 -&gt; units；负值即未启用。 */
    public static int unitsFromSignal(float signal) {
        if (!Float.isFinite(signal) || signal < 0.0F) {
            return SIGNAL_INACTIVE;
        }
        return Math.min((int) Math.floor(signal), MAX_UNITS);
    }

    /** 滑条/角标显示文案的"数字部分"：未启用返回 {@code null}，否则为 units（0..99）。 */
    public static Integer displayUnits(int raw) {
        int units = targetUnits(raw);
        return units < 0 ? null : units;
    }

    private DumpGaugeMath() {
    }
}
