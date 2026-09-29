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
    void unsettledNetworkViewNeverArms() {
        // 重建 churn：每次视图变化清零，持续 churn 永不武装
        int settle = 0;
        float lastStress = 0.0F;
        int lastSize = 0;
        for (int i = 1; i <= 100; i++) {
            float stress = i * 8.0F;
            if (CdgOverloadMath.sameView(stress, 1, lastStress, lastSize)) {
                settle++;
            } else {
                settle = 0;
                lastStress = stress;
                lastSize = 1;
            }
            assertFalse(CdgOverloadMath.isArmed(settle));
        }
    }

    @Test
    void stableNetworkViewArmsAfterSettleWindow() {
        // 视图连续不变：NETWORK_SETTLE_TICKS-1 内不武装，满窗口武装
        int settle = 0;
        for (int i = 0; i < CdgOverloadMath.NETWORK_SETTLE_TICKS - 1; i++) {
            if (CdgOverloadMath.sameView(64.0F, 5, 64.0F, 5)) {
                settle++;
            }
            assertFalse(CdgOverloadMath.isArmed(settle));
        }
        settle++;
        assertTrue(CdgOverloadMath.isArmed(settle));
        assertTrue(CdgOverloadMath.isArmed(settle + 1000));
    }

    @Test
    void sameViewUsesExactFloatAndSizeEquality() {
        assertTrue(CdgOverloadMath.sameView(1.5F, 3, 1.5F, 3));
        assertFalse(CdgOverloadMath.sameView(1.5F, 3, 1.5000001F, 3));
        assertFalse(CdgOverloadMath.sameView(1.5F, 3, 1.5F, 4));
    }
}
