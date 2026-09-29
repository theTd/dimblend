package dimblend.experience.compat.cdg;

/**
 * 柴油机过载确认（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@code CdgKineticOverload}（实时账本刷新）与两处 mixin。
 *
 * <p>加载误报（Create 6.0.10 字节码）：{@code overStressed} 只在
 * {@code KineticBlockEntity.updateFromNetwork} 里按当时的 {@code capacity < stress}
 * 写入。区块加载并入走 {@code KineticNetwork.addSilently}，改 unloaded 账本和成员表，
 * 但不 sync，也不改这个缓存位。缓存位停在 true 时，方块上的
 * {@code (stress, networkSize)} 可以一直不变——那只说明没有新的 sync，不是网络已经恢复。
 * 确认前必须用 {@link #liveOverstressed} 对比 {@code calculateCapacity()}/
 * {@code calculateStress()} 的实时值；实时不成立不得累计，调用方还要
 * {@code updateNetwork()} 把粘住的缓存位刷掉。</p>
 *
 * <p>实时过载连续满 {@link #OVERLOAD_CONFIRM_TICKS} 才点引信。任一非过载 tick
 * 调用方把计数清零。确认进度不序列化。</p>
 */
public final class CdgOverloadMath {

    /**
     * 持续过载确认窗口：2 秒 × 20 tps。调用方仅在实时过载为真时调
     * {@link #countOverloadTick} 累计，任一非过载 tick 自行清零；确认前爬梯/波动计时冻结
     * （不断也不复位），燃油照常扣除（门控仅闩锁后生效）。
     */
    public static final int OVERLOAD_CONFIRM_TICKS = 40;

    /**
     * 与 {@code updateFromNetwork} 同一比较：{@code capacity < stress}。
     * NaN 在 Java {@code <} 下为 false，不判过载（字节码 {@code fcmpg} 对 NaN 走
     * {@code ifge}，同样不判）。
     */
    public static boolean liveOverstressed(float capacity, float stress) {
        return capacity < stress;
    }

    /**
     * 缓存过载位是否还能拿来累计。缓存位为假：Create 还没写上，或刚被刷掉，不算。
     * 缓存位为真但实时容量已经盖住应力：{@code addSilently} 之后粘住的误报，不算。
     */
    public static boolean countsAsOverload(boolean cachedOverstressed, float liveCapacity, float liveStress) {
        return cachedOverstressed && liveOverstressed(liveCapacity, liveStress);
    }

    /**
     * 疑似过载累计 1 tick（调用方保证：实时过载为真）。
     *
     * @return 累计后的计数值（封顶 {@link #OVERLOAD_CONFIRM_TICKS} 不再涨）
     */
    public static int countOverloadTick(int overloadTicks) {
        return overloadTicks < OVERLOAD_CONFIRM_TICKS ? overloadTicks + 1 : overloadTicks;
    }

    /**
     * 是否已满确认窗口。
     *
     * @return 计数值达 {@link #OVERLOAD_CONFIRM_TICKS} 时 true（确认后调用方点引信并 return）
     */
    public static boolean isOverloadConfirmed(int overloadTicks) {
        return overloadTicks >= OVERLOAD_CONFIRM_TICKS;
    }

    private CdgOverloadMath() {
    }
}
