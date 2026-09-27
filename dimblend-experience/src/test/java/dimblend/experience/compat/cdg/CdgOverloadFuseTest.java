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
}
