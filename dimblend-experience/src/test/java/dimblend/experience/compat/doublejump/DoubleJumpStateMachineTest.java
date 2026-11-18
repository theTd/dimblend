package dimblend.experience.compat.doublejump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link DoubleJumpStateMachine} 的逐 tick 单测：钉死“按住空格不自动触发”“空中松开后再按立即触发”
 * “快速二连点不再被 airTicks 门槛丢弃”“一次滞空只触发一次”“地面/水中/飞行/鞘翅/乘骑全部拒绝”。
 */
class DoubleJumpStateMachineTest {

    @Test
    void holdingSpaceFromGroundStaysDisarmedUntilReleasedInAir() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(true, false, true, true), "地面按下起跳不触发");
        assertFalse(machine.tick(false, false, true, false), "离地第 1 tick 仍按住");
        assertFalse(machine.tick(false, false, true, false), "离地第 2 tick 仍按住");
        assertFalse(machine.tick(false, false, true, false), "一直按住不应出现按下沿");

        assertFalse(machine.tick(false, false, false, false), "空中松开只 arm，不触发");
        assertTrue(machine.isArmed());
        assertTrue(machine.tick(false, false, true, true), "松开后的第一次按下立即触发");
        assertTrue(machine.isUsed());
    }

    @Test
    void fastGroundTapAndSecondPressOnAirTickTwoTriggers() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(true, false, true, true), "地面起跳点按");
        assertFalse(machine.tick(false, false, false, false), "离地第 1 tick 已松开");
        assertTrue(machine.tick(false, false, true, true), "离地第 2 tick 的第二次按下必须触发");
    }

    @Test
    void walkingOffALedgeThenPressingTriggersOnTheFirstAirTick() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(true, false, false, false), "走下台阶时未按键");
        assertTrue(machine.tick(false, false, true, true), "空中第一次按下立即触发");
    }

    @Test
    void onlyOneDoubleJumpPerAirtime() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(true, false, true, true));
        assertFalse(machine.tick(false, false, false, false));
        assertTrue(machine.tick(false, false, true, true));

        assertFalse(machine.tick(false, false, false, false), "空中再次松开不重置 used");
        assertFalse(machine.tick(false, false, true, true), "同一滞空第二次按下必须拒绝");
        assertFalse(machine.tick(false, false, false, false));
        assertFalse(machine.tick(false, false, true, true));

        assertFalse(machine.tick(true, false, false, false), "落地复位");
        assertTrue(machine.tick(false, false, true, true), "落地后下一次滞空可再次触发");
    }

    @Test
    void groundedStatesAlwaysResetAndNeverTrigger() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(false, false, false, false));
        assertFalse(machine.tick(true, false, true, true), "地面按下");
        assertFalse(machine.tick(true, false, false, false), "水中/攀爬同属 grounded 口径");
        assertFalse(machine.isUsed());
    }

    @Test
    void blockedStatesFlyingElytraAndPassengerNeverTrigger() {
        for (int blockedCase = 0; blockedCase < 3; blockedCase++) {
            DoubleJumpStateMachine machine = new DoubleJumpStateMachine();
            assertFalse(machine.tick(false, false, false, false), "先在空中 arm");
            assertFalse(machine.tick(false, true, true, true), "禁用态必须拒绝并复位");
            assertFalse(machine.tick(false, true, true, true), "持续禁用态仍拒绝");
            assertFalse(machine.isUsed());
        }
    }

    @Test
    void risingEdgeFallbackWorksWhenClickCounterIsUnavailable() {
        DoubleJumpStateMachine machine = new DoubleJumpStateMachine();

        assertFalse(machine.tick(false, false, false, false), "空中 arm");
        assertTrue(machine.tick(false, false, true, false), "仅用 isDown 的上升沿也必须触发");
    }
}
