package dimblend.experience.compat.cdg;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CdgOverloadResetTest {
    @Test
    void disablingPenaltyCancelsFuseAndLatchWithoutResettingOtherBehavior() {
        var state = new CdgEngineState();
        state.fuseActive = true;
        state.fuseTicksLeft = 80;
        state.overloadLatched = true;
        state.overloadTicks = 30;
        state.rampTicks = 500;
        state.fluctFactor = 0.9F;
        state.fuelPresent = true;
        assertTrue(state.clearOverload());
        assertFalse(state.fuseActive);
        assertFalse(state.overloadLatched);
        assertEquals(0, state.fuseTicksLeft);
        assertEquals(0, state.overloadTicks);
        assertEquals(500, state.rampTicks);
        assertEquals(0.9F, state.fluctFactor);
        assertTrue(state.fuelPresent);
        assertFalse(state.clearOverload());
    }
}
