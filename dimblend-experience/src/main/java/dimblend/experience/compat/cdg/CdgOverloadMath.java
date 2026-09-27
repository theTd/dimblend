package dimblend.experience.compat.cdg;

/**
 * 柴油机过载持续确认窗口（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@code CdgOverloadFuse}（引信）与两处 mixin（普通/组合式、巨型机）。
 *
 * <p>存档/区块加载期 kinetic 网络逐 tick 重建（Create 从 NBT 恢复 stale 应力，
 * 见 {@code KineticBlockEntity.read/updateFromNetwork}），单 tick 的过载读数不可信；
 * 运转中过载连续满 {@link #OVERLOAD_CONFIRM_TICKS} tick（2 秒）才确认——短暂误报
 * 在此窗口内被滤掉，与蒸汽机 H 板块 16 秒持续确认同设计语言。</p>
 */
public final class CdgOverloadMath {

    /**
     * 持续过载确认窗口：2 秒 × 20 tps。调用方仅在"运转中过载"为真时调
     * {@link #countOverloadTick} 累计，任一正常 tick 自行清零计数；确认前爬梯/波动计时冻结
     * （不断也不复位），燃油照常扣除（门控仅闩锁后生效）。
     */
    public static final int OVERLOAD_CONFIRM_TICKS = 40;

    /**
     * 疑似过载累计 1 tick（调用方保证：运转中过载为真）。
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
