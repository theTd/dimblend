package dimblend.radio.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 起播 offset 策略（纯函数）：在场开播从头播且落后 ≤4s、中途走近对齐服务钟、同曲重建沿用落后量。
 */
class RadioStartOffsetTest {
    private static final double EPS = 1e-9;

    @Test
    void maxLagLeavesMarginInsideSongGap() {
        // 曲间 5s（GAP_TICKS=100），留 1s 余量
        assertEquals(4.0, RadioStartOffset.MAX_START_LAG_SEC, EPS);
    }

    @Test
    void coldDecodeAtSongStartPlaysFromBeginning() {
        // 开播瞬间收包、冷解码 2s：从头播，不吞曲首
        assertEquals(0.0, RadioStartOffset.offsetSec(0.05, 2.05, null), EPS);
    }

    @Test
    void decodeSlowerThanMaxLagSkipsOnlyTheExcess() {
        // 冷解码 6s：只跳过超出 4s 的部分，落后量封顶 4s，曲尾不会被服务端切歌截掉
        double offset = RadioStartOffset.offsetSec(0.05, 6.05, null);
        assertEquals(2.05, offset, EPS);
        assertEquals(4.0, 6.05 - offset, EPS);
    }

    @Test
    void walkingUpMidSongStaysOnServerClock() {
        // 曲中 30s 走近、解码 2s：与服务钟同进度（和其他玩家一致）
        assertEquals(32.0, RadioStartOffset.offsetSec(30.0, 32.0, null), EPS);
    }

    @Test
    void graceEndsAtMaxLag() {
        // 准备时进度恰为 4s 已不算“在场开播”
        assertEquals(5.0, RadioStartOffset.offsetSec(4.0, 5.0, null), EPS);
        assertEquals(0.0, RadioStartOffset.offsetSec(3.9, 4.0, null), EPS);
    }

    @Test
    void rebuildOfSameSongKeepsItsLag() {
        // 首次落后 2s；曲中 60s 重建（如走出走回）：接着 58s 播，不重头、不跳到服务钟
        assertEquals(58.0, RadioStartOffset.offsetSec(59.9, 60.0, 2.0), EPS);
        // 开播后 1s 内重建：仍按落后量续，offset 不为负
        assertEquals(0.0, RadioStartOffset.offsetSec(0.5, 1.0, 2.0), EPS);
    }

    @Test
    void finishedSongOffsetReachesTrackEnd() {
        // 本端落后 2s 的 200s 曲：服务钟 202s 时本端也播完，offset ≥ 时长，不再重建
        assertEquals(200.0, RadioStartOffset.offsetSec(202.0, 202.0, 2.0), EPS);
    }
}
