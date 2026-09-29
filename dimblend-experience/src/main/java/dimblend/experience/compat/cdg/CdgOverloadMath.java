package dimblend.experience.compat.cdg;

/**
 * 柴油机过载确认与重建稳定判据（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 调用方见 {@code CdgOverloadFuse}（引信）与两处 mixin（普通/组合式、巨型机）。
 *
 * <p>加载误报机理（Create 6.0.10 字节码核实）：存档/区块加载期 kinetic 网络逐 tick
 * 重建，{@code KineticNetwork} 以 unloadedMembers/unloadedCapacity/unloadedStress 记账
 * 未加载成员；networkDirty 成员次 tick 触发 {@code updateNetwork → 值变才 sync()}，
 * 对全网成员调 {@code KineticBlockEntity.updateFromNetwork(capacity, stress, size)}
 * 刷新视图并写 {@code overStressed}（capacity<stress 时置真；此外 {@code read()}/
 * {@code clearKineticInformation()} 也写该字段）。成员经 {@code addSilently} 静默并入时
 * 不 sync（members↑ 与 unloadedMembers↓ 对冲，getSize 恒定）——但该路径不产生误报
 * （不写 overStressed）。承重蕴含方向是"会产生误报的 churn ⇒ 必先有一次 sync 视图
 * 写入"，故其逆否成立：视图稳定 ⇒ 无误报源，稳定判据可用。</p>
 *
 * <p>"重建完成"判据 = 引擎所见 {@code (stress, networkSize)} 视图连续
 * {@link #NETWORK_SETTLE_TICKS} tick 不变。不跟踪 capacity 是有意的（其变化更频繁）；
 * 但注意 B1 爬梯/B3 波动并非只影响 capacity——应力计算含 live 转速乘子
 * （{@code KineticNetwork.getActualStressOf} = base stress × |getTheoreticalSpeed()|），
 * 本机转速步进在<b>带载</b>网络上也会 churn stress 视图：单机过载确认期间波动计时
 * 冻结、自导 churn 停止，影响轻微；<b>多动力源共网</b>（多柴油机/多巨型机共轴）时
 * 其他源的爬梯/波动持续 churn，本机武装占空下降、40 tick 确认的墙钟时长被拉长
 * （保守方向：更晚炸，非误炸/不炸）——已知并接受，spec B4 有记录。也不用纯
 * {@code unloadedMembers==0} 判据：网络延伸到长期不加载的远处区块时它永远 >0，
 * 会导致正常运转永不炸机（超出加载防护的行为改变）。</p>
 */
public final class CdgOverloadMath {

    /**
     * 持续过载确认窗口：2 秒 × 20 tps。调用方仅在"运转中过载"且网络视图已稳定
     * （{@link #isArmed}）时调 {@link #countOverloadTick} 累计，任一正常 tick 自行
     * 清零计数；确认前爬梯/波动计时冻结（不断也不复位），燃油照常扣除（门控仅闩锁后生效）。
     */
    public static final int OVERLOAD_CONFIRM_TICKS = 40;

    /**
     * 网络视图稳定窗口：1 秒 × 20 tps。{@code (stress, networkSize)} 连续该时长不变
     * 判重建完成、炸机逻辑武装。重建跨多久就守多久+1 秒——比固定宽限期精确，
     * 长重建（>5 秒）也不再漏防。代价：游玩中负载增减会触发 1 秒静默期（保守方向，
     * 随后仍走 {@link #OVERLOAD_CONFIRM_TICKS} 确认）。
     */
    public static final int NETWORK_SETTLE_TICKS = 20;

    /**
     * 两次网络视图采样是否相同（float 走 {@link Float#compare} 精确等值——
     * 视图值来自同一计算链的拷贝传递，不涉及误差累积）。
     */
    public static boolean sameView(float stressA, int sizeA, float stressB, int sizeB) {
        return Float.compare(stressA, stressB) == 0 && sizeA == sizeB;
    }

    /** 稳定计数达标即武装（炸机逻辑开始运作）。 */
    public static boolean isArmed(int settleTicks) {
        return settleTicks >= NETWORK_SETTLE_TICKS;
    }

    /**
     * 疑似过载累计 1 tick（调用方保证：运转中过载且已武装为真）。
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
