package dimblend.experience.jump;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐 tick 钉死真实 {@code LocalPlayer.aiStep} 时序：地面按下的那个 tick 传入的
 * {@code onGround} 必须是移动前的 true，空中续按必须是 {@code prevJumping=true}。
 */
class MidAirJumpStateTest {

    @Test
    void holdingSpaceAfterGroundJumpNeverTriggersMidAirJump() {
        MidAirJumpState state = new MidAirJumpState();
        // 地面起跳 tick：移动前仍在地面，按下沿成立，但 eligible=false。
        assertFalse(tick(state, true, false, true));
        // 离地后一直按住空格：没有新的按下沿。
        for (int i = 0; i < 20; i++) {
            assertFalse(tick(state, false, true, true));
        }
        assertFalse(state.isUsed());
    }

    @Test
    void releasingAndPressingAgainInAirTriggersExactlyOncePerAirbornePhase() {
        MidAirJumpState state = new MidAirJumpState();
        assertFalse(tick(state, true, false, true));   // 地面起跳
        assertFalse(tick(state, false, true, false));  // 空中松开
        assertTrue(tick(state, false, false, true));   // 空中再按 -> 二段跳
        assertTrue(state.isUsed());
        assertFalse(tick(state, false, true, false));  // 再松开
        assertFalse(tick(state, false, false, true));  // 同一滞空再按 -> 拒绝
    }

    @Test
    void walkingOffACliffAllowsOneJumpToBreakTheFall() {
        MidAirJumpState state = new MidAirJumpState();
        // 没有地面起跳，直接坠落：上一 tick 未按键。
        assertFalse(tick(state, false, false, false));
        assertTrue(tick(state, false, false, true));
        assertFalse(tick(state, false, true, false));
        assertFalse(tick(state, false, false, true));
    }

    @Test
    void everyRechargeStateRefillsOneMidAirJump() {
        for (int mode = 0; mode < 6; mode++) {
            MidAirJumpState state = new MidAirJumpState();
            assertTrue(tick(state, false, false, true));
            assertFalse(tick(state, false, true, false));
            boolean recharged = switch (mode) {
                case 0 -> tick(state, true, false, false, false, false, false, false, false, false, 0, false, false);
                case 1 -> tick(state, false, true, false, false, false, false, false, false, false, 0, false, false);
                case 2 -> tick(state, false, false, true, false, false, false, false, false, false, 0, false, false);
                case 3 -> tick(state, false, false, false, true, false, false, false, false, false, 0, false, false);
                case 4 -> tick(state, false, false, false, false, true, false, false, false, false, 0, false, false);
                case 5 -> tick(state, false, false, false, false, false, true, false, false, false, 0, false, false);
                default -> throw new AssertionError(mode);
            };
            assertFalse(recharged, "recharge tick must never itself trigger a jump, mode " + mode);
            assertFalse(state.isUsed(), "recharge must clear the per-airborne flag, mode " + mode);
            assertTrue(tick(state, false, false, true), "one jump after recharge, mode " + mode);
            assertFalse(tick(state, false, true, false));
            assertFalse(tick(state, false, false, true), "still only one per airborne phase, mode " + mode);
        }
    }

    @Test
    void eachEligibilityGuardBlocksTriggering() {
        // 全部放行的基准。
        assertTrue(MidAirJumpRules.eligible(false, false, false, false, false, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(true, false, false, false, false, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, true, false, false, false, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, true, false, false, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, true, false, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, false, true, false, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, false, false, true, false, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, false, false, false, true, false, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, false, false, false, false, true, false));
        assertFalse(MidAirJumpRules.eligible(false, false, false, false, false, false, false, false, true));

        MidAirJumpState state = new MidAirJumpState();
        assertFalse(tick(state, false, false, false, false, false, false, false, true, false, 0, false, true));
        assertFalse(state.isUsed());
    }

    @Test
    void autoJumpSpoofedKeyPressIsNotARisingEdge() {
        assertTrue(MidAirJumpRules.pressedRisingEdge(true, false, 0));
        assertFalse(MidAirJumpRules.pressedRisingEdge(true, false, 1));
        assertFalse(MidAirJumpRules.pressedRisingEdge(true, true, 0));
        assertFalse(MidAirJumpRules.pressedRisingEdge(false, false, 0));

        MidAirJumpState state = new MidAirJumpState();
        assertFalse(tick(state, false, false, false, false, false, false, false, false, false, 1, false, true));
        assertFalse(state.isUsed());
    }

    @Test
    void togglingOffCreativeFlightMidAirDoesNotBounceUpward() {
        MidAirJumpState state = new MidAirJumpState();
        // 双击空格取消创造飞行的那一 tick：HEAD 时 wasFlying=true，aiStep 中途 abilities.flying 翻成 false；
        // flyingOrJustStopped=true 必须抑制该上升沿，落地前若再次单按仍保留一次空中跳。
        boolean flyingOrJustStopped = false || true;
        assertFalse(tick(state, false, false, false, false, flyingOrJustStopped, false, false, false, false, 0,
                false, true));
        assertFalse(state.isUsed());
        assertFalse(tick(state, false, true, false)); // 松开空格
        assertTrue(tick(state, false, false, true));  // 下落途中再按一次可正常二段跳
    }

    @Test
    void payloadCodecRoundTripsWithoutBytes() {
        var original = new MidAirJumpPayload();
        var buffer = Unpooled.buffer();
        try {
            MidAirJumpPayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(0, buffer.readableBytes());
            assertEquals(original, MidAirJumpPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static boolean tick(MidAirJumpState state, boolean onGround, boolean prevJumping,
                               boolean currentJumping) {
        return tick(state, onGround, false, false, false, false, false, false, false, false, 0,
                prevJumping, currentJumping);
    }

    private static boolean tick(MidAirJumpState state, boolean onGround, boolean inFluid, boolean climbable,
                                boolean passenger, boolean flying, boolean fallFlying, boolean spectator,
                                boolean levitation, boolean dead, int autoJumpTime,
                                boolean prevJumping, boolean currentJumping) {
        boolean recharge = MidAirJumpRules.shouldRecharge(onGround, inFluid, climbable, passenger,
                flying, fallFlying);
        boolean eligible = MidAirJumpRules.eligible(onGround, flying, fallFlying, passenger, spectator,
                inFluid, climbable, levitation, dead);
        boolean pressed = MidAirJumpRules.pressedRisingEdge(currentJumping, prevJumping, autoJumpTime);
        return state.tick(recharge, eligible, pressed);
    }
}
