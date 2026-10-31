package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dimblend.experience.compat.create.HornPlaybackMath.Action;
import org.junit.jupiter.api.Test;

/**
 * 列车汽笛播放判定表（2026-10-31 用户改版：「沿开始、电平结束」，取代 2026-10-26 规则 B）。
 *
 * <p>用户原话：「只有连续不断的红石信号会播汽笛声直到结束，期间信号中断，声音也中断，
 * 播完声音一次后结束，等待断开信号」。四条不变量：</p>
 *
 * <ol>
 * <li>false→true 上升沿起播一次（抖动锁内除外）；</li>
 * <li>true→false 下降沿立即停；</li>
 * <li>素材自然播完回 IDLE，高电平保持不重播（没有沿就没有起播）；</li>
 * <li>播完 + 断电后再次上升沿重新起播；连续同电平（无沿）不动作。</li>
 * </ol>
 *
 * <p>纯函数，无任何 Minecraft 类型：判定表就是需求的可执行副本，服务端两条边沿都走它。</p>
 */
class HornPlaybackMathTest {

    private static final boolean POWERED = true;
    private static final boolean UNPOWERED = false;
    private static final boolean LOCK = true;
    private static final boolean FREE = false;
    private static final boolean PLAYING = false; // clipEnded = false → 素材还没播完
    private static final boolean ENDED = true;

    @Test
    void risingEdgeFromIdleStartsTheHorn() {
        assertEquals(Action.START, HornPlaybackMath.decide(UNPOWERED, POWERED, FREE, ENDED),
                "false->true must start the horn");
        assertEquals(Action.START, HornPlaybackMath.decide(UNPOWERED, POWERED, FREE, PLAYING),
                "false->true must start the horn even straight after a cut");
    }

    @Test
    void fallingEdgeStopsThePlayingHorn() {
        assertEquals(Action.STOP, HornPlaybackMath.decide(POWERED, UNPOWERED, FREE, PLAYING),
                "true->false must cut the sound immediately");
        // 抖动锁在下降沿不得拦下停止：锁只防重复起播，不防停止
        assertEquals(Action.STOP, HornPlaybackMath.decide(POWERED, UNPOWERED, LOCK, PLAYING),
                "the debounce lock must never keep a horn playing after power loss");
    }

    @Test
    void fallingEdgeAfterTheClipEndedIsJustIdle() {
        assertEquals(Action.IDLE, HornPlaybackMath.decide(POWERED, UNPOWERED, FREE, ENDED),
                "a falling edge with nothing playing has nothing to stop");
    }

    @Test
    void risingEdgeInsideTheDebounceIsIgnored() {
        assertEquals(Action.IGNORE, HornPlaybackMath.decide(UNPOWERED, POWERED, LOCK, PLAYING),
                "a rising edge inside the one-tick debounce must not start a second horn");
        assertEquals(Action.IGNORE, HornPlaybackMath.decide(UNPOWERED, POWERED, LOCK, ENDED),
                "the debounce blocks a duplicate start even when the clip already ended");
    }

    @Test
    void heldHighNeverRestartsAfterTheClipEnded() {
        assertEquals(Action.IDLE, HornPlaybackMath.decide(POWERED, POWERED, FREE, ENDED),
                "holding the signal high must not replay the clip (no edge, no start)");
        assertEquals(Action.IGNORE, HornPlaybackMath.decide(POWERED, POWERED, FREE, PLAYING),
                "holding the signal high while playing must not restart it");
        assertEquals(Action.IDLE, HornPlaybackMath.decide(POWERED, POWERED, LOCK, ENDED),
                "held high inside a debounce is still a no-op");
    }

    @Test
    void heldLowDoesNothing() {
        assertEquals(Action.IDLE, HornPlaybackMath.decide(UNPOWERED, UNPOWERED, FREE, ENDED),
                "a steady low signal has nothing to do");
        assertEquals(Action.IDLE, HornPlaybackMath.decide(UNPOWERED, UNPOWERED, LOCK, PLAYING),
                "a steady low signal has nothing to do, lock or not");
    }

    /** 播完 + 断电 + 新的上升沿 → 重新起播（用户「等待断开信号」之后的那一次）。 */
    @Test
    void restartAfterClipEndedAndPowerCutStartsAgain() {
        Action afterClipEndedWhileHeld = HornPlaybackMath.decide(POWERED, POWERED, FREE, ENDED);
        Action afterCut = HornPlaybackMath.decide(POWERED, UNPOWERED, FREE, ENDED);
        Action newEdge = HornPlaybackMath.decide(UNPOWERED, POWERED, FREE, ENDED);
        assertEquals(Action.IDLE, afterClipEndedWhileHeld);
        assertEquals(Action.IDLE, afterCut);
        assertEquals(Action.START, newEdge,
                "after the clip ended and the signal was released, a new edge must play again");
    }

    /** 一 tick 的断电/复电：切一次、再起播一次（电平语义，不是「响一次就锁死」）。 */
    @Test
    void oneTickPowerGlitchCutsThenRestarts() {
        // 起播 tick：锁只活 1 tick，本 tick 内重复上升沿被挡
        assertEquals(Action.START, HornPlaybackMath.decide(UNPOWERED, POWERED, FREE, PLAYING));
        assertEquals(Action.STOP, HornPlaybackMath.decide(POWERED, UNPOWERED, FREE, PLAYING));
        assertEquals(Action.START, HornPlaybackMath.decide(UNPOWERED, POWERED, FREE, ENDED));
    }

    @Test
    void debounceWidensByOneTickOnly() {
        assertEquals(1, HornPlaybackMath.DEBOUNCE_TICKS,
                "the lock is a one-tick debounce, not the whole clip length any more");
    }
}