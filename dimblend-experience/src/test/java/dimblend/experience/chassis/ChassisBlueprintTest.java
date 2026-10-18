package dimblend.experience.chassis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChassisBlueprintTest {
    @Test void correctedCarriageHasOnlyTwoVisibleBogeysAndFourteenCleanSlabs() {
        var plan = ChassisBlueprint.CARRIAGE;
        assertEquals(2, count(plan, 'W'));
        assertEquals(0, count(plan, 'I'));
        assertEquals(0, count(plan, 'S'));
        assertEquals(14, count(plan, 'N'));
        assertEquals("HNNNH", plan.row(8));
        assertEquals("HNNNH", plan.row(36));
        for (int row : new int[] {3, 4, 6, 7, 37, 38, 40, 41}) {
            assertEquals("P.N.P", plan.row(row));
        }
        assertEquals("P.W.P", plan.row(5));
        assertEquals("P.W.P", plan.row(39));
    }

    @Test void powerChassisHasSixBogeysInTwoPoweredGroups() {
        var plan = ChassisBlueprint.POWER;
        assertEquals(4, count(plan, 'W'));
        assertEquals(2, count(plan, 'I'));
        assertEquals(12, count(plan, 'S'));
        assertEquals("P.I.P", plan.row(8));
        assertEquals("P.I.P", plan.row(36));
    }

    @Test void bothPlansHaveA41BlockDeckAndSymmetricCouplersAt45BlockExtents() {
        for (var plan : ChassisBlueprint.values()) {
            assertEquals("..C..", plan.row(0));
            assertEquals("..C..", plan.row(44));
            assertEquals("..B..", plan.row(1));
            assertEquals("..B..", plan.row(43));
            assertEquals("HHDHH", plan.row(2));
            assertEquals("HHDHH", plan.row(42));
            assertEquals("H...H", plan.row(22));
            assertTrue(plan.cells().stream().allMatch(cell -> Math.abs(cell.x()) <= 22 && Math.abs(cell.z()) <= 2));
            assertEquals(plan.cells().size(), plan.cells().stream().map(cell -> cell.x() + ":" + cell.z()).distinct().count());
        }
    }

    private static long count(ChassisBlueprint plan, char symbol) {
        return plan.cells().stream().filter(cell -> cell.symbol() == symbol).count();
    }
}
