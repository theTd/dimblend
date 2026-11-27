package dimblend.blocks.compat.deployer;

import java.util.Locale;

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
 * <p>目标件数：{@code inStacks ? units * maxStackSize : units}。单轮卸料量
 * {@code min(库存 - 目标, 一个包裹的容量)}：Create 的打包机一次最多抽 64 个
 * （{@code PackagerBlockEntity#attemptToSend} 里的 {@code Math.min(64, count)}），
 * 装不进容器的大件一件一包，所以库存是分多轮压到目标的。</p>
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
    /** 一个包裹的格数上限（Create {@code PackageItem.SLOTS}）。 */
    public static final int MAX_BATCH_STACKS = 9;
    /** 单包件数上限：直通散装物一轮只发一个包裹，最多 64 个。 */
    public static final int MAX_ITEMS_PER_PACKAGE = 64;
    /** 两次检查网络库存的间隔（tick）：80 tick = 4 秒。 */
    public static final int CHECK_INTERVAL_TICKS = 80;
    /** 一秒的 tick 数，只用于把检查周期显示成秒。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 墙面灯珠的三态，与 Deployer 的 {@code AbstractPanelBehaviour.BulbState} 一一对应。 */
    public enum Bulb {
        /** 目标为 0：面板模型此时是无棱柱的 {@code panel}，画灯珠就是浮空玻璃珠。 */
        DISABLED,
        /** 正常轮询中。 */
        GREEN,
        /** 被红石按住，暂停卸料。 */
        RED
    }

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
     * 一个包裹能装多少件。
     *
     * <p>装不进容器的大件（潜影盒之类，{@code ItemStack#canFitInsideContainerItems} 为假）在 Create 的打包机里
     * 抽一件就 {@code break}，所以恒为 1；其余按 {@code 组数 × 单组上限} 夹到 64。</p>
     */
    public static int itemsPerPackage(int maxStackSize, boolean canFitInsideContainerItems) {
        if (!canFitInsideContainerItems) {
            return 1;
        }
        return Math.min(MAX_ITEMS_PER_PACKAGE, Math.max(1, maxStackSize) * MAX_BATCH_STACKS);
    }

    /**
     * 本次应发送的件数：库存严格大于目标才发，发到"刚好剩 target"为止；
     * 单次不超过 {@code itemsPerPackage(maxStackSize, true)}（默认按直通散装物算一包）。
     */
    public static int dumpAmountForUnits(int units, boolean inStacks, int maxStackSize, int inStorage) {
        return dumpAmountForUnits(units, inStacks, maxStackSize, inStorage, itemsPerPackage(maxStackSize, true));
    }

    /** 按 units 计算本次发送件数（滑条路径、外部信号路径与打包机的大件规则共用）。 */
    public static int dumpAmountForUnits(int units, boolean inStacks, int maxStackSize, int inStorage,
            int maxPerPackage) {
        if (units < 0 || inStorage <= 0) {
            return 0;
        }
        long target = itemsOf(units, inStacks, maxStackSize);
        if (inStorage <= target) {
            return 0;
        }
        long cap = Math.max(1, maxPerPackage);
        return (int) Math.min(inStorage - target, cap);
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

    /**
     * 错峰后的检查相位：{@code gameTime + 位置哈希} 每 {@code interval} tick 命中一次。
     *
     * <p>用位置哈希错开各面板，避免所有卸料仪表在同一 tick 一起查网络汇总。</p>
     */
    public static boolean isCheckTick(long gameTime, int positionHash, int interval) {
        if (interval <= 0) {
            return true;
        }
        return Math.floorMod(gameTime + positionHash, (long) interval) == 0;
    }

    /** tick 数 -&gt; 界面上的秒数文案（{@code 80 -> "4"}，{@code 90 -> "4.5"}）。 */
    public static String secondsText(int ticks) {
        if (ticks % TICKS_PER_SECOND == 0) {
            return Integer.toString(ticks / TICKS_PER_SECOND);
        }
        return String.format(Locale.ROOT, "%.1f", ticks / (float) TICKS_PER_SECOND);
    }

    /** 灯珠状态：目标 0 不画；红石供电红；其余绿。 */
    public static Bulb bulbState(int count, boolean redstonePowered) {
        if (count == 0) {
            return Bulb.DISABLED;
        }
        return redstonePowered ? Bulb.RED : Bulb.GREEN;
    }

    /**
     * 一次检查的准入：有目标、没被红石按住、有投递地址，三者缺一不发包。
     *
     * <p>抽成纯函数是为了让"红石暂停"这条规则能被单测钉住——{@code DumpPanelBehaviour#dump()}
     * 本体要整个物流栈才跑得起来，测不了。</p>
     */
    public static boolean mayDispatch(boolean hasTarget, boolean redstonePowered, String address) {
        return hasTarget && !redstonePowered && address != null && !address.isBlank();
    }

    private DumpGaugeMath() {
    }
}
