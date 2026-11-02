package dimblend.experience.compat.create.observer;

/**
 * 观察者烈焰人红石输出的关断防抖（纯状态机，零 Minecraft/Level 依赖，可单元测试）。
 *
 * <p>用户口径（2026-11-02）：只在<b>关断</b>这一沿加 0.5 秒延迟，<b>开启</b>保持当拍。原因是
 * 标记岩浆集合每个重算窗口（{@code MarkedLavaTracker.RECOMPUTE_TICKS = 2}）整表重算，
 * 锥体边缘/遮挡采样踩边时相邻窗口的答案会不同，输出最高以 5 Hz 闪烁；10 tick 宽限正好盖住
 * 单个窗口的假 false。</p>
 *
 * <p>规则：</p>
 * <ol>
 * <li><b>开：当拍</b>——扫到标记岩浆的那一拍就返回 {@code true}，不排队、不延迟；</li>
 * <li><b>关：连续 {@value #OFF_DELAY_TICKS} 拍</b>扫不到才返回 {@code false}（0.5 s @ 20 tps）；</li>
 * <li><b>中途复位</b>——宽限期内任何一拍重新扫到，计数清零，下次关断重新从头数满；</li>
 * <li><b>不叠加</b>——持续扫到只保持高电平，不会攒倒计时，也就不会缩短以后的宽限。</li>
 * </ol>
 *
 * <p>计数器只在“当前已通电”时累加：没亮过的方块不预存倒计时，否则重亮后的第一次关断会提前。</p>
 *
 * <p><b>一拍 = 一次调用</b>：调用方 {@code QUERY_TICKS = 1} 时 1 拍 = 1 tick。若以后把查询周期调大，
 * 实际宽限会被放大成 {@code OFF_DELAY_TICKS × QUERY_TICKS}——要改的是这个换算，
 * 不能只改常量却不看调用节拍。</p>
 */
public final class ObserverBlazeSignal {
    /** 关断宽限：连续 10 拍（0.5 s @ 20 tps）扫不到标记岩浆才掉电。这是用户口径，不是性能调参。 */
    public static final int OFF_DELAY_TICKS = 10;

    /** 已连续扫不到标记岩浆的拍数；只在已通电时累加，掉电/重亮时清零。 */
    private int unmarkedTicks;

    /**
     * 推进一拍，返回本拍应写入的 {@code POWERED}。
     *
     * @param marked  本拍查询是否扫到了标记岩浆
     * @param powered 本拍查询之前方块的 {@code POWERED}
     * @return 本拍应写入的 {@code POWERED}；返回值与 {@code powered} 相同则调用方不必写方块
     */
    public boolean step(boolean marked, boolean powered) {
        if (marked) {
            // 开：当拍生效，宽限期内攒的倒计时一律作废。
            unmarkedTicks = 0;
            return true;
        }
        if (!powered) {
            // 本来就没亮：不预存倒计时，避免攒下的计数让下一次关断提前。
            unmarkedTicks = 0;
            return false;
        }
        // 关：宽限期内继续维持高电平（信号保持 15），数满这一拍才允许掉电。
        if (++unmarkedTicks >= OFF_DELAY_TICKS) {
            unmarkedTicks = 0;
            return false;
        }
        return true;
    }

    /** 当前已连续未扫到的拍数（测试/诊断用；正常流程由 {@link #step} 维护）。 */
    public int unmarkedTicks() {
        return unmarkedTicks;
    }
}
