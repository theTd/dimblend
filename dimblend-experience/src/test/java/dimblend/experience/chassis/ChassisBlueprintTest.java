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

    @Test void powerBogeyTuningPowersBothEndsAndFloatsTheMiddleFour() {
        var plan = ChassisBlueprint.POWER;
        // Ranked from the X+ end: +17, +14, +11, -11, -14, -17.
        assertEquals(new ChassisBlueprint.BogeyTuning(32.0f, false), tuning(plan, 17));
        assertEquals(new ChassisBlueprint.BogeyTuning(32.0f, false), tuning(plan, -17));
        for (int x : new int[] {14, 11, -11, -14}) {
            assertEquals(new ChassisBlueprint.BogeyTuning(0.0f, true), tuning(plan, x), "power x=" + x);
        }
    }

    @Test void carriageBogeyTuningIsIdleAndRigid() {
        var plan = ChassisBlueprint.CARRIAGE;
        assertEquals(new ChassisBlueprint.BogeyTuning(0.0f, false), tuning(plan, 17));
        assertEquals(new ChassisBlueprint.BogeyTuning(0.0f, false), tuning(plan, -17));
        assertEquals(2, count(plan, 'W'));
    }

    @Test void drivingTheEndBogeysWakesOnlyTheCarriage() {
        for (int x = -ChassisBlueprint.HALF_LENGTH; x <= ChassisBlueprint.HALF_LENGTH; x++) {
            assertEquals(ChassisBlueprint.bogeyTuning(ChassisBlueprint.POWER, x),
                    ChassisBlueprint.bogeyTuning(ChassisBlueprint.POWER, x, true), "power x=" + x);
        }
        assertEquals(new ChassisBlueprint.BogeyTuning(32.0f, false),
                ChassisBlueprint.bogeyTuning(ChassisBlueprint.CARRIAGE, 17, true).orElseThrow());
        assertEquals(new ChassisBlueprint.BogeyTuning(32.0f, false),
                ChassisBlueprint.bogeyTuning(ChassisBlueprint.CARRIAGE, -17, true).orElseThrow());
        for (int x : new int[] {0, 1, -1, 11, -11, 22, -22}) {
            assertTrue(ChassisBlueprint.bogeyTuning(ChassisBlueprint.CARRIAGE, x, true).isEmpty(),
                    "no bogey sits at x=" + x);
        }
    }

    @Test void everyBogeyCarriesATuningAndNoOtherCoordinateDoes() {
        for (var plan : ChassisBlueprint.values()) {
            var bogeyXs = new java.util.HashSet<Integer>();
            for (var cell : plan.cells()) {
                if (cell.symbol() == 'W' || cell.symbol() == 'I') bogeyXs.add(cell.x());
            }
            for (int x = -ChassisBlueprint.HALF_LENGTH; x <= ChassisBlueprint.HALF_LENGTH; x++) {
                assertEquals(bogeyXs.contains(x), ChassisBlueprint.bogeyTuning(plan, x).isPresent(),
                        plan + " x=" + x);
            }
            for (int x : new int[] {0, 1, -1, 22, -22}) {
                assertTrue(ChassisBlueprint.bogeyTuning(plan, x).isEmpty(), plan + " x=" + x);
            }
        }
    }

    @Test void bogeyStressStaysInsideSimurailClampRange() {
        // simurail PhysicsBogeyOptions.setStress clamps to [0, 32]; keep every tuning inside it.
        for (var plan : ChassisBlueprint.values()) {
            for (int x = -ChassisBlueprint.HALF_LENGTH; x <= ChassisBlueprint.HALF_LENGTH; x++) {
                var tuning = ChassisBlueprint.bogeyTuning(plan, x);
                if (tuning.isEmpty()) continue;
                assertTrue(tuning.get().stress() >= 0.0f && tuning.get().stress() <= 32.0f,
                        plan + " x=" + x + " stress=" + tuning.get().stress());
            }
        }
    }

    private static ChassisBlueprint.BogeyTuning tuning(ChassisBlueprint plan, int x) {
        return ChassisBlueprint.bogeyTuning(plan, x).orElseThrow(() -> new AssertionError("no tuning at " + x));
    }

    private static long count(ChassisBlueprint plan, char symbol) {
        return plan.cells().stream().filter(cell -> cell.symbol() == symbol).count();
    }
}
