package dimblend.experience.compat.cdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** B6 持续过载确认（见 {@link CdgOverloadMath}）：短暂误报不成引信（纯函数，零依赖）。 */
class CdgOverloadFuseTest {

    @Test
    void transientOverloadNeverConfirms() {
        // 加载期式短暂误报：5 tick 过载后恢复，全程不成引信
        int ticks = 0;
        for (int i = 0; i < 5; i++) {
            ticks = CdgOverloadMath.countOverloadTick(ticks);
            assertFalse(CdgOverloadMath.isOverloadConfirmed(ticks));
        }
        // 恢复当 tick 调用方清零：下次从头累计
        ticks = 0;
        ticks = CdgOverloadMath.countOverloadTick(ticks);
        assertEquals(1, ticks);
        assertFalse(CdgOverloadMath.isOverloadConfirmed(ticks));
    }

    @Test
    void confirmWindowIsTwoSeconds() {
        int ticks = 0;
        for (int i = 0; i < CdgOverloadMath.OVERLOAD_CONFIRM_TICKS - 1; i++) {
            ticks = CdgOverloadMath.countOverloadTick(ticks);
            assertFalse(CdgOverloadMath.isOverloadConfirmed(ticks));
        }
        ticks = CdgOverloadMath.countOverloadTick(ticks);
        assertTrue(CdgOverloadMath.isOverloadConfirmed(ticks));
    }

    @Test
    void counterCapsAndStaysConfirmed() {
        int ticks = 0;
        for (int i = 0; i < CdgOverloadMath.OVERLOAD_CONFIRM_TICKS; i++) {
            ticks = CdgOverloadMath.countOverloadTick(ticks);
        }
        for (int i = 0; i < 100; i++) {
            ticks = CdgOverloadMath.countOverloadTick(ticks);
            assertTrue(CdgOverloadMath.isOverloadConfirmed(ticks));
        }
        assertEquals(CdgOverloadMath.OVERLOAD_CONFIRM_TICKS, ticks);
    }

    @Test
    void loadGraceBlocksConfirmationRightAfterLoad() {
        // 读档即过载（网络重建残留）：宽限期内计数不动、不成引信
        for (int ticksSinceLoad = 0; ticksSinceLoad < CdgOverloadMath.LOAD_GRACE_TICKS; ticksSinceLoad++) {
            assertTrue(CdgOverloadMath.isWithinLoadGrace(ticksSinceLoad));
        }
        // 宽限期一过，确认窗口照常计时
        assertFalse(CdgOverloadMath.isWithinLoadGrace(CdgOverloadMath.LOAD_GRACE_TICKS));
    }

    @Test
    void loadGraceMustExceedConfirmWindow() {
        // 宽限期短于确认窗口则重建误报仍可能单独击穿窗口
        assertTrue(CdgOverloadMath.LOAD_GRACE_TICKS > CdgOverloadMath.OVERLOAD_CONFIRM_TICKS);
    }
}
