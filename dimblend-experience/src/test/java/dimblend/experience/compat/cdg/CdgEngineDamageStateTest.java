package dimblend.experience.compat.cdg;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 活塞损坏态的状态机（2026-11-25）：标记损坏要顺手清掉引信/闩锁，换活塞要整体归零。
 * 以及损坏态黑烟的节流/交替（纯函数）。
 */
class CdgEngineDamageStateTest {

    @Test
    void markingDamagedClearsFuseAndLatchAndIsIdempotent() {
        CdgEngineState state = new CdgEngineState();
        state.fuseActive = true;
        state.fuseTicksLeft = 12;
        state.overloadLatched = true;
        state.overloadTicks = 40;
        state.rampTicks = 320;
        assertTrue(state.markPistonDamaged());
        assertTrue(state.pistonDamaged);
        assertFalse(state.fuseActive);
        assertFalse(state.overloadLatched);
        assertEquals(0, state.fuseTicksLeft);
        assertEquals(0, state.overloadTicks);
        assertEquals(0, state.rampTicks);
        assertFalse(state.markPistonDamaged(), "重复标记不应算状态变化");
    }

    @Test
    void repairingPistonResetsEverythingIncludingBoost() {
        CdgEngineState state = new CdgEngineState();
        state.pistonDamaged = true;
        state.overloadLatched = true;
        state.fuseActive = true;
        state.fuseTicksLeft = 44;
        state.overloadTicks = 17;
        state.rampTicks = 900;
        state.boostConsumed = true;
        state.fluctTicksLeft = 30;
        state.fluctFactor = 0.85F;
        assertTrue(state.repairPiston());
        assertFalse(state.pistonDamaged);
        assertFalse(state.overloadLatched);
        assertFalse(state.fuseActive);
        assertFalse(state.boostConsumed);
        assertEquals(0, state.fuseTicksLeft);
        assertEquals(0, state.overloadTicks);
        assertEquals(0, state.rampTicks);
        assertEquals(0, state.fluctTicksLeft);
        assertFalse(state.repairPiston(), "健康机器换活塞不应算状态变化");
    }

    @Test
    void fuelRefillAloneCannotRepairADamagedPiston() {
        CdgEngineState state = new CdgEngineState();
        assertTrue(state.markPistonDamaged());
        // 模拟"重新加油"路径：只清闩锁，损坏位必须留着
        state.overloadLatched = false;
        state.rampTicks = 0;
        state.boostConsumed = false;
        assertTrue(state.pistonDamaged, "重新加油不得洗掉活塞损坏");
        // 关过载开关走 clearOverload：它只管闩锁/引信，绝不能碰损坏位
        assertFalse(state.clearOverload(), "已无闩锁/引信可清");
        assertTrue(state.pistonDamaged);
    }

    @Test
    void damagedSmokeEveryFiveTicksAndAlternates() {
        assertEquals(5, CdgOverloadMath.DAMAGED_SMOKE_INTERVAL_TICKS);
        for (long t = 0; t < 100; t++) {
            assertEquals(t % 5 == 0, CdgOverloadMath.shouldEmitDamagedSmoke(t), "tick " + t);
        }
        // 0~4 tick 用大烟，5~9 tick 用普通烟，10~14 大烟……
        assertTrue(CdgOverloadMath.useLargeSmoke(0));
        assertTrue(CdgOverloadMath.useLargeSmoke(4));
        assertFalse(CdgOverloadMath.useLargeSmoke(5));
        assertFalse(CdgOverloadMath.useLargeSmoke(9));
        assertTrue(CdgOverloadMath.useLargeSmoke(10));
    }
}
