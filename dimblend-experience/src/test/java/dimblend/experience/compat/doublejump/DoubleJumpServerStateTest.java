package dimblend.experience.compat.doublejump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link DoubleJumpServerState} 单测：一次滞空只放行一次、落地复位、触发后进入 10 tick 冷却。
 */
class DoubleJumpServerStateTest {

    @Test
    void oneTriggerPerAirtimeAndResetOnLanding() {
        DoubleJumpServerState state = new DoubleJumpServerState();

        assertTrue(state.canTrigger());
        state.onTriggered();
        assertTrue(state.isUsed());
        assertFalse(state.canTrigger(), "同一滞空第二次包必须拒绝");

        for (int i = 0; i < 40; i++) {
            state.tickCooldown();
        }
        assertFalse(state.canTrigger(), "冷却结束也不得在未落地前重新放行");

        state.reset();
        assertFalse(state.isUsed());
        assertTrue(state.canTrigger(), "落地后允许下一次滞空");
    }

    @Test
    void cooldownCountsDownToZeroAndIsClearedByReset() {
        DoubleJumpServerState state = new DoubleJumpServerState();

        state.onTriggered();
        assertEquals(DoubleJumpServerState.COOLDOWN_TICKS, state.cooldownTicks());
        for (int i = 0; i < DoubleJumpServerState.COOLDOWN_TICKS; i++) {
            state.tickCooldown();
        }
        assertEquals(0, state.cooldownTicks());
        state.tickCooldown();
        assertEquals(0, state.cooldownTicks(), "冷却不得减到负数");

        state.reset();
        assertEquals(0, state.cooldownTicks());
    }
}
