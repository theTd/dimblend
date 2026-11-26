package dimblend.experience.compat.cdg;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 活塞损坏态的状态机（2026-11-25）：标记损坏要顺手清掉引信/闩锁，换活塞要整体归零。
 * 以及损坏态黑烟的节流/交替（纯函数）。
 * <p>2026-11-27 追加：损坏机器"非扳手破坏 → 黄铜板 x2"的常量，以及右键路由
 * （活塞修复 / 扳手"坏掉了 要先修好" / 其它物品"已损坏 需更换活塞"）。</p>
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

    @Test
    void damagedEngineScrapsIntoTwoBrassSheets() {
        assertEquals(2, CdgEngineDamage.DAMAGED_SCRAP_COUNT,
                "损坏的柴油机被非扳手方式破坏后掉黄铜板 x2（不是引擎本体）");
    }

    @Test
    void rightClickRoutingIgnoresHealthyEngines() {
        // 没坏：活塞/扳手/别的物品一律不干预，原版 useItemOn（升级件、扳手转动）照常
        for (boolean holdingPiston : new boolean[] {true, false}) {
            for (boolean holdingWrench : new boolean[] {true, false}) {
                assertEquals(CdgEngineDamage.RightClickAction.IGNORE,
                        CdgEngineDamage.decideRightClick(false, holdingPiston, holdingWrench),
                        "piston=" + holdingPiston + " wrench=" + holdingWrench);
            }
        }
    }

    @Test
    void rightClickRoutingOnDamagedEngineSeparatesPistonWrenchAndOtherItems() {
        assertEquals(CdgEngineDamage.RightClickAction.REPAIR,
                CdgEngineDamage.decideRightClick(true, true, false), "手持活塞要能修");
        // 这条就是本次需求：普通扳手右键不再播"已损坏 需更换活塞"，改播"坏掉了 要先修好"
        assertEquals(CdgEngineDamage.RightClickAction.DENY_WRENCH,
                CdgEngineDamage.decideRightClick(true, false, true), "手持扳手要给修好提示");
        assertEquals(CdgEngineDamage.RightClickAction.DENY_OTHER,
                CdgEngineDamage.decideRightClick(true, false, false), "其它物品仍是换活塞提示");
    }

    @Test
    void pistonWinsWhenHoldingBothPistonAndWrench() {
        // 别把唯一能把机器救回来的操作挡掉
        assertEquals(CdgEngineDamage.RightClickAction.REPAIR,
                CdgEngineDamage.decideRightClick(true, true, true));
    }
}
