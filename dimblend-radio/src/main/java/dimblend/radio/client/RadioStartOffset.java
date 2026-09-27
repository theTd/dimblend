package dimblend.radio.client;

import dimblend.radio.RadioState;

/**
 * 起播 offset 策略（纯计算）：曲首不被冷解码吞掉，中途加入仍与服务钟同进度。
 *
 * <p>服务端定曲定钟，客户端要先把整曲解成 PCM 才能起播（冷解码 1~3 秒，缓存命中≈0）。
 * 硬对齐服务钟会把解码耗时从曲首跳过——进服后第一次开播总吞掉开头一小段。</p>
 *
 * <ul>
 *   <li>本端在场听到开播（开始准备时服务钟进度 &lt; {@link #MAX_START_LAG_SEC}）：从头播，
 *       允许落后服务钟至多 {@link #MAX_START_LAG_SEC} 秒，超出部分才跳过。落后量小于曲间间隔
 *       （{@link RadioState#GAP_TICKS}），本端播完时服务端还没切下一曲，曲尾不被截。</li>
 *   <li>中途走近/迟加入：对齐服务钟，与他人同进度。</li>
 *   <li>同一曲在本端重建（静音恢复、走出走回、音频设备切换）：沿用首次落后量接着播，不重头。</li>
 * </ul>
 */
public final class RadioStartOffset {
    /** 允许落后服务钟的上限：曲间间隔 5 秒留 1 秒余量。 */
    public static final double MAX_START_LAG_SEC = RadioState.GAP_TICKS / 20.0 - 1.0;

    /**
     * @param prepSec  开始准备（提交解码任务）时服务钟的曲内进度，秒
     * @param playSec  起播时服务钟的曲内进度，秒
     * @param knownLag 同一曲此前在本端的落后量（秒）；本端首次起播传 null
     * @return 起播 offset（秒，≥0）
     */
    public static double offsetSec(double prepSec, double playSec, Double knownLag) {
        if (knownLag != null) {
            return Math.max(0.0, playSec - knownLag);
        }
        if (prepSec < MAX_START_LAG_SEC) {
            return Math.max(0.0, playSec - MAX_START_LAG_SEC);
        }
        return Math.max(0.0, playSec);
    }

    private RadioStartOffset() {
    }
}
