package dimblend.experience.compat.cdg;

/**
 * 柴油机过载持续确认窗口（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@code CdgOverloadFuse}（引信）与两处 mixin（普通/组合式、巨型机）。
 *
 * <p>存档/区块加载期 kinetic 网络逐 tick 重建（Create 从 NBT 恢复 stale 应力，
 * 见 {@code KineticBlockEntity.read/updateFromNetwork}），单 tick 的过载读数不可信；
 * 运转中过载连续满 {@link #OVERLOAD_CONFIRM_TICKS} tick（2 秒）才确认——短暂误报
 * 在此窗口内被滤掉，与蒸汽机 H 板块 16 秒持续确认同设计语言。</p>
 *
 * <p>重建误报的读数可能连续超过确认窗口（成员随区块逐个并入，重建跨越多 tick），
 * 且确认进度本身不跨存档携带后，窗口仍可能被长重建读数单独击穿——另设
 * {@link #LOAD_GRACE_TICKS} 加载宽限期：BE（重）实例化后该时长内的过载读数
 * 直接不参与确认（2026-09-28 口径）。</p>
 */
public final class CdgOverloadMath {

    /**
     * 持续过载确认窗口：2 秒 × 20 tps。调用方仅在"运转中过载"为真时调
     * {@link #countOverloadTick} 累计，任一正常 tick 自行清零计数；确认前爬梯/波动计时冻结
     * （不断也不复位），燃油照常扣除（门控仅闩锁后生效）。
     */
    public static final int OVERLOAD_CONFIRM_TICKS = 40;

    /**
     * 加载宽限期：BE（重）实例化后该 tick 数（5 秒）内的过载读数不参与确认。
     * 动机：存档/区块加载期 kinetic 网络重建期间 overstressed 读数不可信，且
     * 可能连续超过 {@link #OVERLOAD_CONFIRM_TICKS}；宽限期内不累计，重建结束
     * （出现正常读数 tick）后计数从 0 重新开始。宽限期取 5 秒——覆盖典型
     * 分块读档的重建时长，代价是加载后 5 秒内的真过载不惩罚（引擎刚加载，
     * 可接受）。判据入参为 {@link CdgEngineState#ticksSinceLoad}（读档归零）。
     */
    public static final int LOAD_GRACE_TICKS = 100;

    /**
     * 是否仍在加载宽限期内（true = 过载读数不参与确认）。
     *
     * @param ticksSinceLoad BE 实例化起累计的服务端 tick
     */
    public static boolean isWithinLoadGrace(int ticksSinceLoad) {
        return ticksSinceLoad < LOAD_GRACE_TICKS;
    }

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
