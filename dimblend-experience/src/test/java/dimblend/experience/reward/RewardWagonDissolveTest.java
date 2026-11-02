package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.reward.RewardWagonDissolve.Box;
import dimblend.experience.reward.RewardWagonDissolve.BlockPos;
import dimblend.experience.reward.RewardWagonDissolve.Occupant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When the wagon counts as emptied, and what counts as touching a gate. */
class RewardWagonDissolveTest {

    @Test
    void structureOnlyMeansEmpty() {
        assertTrue(RewardWagonDissolve.isEmpty(List.of(
                Occupant.STRUCTURE, Occupant.STRUCTURE, Occupant.STRUCTURE)));
    }

    @Test
    void anyContentKeepsTheWagonAlive() {
        assertFalse(RewardWagonDissolve.isEmpty(List.of(Occupant.STRUCTURE, Occupant.CONTENT)));
        assertFalse(RewardWagonDissolve.isEmpty(List.of(Occupant.CONTENT)));
    }

    @Test
    void anEmptyContainerDoesNotKeepTheWagonAlive() {
        // The player's call: the twilight chest counts as taken once emptied, so the wagon
        // dissolves without the player having to break the chest too.
        assertTrue(RewardWagonDissolve.isEmpty(List.of(Occupant.STRUCTURE, Occupant.EMPTY_CONTAINER)));
    }

    @Test
    void aFilledContainerDoesKeepItAlive() {
        assertFalse(RewardWagonDissolve.isEmpty(List.of(Occupant.STRUCTURE, Occupant.FILLED_CONTAINER)));
    }

    @Test
    void anEmptyWagonWithNoBlocksAtAllIsEmpty() {
        assertTrue(RewardWagonDissolve.isEmpty(List.of()));
    }

    @Test
    void aGateInsideTheBoxCounts() {
        Box box = new Box(-2, 65, -1, 2, 66, 1);
        assertTrue(RewardWagonDissolve.touchesGate(box, List.of(new BlockPos(0, 65, 0))));
        assertTrue(RewardWagonDissolve.touchesGate(box, List.of(new BlockPos(-2, 66, 1))),
                "a corner of the box counts");
    }

    @Test
    void aGateOutsideTheBoxDoesNot() {
        Box box = new Box(-2, 65, -1, 2, 66, 1);
        assertFalse(RewardWagonDissolve.touchesGate(box, List.of(new BlockPos(3, 65, 0))));
        assertFalse(RewardWagonDissolve.touchesGate(box, List.of(new BlockPos(0, 64, 0))));
        assertFalse(RewardWagonDissolve.touchesGate(box, List.of(new BlockPos(0, 65, 2))));
    }

    @Test
    void oneMatchingGateAmongManyIsEnough() {
        Box box = new Box(10, 65, -1, 12, 66, 1);
        assertTrue(RewardWagonDissolve.touchesGate(box, List.of(
                new BlockPos(0, 65, 0), new BlockPos(50, 65, 0), new BlockPos(11, 65, 0))));
    }

    @Test
    void theBoxTestIsInclusiveOnEveryFace() {
        Box box = new Box(0, 0, 0, 1, 1, 1);
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    assertTrue(box.contains(x, y, z), x + "," + y + "," + z);
                }
            }
        }
        assertFalse(box.contains(-1, 0, 0));
        assertFalse(box.contains(2, 0, 0));
    }

    @Test
    void theDelayIsHalfASecond() {
        assertEquals(10, RewardWagonDissolve.DISSOLVE_DELAY_TICKS);
    }
}