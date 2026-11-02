package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.reward.RewardWagonGuard.Brick;
import dimblend.experience.reward.RewardWagonGuard.Source;
import dimblend.experience.reward.RewardWagonGuard.Verdict;
import org.junit.jupiter.api.Test;

/** The "nobody may change the wagon, content may leave" rule. */
class RewardWagonGuardTest {

    @Test
    void structureCannotBeBrokenByAPlayer() {
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayRemove(true, Brick.SLAB, Source.PLAYER));
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayRemove(true, Brick.BOGEY, Source.PLAYER));
    }

    @Test
    void creativeModeIsNotAnException() {
        // The guard has no creative input at all; the same call is what a creative player
        // and a survival player make, and both are refused. This test pins that shape.
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayRemove(true, Brick.SLAB, Source.PLAYER));
    }

    @Test
    void couplersStayLockedForPlayers() {
        assertEquals(Verdict.DENY_COUPLER, RewardWagonGuard.mayRemove(true, Brick.COUPLER, Source.PLAYER));
        assertEquals(Verdict.DENY_COUPLER, RewardWagonGuard.mayInteract(true, Brick.COUPLER));
    }

    @Test
    void contentMayBeRemovedFromAnySource() {
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.PLAYER));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.OTHER));
    }

    @Test
    void contentStaysInteractableSoTheChestOpens() {
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.CONTENT));
    }

    @Test
    void structureCannotBeInteractedWith() {
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayInteract(true, Brick.SLAB));
        assertEquals(Verdict.DENY_STRUCTURE, RewardWagonGuard.mayInteract(true, Brick.BOGEY));
    }

    @Test
    void nothingMayBePlacedInsideTheWagonFromAnySource() {
        assertEquals(Verdict.DENY_PLACE, RewardWagonGuard.mayPlace(true, Source.PLAYER));
        assertEquals(Verdict.DENY_PLACE, RewardWagonGuard.mayPlace(true, Source.OTHER));
    }

    @Test
    void everythingOutsideTheWagonIsUntouched() {
        for (Brick brick : Brick.values()) {
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(false, brick, Source.PLAYER));
            assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(false, brick));
        }
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayPlace(false, Source.PLAYER));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayPlace(false, Source.OTHER));
    }

    @Test
    void theDenyHelperAgreesWithTheVerdicts() {
        assertFalse(RewardWagonGuard.denies(Verdict.ALLOW));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_STRUCTURE));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_COUPLER));
        assertTrue(RewardWagonGuard.denies(Verdict.DENY_PLACE));
    }

    @Test
    void theMotorMayBeBrokenButItsSpeedMayNotBeAdjusted() {
        // The requirement pins the motor at 256 rpm and marks it "不可调整". Breaking it is
        // content removal and stays allowed; re-rolling the value does not.
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.MOTOR, Source.PLAYER));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.MOTOR),
                "the motor itself is not locked, only its speed panel");
        assertEquals(Verdict.DENY_MOTOR_SPEED, RewardWagonGuard.mayAdjustMotorSpeed(true));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayAdjustMotorSpeed(false),
                "outside a wagon a wrench still works normally");
    }

    @Test
    void theMotorWagonsClutchAndLeverStayInteractable() {
        // The clutch must be switchable and the lever must be pullable, or the wagon can never
        // be started. Both are content, not structure.
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayInteract(true, Brick.CONTENT));
        assertEquals(Verdict.ALLOW, RewardWagonGuard.mayRemove(true, Brick.CONTENT, Source.PLAYER));
    }

    @Test
    void everyBrickAndSourceCombinationIsCovered() {
        // Exhaustive: no combination may throw or fall through to a surprise.
        for (Brick brick : Brick.values()) {
            for (Source source : Source.values()) {
                Verdict removal = RewardWagonGuard.mayRemove(true, brick, source);
                boolean allowed = removal == Verdict.ALLOW;
                // Content and the motor may be taken; slabs, bogeys and couplers may not.
                boolean expected = brick == Brick.CONTENT || brick == Brick.MOTOR;
                assertEquals(expected, allowed,
                        "removal of " + brick + " from " + source);
            }
            RewardWagonGuard.mayInteract(true, brick);
        }
    }
}