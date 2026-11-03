package dimblend.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 新增的「动态/固定音量」口径（用户 2026-11-03）：
 * 0% = 跟随外部红石输入；非 0% = 有输入时按固定音量播放，不再受信号强度影响。
 */
class RadioVolumeRuleTest {

    @Test
    void dynamicFollowsTheSideSignal() {
        assertEquals(10, RadioVolumeRule.decide(1, RadioVolumeRule.DYNAMIC).volumePercent());
        assertEquals(80, RadioVolumeRule.decide(8, RadioVolumeRule.DYNAMIC).volumePercent());
        assertEquals(150, RadioVolumeRule.decide(15, RadioVolumeRule.DYNAMIC).volumePercent());
    }

    @Test
    void dynamicWithNoInputIsSilent() {
        assertTrue(RadioVolumeRule.decide(0, RadioVolumeRule.DYNAMIC).verdict()
                == RadioVolumeRule.Verdict.SILENT);
    }

    @Test
    void fixedIgnoresSignalStrengthWhileAnInputExists() {
        for (int signal = 1; signal <= 15; signal++) {
            for (int fixed : new int[] {10, 50, 100, 150}) {
                RadioVolumeRule.Setting setting = RadioVolumeRule.decide(signal, fixed);
                assertTrue(setting.playing(), "signal " + signal + " must still gate playback");
                assertEquals(fixed, setting.volumePercent(),
                        "fixed volume must not follow the signal strength (signal=" + signal + ")");
            }
        }
    }

    @Test
    void fixedWithNoInputIsSilent() {
        assertTrue(RadioVolumeRule.decide(0, 100).verdict() == RadioVolumeRule.Verdict.SILENT,
                "固定音量不等于永远播放：没有外部输入仍然不播");
    }

    @Test
    void theRangeIsZeroToFifteenHundredInStepsOfTen() {
        assertEquals(RadioVolumeRule.DYNAMIC, RadioVolumeRule.clampToStep(-5));
        assertEquals(RadioVolumeRule.DYNAMIC, RadioVolumeRule.clampToStep(0));
        assertEquals(10, RadioVolumeRule.clampToStep(10));
        assertEquals(150, RadioVolumeRule.clampToStep(150));
        assertEquals(150, RadioVolumeRule.clampToStep(999));
        // Off-grid values snap instead of being rejected, so an old save keeps working.
        assertEquals(10, RadioVolumeRule.clampToStep(7));
        assertEquals(20, RadioVolumeRule.clampToStep(15));
    }

    @Test
    void theGridCheckMatchesTheClamp() {
        for (int percent = 0; percent <= 150; percent += 10) {
            assertTrue(RadioVolumeRule.isOnGrid(percent), "on grid: " + percent);
            assertEquals(percent, RadioVolumeRule.clampToStep(percent));
        }
        for (int percent : new int[] {5, 7, 23, 151, -10}) {
            assertFalse(RadioVolumeRule.isOnGrid(percent), "off grid: " + percent);
        }
    }
}