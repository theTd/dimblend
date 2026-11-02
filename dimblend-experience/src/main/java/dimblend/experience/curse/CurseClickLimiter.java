package dimblend.experience.curse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 消失诅咒的点击限流：同一玩家每个服务端 tick 最多结算 {@link #MAX_ROLLS_PER_TICK} 次点击掷骰，
 * 超出的点击静默丢弃。
 *
 * <p>为什么需要：{@link CurseClickPayload} 是一条不带数据的 C2S 信号，"点了"本身不能证明是真人
 * 点击；改过的客户端可以每 tick 发任意多次，把 0.05% 的单次概率放大成"必定销毁装备"。按玩家、
 * 按 tick 封顶后，即使客户端狂刷也只有 2 × 20 = 40 次/秒的掷骰预算，而人类连续点击的极限约
 * 10 次/秒，正常玩法完全看不到这条上限。</p>
 *
 * <p>状态只有一张 {@code UUID → 当前 tick 的已用次数} 的小表：{@link #tryAcquire} 发现窗口不属于
 * 当前 tick 就地重置，所以一个玩家无论点多少次、跨多少个 tick，都只占一条；玩家登出时由
 * {@link #clear(UUID)} 立刻删掉，因此表的条数上限就是"点过鼠标且仍在线的玩家数"，不会随点击
 * 次数或运行时间增长。纯逻辑，不接触实体、网络与时间源。</p>
 */
public final class CurseClickLimiter {

    /** 同一玩家每个 tick 允许的掷骰次数：2 × 20 tick = 40 次/秒。 */
    public static final int MAX_ROLLS_PER_TICK = 2;

    private final Map<UUID, Window> windows = new HashMap<>();

    /**
     * 该玩家本 tick 是否还有掷骰额度；有就记一次并返回 {@code true}，没有返回 {@code false}。
     *
     * @param player   玩家 UUID（限流状态按它分开）
     * @param gameTime 当前服务端 tick 计数（{@code Level#getGameTime()}）
     */
    public boolean tryAcquire(UUID player, long gameTime) {
        Window window = this.windows.get(player);
        if (window == null || window.gameTime != gameTime) {
            // 第一次点击，或已经进入新 tick：开一个新窗口，这一次本身算第一次。
            this.windows.put(player, new Window(gameTime, 1));
            return true;
        }
        if (window.count >= MAX_ROLLS_PER_TICK) {
            return false;
        }
        window.count++;
        return true;
    }

    /** 玩家登出：丢掉它的窗口，断线玩家的状态不留在进程里。 */
    public void clear(UUID player) {
        this.windows.remove(player);
    }

    /** 当前记着多少玩家的窗口。只给单测与排查用；正常玩法下等于"点过鼠标的在线玩家数"。 */
    public int trackedPlayers() {
        return this.windows.size();
    }

    /** 一个玩家当前的计数窗口：属于哪个 tick、已用几次。 */
    private static final class Window {
        private final long gameTime;
        private int count;

        private Window(long gameTime, int count) {
            this.gameTime = gameTime;
            this.count = count;
        }
    }
}
