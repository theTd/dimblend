package dimblend.band;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dimblend.api.BandTrainMarker;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * {@link BandTrainSync#resolvePayload}: what a scan tick pushes, and — the reason this is a
 * separate function — what it pushes when the dragon-chase marker override disappears.
 *
 * <p>需求 §3.3 收尾：龙巢消失时 override 变回 null。若玩家当时 320 格内没有任何合格 sable，
 * 旧逻辑直接 {@code continue}，客户端缓存的 {@code alert = true} 永远留在屏幕上（红线指向一个
 * 已经不存在的龙巢）。这里把这四种状态转换钉死：override 生效、清空后找到了车、清空后没车但上
 * 一次是警戒（补发一条白线）、清空后没车且上一次本来就不是警戒（不发）。
 */
class BandTrainSyncAlertResetTest {
    @Test
    void activeOverrideAlwaysWins() {
        BandTrainMarker.Marker marker = new BandTrainMarker.Marker(12345.5, true);
        BandTrainPayload payload = BandTrainSync.resolvePayload(
                marker, OptionalDouble.empty(), new BandTrainPayload(9000.0, false));
        assertEquals(new BandTrainPayload(12345.5, true), payload,
                "override 生效时整维度广播它的 x 与警戒位，与候选/上一次无关");
    }

    @Test
    void overrideClearedWithNearbyTrainReturnsTheLocatedPosition() {
        BandTrainPayload payload = BandTrainSync.resolvePayload(
                null, OptionalDouble.of(4000.0), new BandTrainPayload(12345.5, true));
        assertEquals(new BandTrainPayload(4000.0, false), payload,
                "override 清空后按常规逻辑回到最近的列车，且不再警戒");
    }

    @Test
    void overrideClearedFindingNoTrainStillClearsTheAlert() {
        BandTrainPayload previous = new BandTrainPayload(12345.5, true);
        BandTrainPayload payload = BandTrainSync.resolvePayload(null, OptionalDouble.empty(), previous);
        assertEquals(new BandTrainPayload(12345.5, false), payload,
                "没有车可指也必须补发一条非警戒，否则客户端的红线永远不消失");
        assertFalse(payload.alert());
        assertEquals(previous.centerX(), payload.centerX(), 0.0,
                "清警戒时沿用上一次的 x，白线留在原地而不是跳到别处");
    }

    @Test
    void noTrainAndNoAlertKeepsTheLastPositionSilently() {
        assertNull(BandTrainSync.resolvePayload(
                        null, OptionalDouble.empty(), new BandTrainPayload(4000.0, false)),
                "客户端继续用上一次的位置（需求 #6），不重复下发");
    }

    @Test
    void noHistoryAndNoTrainSendsNothing() {
        assertNull(BandTrainSync.resolvePayload(null, OptionalDouble.empty(), null));
        assertNull(BandTrainSync.resolvePayload(null, null, null), "null located means 'nothing found'");
    }

    @Test
    void clearingIsIdempotentOnTheFollowingTick() {
        BandTrainPayload cleared = BandTrainSync.resolvePayload(
                null, OptionalDouble.empty(), new BandTrainPayload(12345.5, true));
        assertNotNull(cleared);
        assertFalse(cleared.alert());
        // 下一个 tick 已经不再警戒：不再重复下发，客户端保持最后一次（白色）结果。
        assertNull(BandTrainSync.resolvePayload(null, OptionalDouble.empty(), cleared));
    }
}