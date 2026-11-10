package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChassisSkeletonTest {
    @Test
    void fourOrientationsRecogniseTheSameBlueprint() {
        for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
            List<ChassisSkeleton.Match> matches = ChassisSkeleton.identify(
                    observations(ChassisBlueprint.POWER, quarterTurns, true));
            assertTrue(matches.contains(new ChassisSkeleton.Match(ChassisBlueprint.POWER, quarterTurns)),
                    "quarter turn " + quarterTurns + " must match, got " + matches);
            assertTrue(matches.contains(new ChassisSkeleton.Match(ChassisBlueprint.POWER, (quarterTurns + 2) % 4)),
                    "180-degree symmetric front/back must also match");
            assertTrue(matches.stream().allMatch(match -> match.blueprint() == ChassisBlueprint.POWER),
                    "no carriage match for a power skeleton");
        }
    }

    @Test
    void superstructureAndDecorationAreIgnored() {
        List<ChassisSkeleton.Match> matches = ChassisSkeleton.identify(
                observations(ChassisBlueprint.CARRIAGE, 1, true));
        assertTrue(matches.contains(new ChassisSkeleton.Match(ChassisBlueprint.CARRIAGE, 1)));
        assertTrue(matches.stream().allMatch(match -> match.blueprint() == ChassisBlueprint.CARRIAGE));
    }

    @Test
    void powerAndCarriageBogeyLayoutsAreDistinguished() {
        List<ChassisSkeleton.Match> power = ChassisSkeleton.identify(
                observations(ChassisBlueprint.POWER, 0, false));
        assertTrue(power.stream().allMatch(match -> match.blueprint() == ChassisBlueprint.POWER));
        assertFalse(power.stream().anyMatch(match -> match.blueprint() == ChassisBlueprint.CARRIAGE));

        List<ChassisSkeleton.Match> carriage = ChassisSkeleton.identify(
                observations(ChassisBlueprint.CARRIAGE, 0, false));
        assertTrue(carriage.stream().allMatch(match -> match.blueprint() == ChassisBlueprint.CARRIAGE));
        assertFalse(carriage.stream().anyMatch(match -> match.blueprint() == ChassisBlueprint.POWER));
    }

    @Test
    void missingRequiredSkeletonCellRejects() {
        List<ChassisSkeleton.Observation> observed = new ArrayList<>(
                observations(ChassisBlueprint.POWER, 0, false));
        observed.remove(0);
        assertTrue(ChassisSkeleton.identify(observed).isEmpty());
    }

    @Test
    void extraOrRetypedBogeyRejects() {
        List<ChassisSkeleton.Observation> extra = new ArrayList<>(
                observations(ChassisBlueprint.POWER, 0, false));
        extra.add(new ChassisSkeleton.Observation(0, 1, 'W'));
        assertTrue(ChassisSkeleton.identify(extra).isEmpty());

        List<ChassisSkeleton.Observation> retyped = new ArrayList<>();
        for (ChassisSkeleton.Observation observation : observations(ChassisBlueprint.POWER, 0, false)) {
            retyped.add(observation.symbol() == 'I'
                    ? new ChassisSkeleton.Observation(observation.x(), observation.z(), 'W')
                    : observation);
        }
        assertTrue(ChassisSkeleton.identify(retyped).isEmpty());
    }

    @Test
    void emptyObservationRejects() {
        assertTrue(ChassisSkeleton.identify(List.of()).isEmpty());
        assertTrue(ChassisSkeleton.identify(null).isEmpty());
    }

    @Test
    void requiredCellsOnlyContainTheSkeletonSubset() {
        List<ChassisBlueprint.Cell> cells = ChassisSkeleton.requiredCells(ChassisBlueprint.POWER);
        assertTrue(cells.stream().allMatch(cell -> ChassisSkeleton.REQUIRED_SYMBOLS.contains(cell.symbol())));
        assertTrue(cells.stream().noneMatch(cell -> cell.symbol() == 'C' || cell.symbol() == 'S'
                || cell.symbol() == 'N' || cell.symbol() == '.'));
    }

    @Test
    void rotationIsAQuarterTurn() {
        assertEquals(new ChassisSkeleton.Point(0, 1), ChassisSkeleton.rotate(1, 0, 1));
        assertEquals(new ChassisSkeleton.Point(-1, 0), ChassisSkeleton.rotate(1, 0, 2));
        assertEquals(new ChassisSkeleton.Point(0, -1), ChassisSkeleton.rotate(1, 0, 3));
        assertEquals(new ChassisSkeleton.Point(1, 0), ChassisSkeleton.rotate(1, 0, 4));
    }

    private static List<ChassisSkeleton.Observation> observations(ChassisBlueprint blueprint,
                                                                  int quarterTurns, boolean superstructure) {
        List<ChassisSkeleton.Observation> observed = new ArrayList<>();
        for (ChassisBlueprint.Cell cell : ChassisSkeleton.requiredCells(blueprint)) {
            ChassisSkeleton.Point point = ChassisSkeleton.rotate(cell.x(), cell.z(), quarterTurns);
            observed.add(new ChassisSkeleton.Observation(point.x(), point.z(), cell.symbol()));
        }
        if (superstructure) {
            // 上装/装饰：不同符号叠在同一列，或落在骨架外；都不应影响必要骨架匹配。
            observed.add(new ChassisSkeleton.Observation(0, 0, 'N'));
            observed.add(new ChassisSkeleton.Observation(0, 0, 'S'));
            observed.add(new ChassisSkeleton.Observation(0, 10, 'P'));
        }
        return observed;
    }
}
