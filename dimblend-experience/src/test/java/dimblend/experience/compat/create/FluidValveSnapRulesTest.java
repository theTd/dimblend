package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 阀门指针回拉口径（LOCKED DECISION #12 {@code valve_snap_revert}）。 */
class FluidValveSnapRulesTest {

    @Test
    void stationaryOnlyWhenSpeedIsZeroWithinEpsilon() {
        assertTrue(FluidValveSnapRules.isStationary(0.0F));
        assertTrue(FluidValveSnapRules.isStationary(1.0e-6F));
        assertTrue(FluidValveSnapRules.isStationary(-1.0e-6F));
        assertFalse(FluidValveSnapRules.isStationary(0.5F), "还在转");
        assertFalse(FluidValveSnapRules.isStationary(-0.5F), "反转也算在转");
    }

    @Test
    void unfinishedIsStrictlyBetweenZeroAndOne() {
        assertTrue(FluidValveSnapRules.isUnfinished(0.3F));
        assertTrue(FluidValveSnapRules.isUnfinished(0.999F));
        assertFalse(FluidValveSnapRules.isUnfinished(0.0F), "到位 0 由原版翻开关");
        assertFalse(FluidValveSnapRules.isUnfinished(1.0F), "到位 1 由原版翻开关");
    }

    @Test
    void snapTargetFollowsEnabledFlag() {
        assertEquals(1.0F, FluidValveSnapRules.snapTarget(true));
        assertEquals(0.0F, FluidValveSnapRules.snapTarget(false));
    }

    @Test
    void stepTowardNeverOvershoots() {
        assertEquals(0.55F, FluidValveSnapRules.stepToward(0.30F, 1.0F, 0.25F), 1.0e-6F);
        assertEquals(0.05F, FluidValveSnapRules.stepToward(0.30F, 0.0F, 0.25F), 1.0e-6F);
        assertEquals(1.0F, FluidValveSnapRules.stepToward(0.90F, 1.0F, 0.25F), "一步够到就落位");
        assertEquals(0.0F, FluidValveSnapRules.stepToward(0.05F, 0.0F, 0.25F));
    }

    @Test
    void reachedUsesEpsilon() {
        assertTrue(FluidValveSnapRules.reached(0.99995F, 1.0F));
        assertFalse(FluidValveSnapRules.reached(0.99F, 1.0F));
    }

    @Test
    void snapsBackInFewTicks() {
        // 0.9 → 1 需要 1 拍；0.1 → 0 也是 1 拍；0.5 需要 2 拍
        float pointer = 0.5F;
        int ticks = 0;
        while (!FluidValveSnapRules.reached(pointer, 1.0F) && ticks < 10) {
            pointer = FluidValveSnapRules.stepToward(pointer, 1.0F, FluidValveSnapRules.SNAP_STEP_PER_TICK);
            ticks++;
        }
        assertEquals(2, ticks);
        assertEquals(1.0F, pointer);
    }
}
