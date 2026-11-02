package dimblend.experience.compat.create.observer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 观察者烈焰人红石输出关断防抖的判定表（用户 2026-11-02 口径：只在关断加 0.5 s 延迟，开启仍然即时）。
 *
 * <p>{@link ObserverBlazeSignal} 是纯状态机：输入本拍扫没扫到标记岩浆 + 当前 {@code POWERED}，
 * 输出本拍该写的 {@code POWERED}。这些用例把四条口径钉死，避免以后有人在方块实体里悄悄改回
 * “当拍关断”：</p>
 *
 * <ol>
 * <li>立即开：扫到的那一拍就是高电平；</li>
 * <li>延迟关：连续 10 拍扫不到才掉电（0.5 s @ 20 tps）；</li>
 * <li>中途复位：宽限期内重新扫到，计数器清零、重新从 0 数；</li>
 * <li>重复开不叠加：持续扫到不会攒倒计时，也不会缩短下一次关断。</li>
 * </ol>
 */
class ObserverBlazeSignalTest {
    @Test
    void onIsImmediate() {
        ObserverBlazeSignal signal = new ObserverBlazeSignal();
        assertTrue(signal.step(true, false), "扫到标记岩浆的那一拍必须立刻输出高电平");
        assertEquals(0, signal.unmarkedTicks(), "开后不该留下关断倒计时");
        assertTrue(signal.step(true, true), "持续扫到时保持高电平");
        assertEquals(0, signal.unmarkedTicks());
    }

    @Test
    void offWaitsTheFullWindow() {
        ObserverBlazeSignal signal = new ObserverBlazeSignal();
        assertTrue(signal.step(true, false), "先亮起来");

        // 前 9 拍仍在 0.5 s 宽限内：继续维持高电平，计数逐拍累加。
        for (int tick = 1; tick < ObserverBlazeSignal.OFF_DELAY_TICKS; tick++) {
            assertTrue(signal.step(false, true), "第 " + tick + " 拍仍在 0.5 s 宽限内，不应掉电");
            assertEquals(tick, signal.unmarkedTicks());
        }

        // 第 10 拍宽限走完：这一拍才允许写 POWERED=false。
        assertFalse(signal.step(false, true), "连续第 10 拍扫不到，必须掉电");
        assertEquals(0, signal.unmarkedTicks(), "掉电后计数归零");

        // 掉电后继续扫不到：维持低电平，不能自己再亮。
        assertFalse(signal.step(false, false));
    }

    @Test
    void aMarkerInsideTheWindowRestartsTheCount() {
        ObserverBlazeSignal signal = new ObserverBlazeSignal();
        assertTrue(signal.step(true, false), "先亮起来");

        for (int tick = 1; tick <= 4; tick++) {
            assertTrue(signal.step(false, true));
        }
        assertEquals(4, signal.unmarkedTicks(), "先攒 4 拍未扫到");

        // 中途重新扫到：计数清零，当拍立刻为高。
        assertTrue(signal.step(true, true));
        assertEquals(0, signal.unmarkedTicks(), "中途扫到必须把倒计时清零");

        // 复位后仍要重新数满 10 拍，不能沿用之前那 4 拍。
        for (int tick = 1; tick < ObserverBlazeSignal.OFF_DELAY_TICKS; tick++) {
            assertTrue(signal.step(false, true), "计数被复位，必须重新数满 10 拍");
        }
        assertFalse(signal.step(false, true), "复位之后也只在第 10 拍掉电");
    }

    @Test
    void repeatedOnDoesNotStackOrShortenTheWindow() {
        ObserverBlazeSignal signal = new ObserverBlazeSignal();

        // 连续 5 拍扫到：状态保持高电平，倒计时始终是 0（“重复开不叠加”）。
        for (int i = 0; i < 5; i++) {
            assertTrue(signal.step(true, i > 0), "重复的开不改变输出");
            assertEquals(0, signal.unmarkedTicks(), "重复的开不留关断倒计时");
        }

        // 重复开之后关断，仍然是完整的 10 拍，不会因为前面开过多次而提前掉电。
        for (int tick = 1; tick < ObserverBlazeSignal.OFF_DELAY_TICKS; tick++) {
            assertTrue(signal.step(false, true), "重复开过也不能缩短宽限");
        }
        assertFalse(signal.step(false, true), "第 10 拍才掉电");
    }

    @Test
    void anUnpoweredObserverDoesNotBankAnOffCountdown() {
        ObserverBlazeSignal signal = new ObserverBlazeSignal();

        // 从未亮过：扫不到也不该攒倒计时，否则重亮后的第一次关断会提前。
        for (int tick = 0; tick < 50; tick++) {
            assertFalse(signal.step(false, false));
        }
        assertEquals(0, signal.unmarkedTicks(), "没亮过就不该攒关断倒计时");

        assertTrue(signal.step(true, false), "重亮当拍立即为高");
        for (int tick = 1; tick < ObserverBlazeSignal.OFF_DELAY_TICKS; tick++) {
            assertTrue(signal.step(false, true), "重亮后同样要数满 10 拍");
        }
        assertFalse(signal.step(false, true), "第 10 拍才掉电");
    }

    /** 0.5 s × 20 tps = 10 tick；这个数来自用户口径，不是性能调参。 */
    @Test
    void theWindowIsHalfASecond() {
        assertEquals(10, ObserverBlazeSignal.OFF_DELAY_TICKS, "关断宽限必须是 10 tick（0.5 s）");
    }
}
