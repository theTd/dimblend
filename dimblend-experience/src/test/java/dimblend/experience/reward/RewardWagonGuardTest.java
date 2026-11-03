package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.reward.RewardWagonGuard.Brick;
import dimblend.experience.reward.RewardWagonGuard.Source;
import dimblend.experience.reward.RewardWagonGuard.Verdict;
import org.junit.jupiter.api.Test;

/**
 * 奖励车厢的交互规则，2026-11-03 口径：
 * 生存模式不许动结构、不许放、只许取内容；创造模式可以拆、可以放；马达转速永远锁死。
 */
class RewardWagonGuardTest {

    @Test
    void survivalCannotBreakStructure() {
        assertEquals(Verdict.DENY_STRUCTURE,
                RewardWagonGuard.mayRemove(true, Brick.SLAB, Source.PLAYER, false));
        assertEquals(Verdict.DENY_STRUCTURE,
                RewardWagonGuard.mayRemove(true, Brick.BOGEY, Source.PLAYER, false));
    }

    /**
     * 用户原话（2026-11-03）："扳手拆卸伪装方块也不可以 但创造模式可以"。
     * 这覆盖了上一轮的"任何人都不许动"，是本测试存在的原因。
     */
    @Test
    void creativeMayBreakAndWrenchTheStructure() {
        for (Brick brick : new Brick[] {Brick.SLAB, Brick.BOGEY, Brick.COUPLER}) {
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, brick, Source.PLAYER, true),
                    "creative must be able to remove " + brick);
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, brick, true),
                    "creative must be able to interact with " + brick);
        }
    }

    @Test
    void survivalCouplersStayLocked() {
        assertEquals(Verdict.DENY_COUPLER,
                RewardWagonGuard.mayRemove(true, Brick.COUPLER, Source.PLAYER, false));
        assertEquals(Verdict.DENY_COUPLER, RewardWagonGuard.mayInteract(true, Brick.COUPLER, false));
    }

    @Test
    void contentMayBeRemovedFromAnySourceInAnyMode() {
        for (boolean creative : new boolean[] {true, false}) {
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.PLAYER, creative));
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.OTHER, creative));
        }
    }

    @Test
    void contentStaysInteractableSoTheChestOpens() {
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.CONTENT, false));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.CONTENT, true));
    }

    @Test
    void survivalCannotInteractWithStructure() {
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayInteract(true, Brick.SLAB, false));
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayInteract(true, Brick.BOGEY, false));
    }

    /**
     * 用户原话："该sable结构上仅能移除内容 不允许添加 <更改：但创造模式可以>"。
     * 创造模式放进去的方块按用户选择算内容，所以这里只钉"能放"，不记来源。
     */
    @Test
    void survivalCannotPlaceButCreativeCan() {
        assertEquals(Verdict.DENY_PLACE, RewardWagonGuard.mayPlace(true, Source.PLAYER, false));
        assertEquals(Verdict.DENY_PLACE, RewardWagonGuard.mayPlace(true, Source.OTHER, false));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayPlace(true, Source.PLAYER, true));
    }

    @Test
    void everythingOutsideTheWagonIsUntouched() {
        for (Brick brick : Brick.values()) {
            for (boolean creative : new boolean[] {true, false}) {
                assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(false, brick, Source.PLAYER, creative));
                assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(false, brick, creative));
                assertEquals(Verdict.ALLOW, RewardWagonGuard.mayPlace(false, Source.PLAYER, creative));
            }
        }
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayPlace(false, Source.OTHER, false));
    }

    @Test
    void theDenyHelperAgreesWithTheVerdicts() {
        assertFalse(RewardWagonGuard.denies(Verdict.ALLOW));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_STRUCTURE));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_COUPLER));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_PLACE));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_MOTOR_SPEED));
    }

    /**
     * 马达转速是创造模式豁免唯一不覆盖的东西：需求把它钉在 256 并标注"不可调整"。
     */
    @Test
    void theMotorMayBeBrokenButItsSpeedIsLockedInCreativeToo() {
        for (boolean creative : new boolean[] {true, false}) {
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.MOTOR, Source.PLAYER, creative));
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.MOTOR, creative),
                    "the motor itself is not locked, only its speed panel");
        }
        assertEquals(Verdict.DENY_MOTOR_SPEED, RewardWagonGuard.mayAdjustMotorSpeed(true),
                "creative does not lift the motor speed lock");
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayAdjustMotorSpeed(false),
                "outside a wagon a wrench still works normally");
    }

    @Test
    void theMotorWagonsClutchAndLeverStayInteractable() {
        // The clutch must be switchable and the lever must be pullable, or the wagon can never
        // be started. Both are content, not structure.
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.CONTENT, false));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.PLAYER, false));
    }

    @Test
    void everyBrickAndModeCombinationIsCovered() {
        // Exhaustive: no combination may throw or fall through to a surprise. With creative off,
        // only content and the motor may leave; with creative on, everything may.
        for (Brick brick : Brick.values()) {
            for (Source source : Source.values()) {
                boolean survivalExpected = brick == Brick.CONTENT || brick == Brick.MOTOR;
                assertEquals(survivalExpected,
                        RewardWagonGuard.mayRemove(true, brick, source, false) == Verdict.ALLOW,
                        "survival removal of " + brick + " from " + source);
                assertEquals(true,
                        RewardWagonGuard.mayRemove(true, brick, source, true) == Verdict.ALLOW,
                        "creative removal of " + brick + " from " + source);
            }
            assertEquals(true, RewardWagonGuard.mayInteract(true, brick, true) == Verdict.ALLOW,
                    "creative interaction with " + brick);
        }
    }
}