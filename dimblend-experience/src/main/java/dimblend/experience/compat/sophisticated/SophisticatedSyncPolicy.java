package dimblend.experience.compat.sophisticated;

/**
 * 精妙背包同步修复的时间/哨兵参数。
 *
 * <p>放在普通包里而不是 mixin 类里：mixin 类内的静态字段在合并/重定位上没有普通类干净，
 * 且仓库纪律要求 mixin 成员一律 private 实例成员。</p>
 */
public final class SophisticatedSyncPolicy {

    /**
     * 强制重发窗口。要盖住一次客户端槽位表重建：列数升级时客户端要等
     * {@code SyncSlotChangeErrorPayload} 一个包往返后才重建，重建前按新槽号发来的包会被
     * {@code setItem} 丢弃；40 tick（2 秒）覆盖正常延迟与一次 tick 抖动。
     */
    public static final int FORCE_RESYNC_TICKS = 40;

    /** 初始全量同步宽限：超过 20 tick 还没收到 {@code initializeContents} 就按正常路径渲染。 */
    public static final long INITIAL_SYNC_GRACE_TICKS = 20L;

    /** 哨兵数量：真实槽位不可能出现负数量，{@code ItemStack.matches} 必不相等。 */
    public static final int RESYNC_SENTINEL_COUNT = -1;

    private SophisticatedSyncPolicy() {
    }
}
