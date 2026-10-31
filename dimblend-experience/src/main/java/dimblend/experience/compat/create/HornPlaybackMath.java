package dimblend.experience.compat.create;

/**
 * 列车汽笛播放判定表（2026-10-31 用户改版：「沿开始、电平结束」，取代 2026-10-26 规则 B）。
 *
 * <p>用户原话：「只有连续不断的红石信号会播汽笛声直到结束，期间信号中断，声音也中断，
 * 播完声音一次后结束，等待断开信号」。目标状态机：</p>
 *
 * <pre>
 * IDLE    ──上升沿──▶ PLAYING（广播开始包，写玩法窗口）
 * PLAYING ──下降沿──▶ IDLE   （立即广播停止包 + 清锁；这是本次最大变化）
 * PLAYING ──素材播完─▶ IDLE  （不广播；高电平保持时没有沿，故不会重播）
 * </pre>
 *
 * <p>四条不变量，本类就是它的可执行副本：</p>
 * <ol>
 * <li>只有 false→true 起播；同一 tick / 相邻 tick 的重复上升沿被去抖锁挡住；</li>
 * <li>true→false 立即停——去抖锁<b>永不</b>拦下停止（锁只防重复起播）；</li>
 * <li>素材自然播完回 IDLE，持续高电平不重播（没有沿就没有新起播）；</li>
 * <li>播完 + 断电后再来一个上升沿 → 重新起播。</li>
 * </ol>
 *
 * <p>纯函数、零 Minecraft 类型：服务端两条边沿与客户端停止包都走同一张表，判定只有一份。</p>
 */
public final class HornPlaybackMath {

    /**
     * 去抖锁多活 1 tick：起播 tick 与紧随其后的那一 tick 内的重复上升沿都被无视
     * （覆盖「同一 tick 两次 setBlock」与「相邻 tick 二次触发」两种重复）。它<b>不</b>覆盖整段
     * 素材时长——2026-10-26 规则 B 的整段锁定已被 2026-10-31 用户改版推翻。
     */
    public static final int DEBOUNCE_TICKS = 1;

    /** 一条边沿该做的事；{@code IDLE} = 无事可做（保持现状）。 */
    public enum Action {
        /** 起播：广播开始包、写玩法窗口。 */
        START,
        /** 立即停：广播停止包、清锁、客户端淡出。 */
        STOP,
        /** 有上升沿但被去抖锁挡下——既不起播也不停。 */
        IGNORE,
        /** 无动作（持平电平、或无声音可停的下降沿）。 */
        IDLE
    }

    /**
     * 判定一条边沿。
     *
     * @param wasPowered 边沿前该方块的红石 {@code POWERED}
     * @param powered    边沿后该方块的红石 {@code POWERED}
     * @param lockActive 去抖锁是否仍在本 tick / 相邻 tick 内
     * @param clipEnded  当前素材是否已经播完（没有在响的片段）
     */
    public static Action decide(boolean wasPowered, boolean powered, boolean lockActive, boolean clipEnded) {
        if (wasPowered == powered) {
            // 持平电平：没有沿就没有起播。正在播 → 什么都不做；已播完 → 回 IDLE 等断开
            return powered && !clipEnded ? Action.IGNORE : Action.IDLE;
        }
        if (powered) {
            // 上升沿：锁内不重复起播
            return lockActive ? Action.IGNORE : Action.START;
        }
        // 下降沿：电平结束语义——有声音就立刻停，没有声音就无事可做。
        // 锁刻意不参与这一支：断电必须停，去抖锁只防重复起播。
        return clipEnded ? Action.IDLE : Action.STOP;
    }

    /**
     * 客户端收到 {@link TrainHornPayload} 时的同表判定。
     *
     * <p>开始包<b>本身就是那条上升沿</b>（服务端已经判定过 START），因此一律按
     * {@code false→true} 处理，不因本地窗口是否仍在而改判——否则一次正常重播会被误判成
     * 重复起播而静音。停止包按电平结束处理：窗口里还有声音才淡出，没有就什么都不做。</p>
     *
     * @param start     包里的 {@code start} 位
     * @param clipEnded 客户端当前是否已无鸣笛窗口（= 素材不在响）
     */
    public static Action decideForPacket(boolean start, boolean clipEnded) {
        return start
                ? decide(false, true, false, clipEnded)
                : decide(true, false, false, clipEnded);
    }

    private HornPlaybackMath() {
    }
}