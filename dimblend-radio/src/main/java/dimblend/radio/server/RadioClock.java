package dimblend.radio.server;

/**
 * 电台服务钟：每个服务端 tick 把真实流逝的毫秒累加进来（nanoTime 差值，单调）。
 *
 * <p>为什么不直接用 gameTime：掉刻（Can't keep up 跳 tick）时 gameTime 被等比拉长，
 * 而客户端音频按真实时间播放，切歌时钟会被拖慢数倍（实测 11 TPS 下 172s 的曲静默 2 分多钟）。
 * 本钟按 tick 间真实间隔补足，掉刻下仍与音频同速。</p>
 *
 * <p>暂停/挂起语义：单人暂停期间 IntegratedServer 走 tickPaused，服务端 tick 事件不触发，
 * 恢复后第一个 tick 的间隔会包含整段暂停时长——单 tick 间隔超过 {@link #MAX_TICK_GAP_MILLIS}
 * 时按封顶值累加，暂停/进程挂起因此只多走 1 秒（客户端在那些场景同样冻结播放，跳钟是错的）。
 * 掉刻补偿不受影响：要补偿的是高负载 TPS 下降（tick 间隔几十~几百毫秒），
 * 单 tick 超 1s 的极端卡顿最多损失超出封顶的部分。专用服务器无暂停。</p>
 */
public final class RadioClock {
    /** 单 tick 间隔累加上限（毫秒）：超过视为暂停/挂起，只按封顶值走。 */
    static final long MAX_TICK_GAP_MILLIS = 1000L;

    private static long radioMillis;
    private static long lastTickNanos = -1L;

    /** 每个服务端 tick 调用（RadioSync.onServerTick 入口，先于一切早退）。 */
    public static void onServerTick() {
        tick(System.nanoTime());
    }

    /** 可注入时刻的 tick（测试用）：累加与上一 tick 的间隔，封顶 {@link #MAX_TICK_GAP_MILLIS}。 */
    static void tick(long nowNanos) {
        if (lastTickNanos >= 0L) {
            long gapMillis = Math.max(0L, (nowNanos - lastTickNanos) / 1_000_000L);
            radioMillis += Math.min(gapMillis, MAX_TICK_GAP_MILLIS);
        }
        lastTickNanos = nowNanos;
    }

    /** 当前服务钟读数，毫秒。 */
    public static long now() {
        return radioMillis;
    }

    private RadioClock() {
    }
}
