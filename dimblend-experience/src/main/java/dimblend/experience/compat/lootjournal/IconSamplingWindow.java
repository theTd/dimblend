package dimblend.experience.compat.lootjournal;

/**
 * 诊断采样的全局限频窗口（纯逻辑，可单测）。
 *
 * <p>只放行「距上次放行已满 {@code windowMs}」的采样。时钟回拨（NTP 校时、用户改表）时按
 * 「新窗口」处理并立即放行——否则 {@code now - last} 为负，会永远小于窗口长度，诊断会静默
 * 永久失效；诊断静默失效比多打一行日志糟得多。</p>
 *
 * <p>只给 HUD 渲染线程用（单线程访问），因此不加同步。</p>
 */
final class IconSamplingWindow {

    private final long windowMs;
    private boolean hasLast;
    private long lastAcquiredMs;

    IconSamplingWindow(long windowMs) {
        this.windowMs = Math.max(0L, windowMs);
    }

    /**
     * 尝试占用一次放行名额。
     *
     * @param nowMs 当前毫秒时间戳
     * @return 本次是否应输出日志
     */
    boolean tryAcquire(long nowMs) {
        if (this.hasLast && nowMs >= this.lastAcquiredMs && nowMs - this.lastAcquiredMs < this.windowMs) {
            return false;
        }
        this.hasLast = true;
        this.lastAcquiredMs = nowMs;
        return true;
    }
}
