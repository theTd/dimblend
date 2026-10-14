package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Coupler topology, attendance distance, and the emergency-brake signal-change reset. */
class TrainDeadmanMathTest {

    private static final double RADIUS_SQ = 32.0D * 32.0D;

    private static List<TrainDeadmanMath.Probe> probes(double... xyzSpeed) {
        java.util.ArrayList<TrainDeadmanMath.Probe> list = new java.util.ArrayList<>();
        for (int i = 0; i < xyzSpeed.length; i += 4) {
            list.add(new TrainDeadmanMath.Probe(xyzSpeed[i], xyzSpeed[i + 1], xyzSpeed[i + 2], xyzSpeed[i + 3]));
        }
        return list;
    }

    private static java.util.UUID id(int n) {
        return new java.util.UUID(0, n);
    }

    private static TrainDeadmanMath.Carriage carriage(int id, List<Integer> partners,
                                                       double... xyzSpeed) {
        return new TrainDeadmanMath.Carriage(id(id), partners.stream().map(TrainDeadmanMathTest::id).toList(),
                probes(xyzSpeed));
    }

    @Test
    void coupledLongTrainIsOneConsistRegardlessOfBogieGaps() {
        var groups = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(2), 0, 64, 0, 10),
                carriage(2, List.of(3), 40, 64, 0, 3),
                carriage(3, List.of(), 80, 64, 0, 0)));
        assertEquals(1, groups.size());
        var train = groups.getFirst();
        assertEquals(3, train.bogeys().size());
        assertTrue(TrainDeadmanMath.hasFast(train.bogeys()));
        assertEquals(1, TrainDeadmanMath.nearestDistanceSq(train.bogeys(), 81, 64, 0));
    }

    @Test
    void parallelParkedTrainCannotAttendDepartingTrain() {
        // Positions alone used to merge these into one train and mask the runaway.
        var groups = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(), 0, 64, 0, 0),
                carriage(2, List.of(3), 20, 64, 0, 10),
                carriage(3, List.of(), 40, 64, 0, 10)));
        assertEquals(2, groups.size());
        var moving = groups.stream().filter(consist -> TrainDeadmanMath.hasFast(consist.bogeys())).findFirst().orElseThrow();
        assertTrue(TrainDeadmanMath.nearestDistanceSq(moving.bogeys(), -20, 64, 0) > RADIUS_SQ);
    }

    @Test
    void sameCarriageBogeysStayTogetherWithoutCouplers() {
        var groups = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(), 0, 64, 0, 10, 50, 64, 0, 3.9)));
        assertEquals(1, groups.size());
        assertTrue(TrainDeadmanMath.hasFast(groups.getFirst().bogeys()));
        assertEquals(25, TrainDeadmanMath.nearestDistanceSq(groups.getFirst().bogeys(), 55, 64, 0));
    }

    @Test
    void carriageWithoutBogeysStillConnectsBothEnds() {
        var groups = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(2), 0, 64, 0, 10),
                carriage(2, List.of(3)),
                carriage(3, List.of(), 100, 64, 0, 10)));
        assertEquals(1, groups.size());
        assertEquals(2, groups.getFirst().bogeys().size());
        var withoutBridge = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(2), 0, 64, 0, 10),
                carriage(3, List.of(), 100, 64, 0, 10)));
        assertEquals(2, withoutBridge.size());
        assertTrue(withoutBridge.stream().allMatch(group -> group.bogeys().size() == 1));
    }

    @Test
    void missingPartnerAndUncoupledCarriageStaySeparate() {
        var groups = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(99), 0, 64, 0, 10),
                carriage(2, List.of(), 1, 64, 0, 10)));
        assertEquals(2, groups.size());
    }

    @Test
    void radiusBoundaryAndVerticalDistanceAreMeasuredInWorldSpace() {
        var train = probes(1000, 64, -1000, 10);
        assertEquals(RADIUS_SQ, TrainDeadmanMath.nearestDistanceSq(train, 1032, 64, -1000));
        assertTrue(TrainDeadmanMath.nearestDistanceSq(train, 1032.01, 64, -1000) > RADIUS_SQ);
        assertTrue(TrainDeadmanMath.nearestDistanceSq(train, 1000, 97, -1000) > RADIUS_SQ);
    }

    @Test
    void stationaryAndEmptyConsistsCannotTriggerBrake() {
        assertFalse(TrainDeadmanMath.hasFast(probes(0, 64, 0, 4.0)));
        assertFalse(TrainDeadmanMath.hasFast(List.of()));
        assertEquals(List.of(), TrainDeadmanMath.cluster(List.of()));
    }

    @Test
    void anyRawSignalLevelChangeCountsButFirstObservationDoesNot() {
        for (int current = 0; current <= 15; current++) {
            assertFalse(TrainDeadmanMath.signalChanged(null, current));
            for (int previous = 0; previous <= 15; previous++) {
                assertEquals(previous != current, TrainDeadmanMath.signalChanged(previous, current));
            }
        }
    }

    @Test
    void anyBogieSignalChangeReleasesAllConnectedCarriagesEvenIfStillUnattended() {
        var consists = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(2), 0, 64, 0, 10),
                carriage(2, List.of(3)),
                carriage(3, List.of(), 80, 64, 0, 10)));
        for (int changedCarriage = 1; changedCarriage <= 3; changedCarriage++) {
            var next = TrainDeadmanMath.updateLatches(Set.of(id(1), id(2), id(3)), consists,
                    Set.of(id(changedCarriage)), group -> true);
            assertEquals(Set.of(), next);
        }
    }

    @Test
    void releasingOneTrainDoesNotReleaseAnotherTrain() {
        var consists = TrainDeadmanMath.cluster(List.of(
                carriage(1, List.of(2)), carriage(2, List.of()),
                carriage(3, List.of(4)), carriage(4, List.of())));
        var next = TrainDeadmanMath.updateLatches(Set.of(id(1), id(2), id(3), id(4)), consists,
                Set.of(id(2)), group -> false);
        assertEquals(Set.of(id(3), id(4)), next);
    }

    @Test
    void signalOnUnlatchedTrainCannotReleaseLatchedNeighbor() {
        var consists = TrainDeadmanMath.cluster(List.of(carriage(1, List.of()), carriage(2, List.of())));
        assertEquals(Set.of(id(2)), TrainDeadmanMath.updateLatches(Set.of(id(2)), consists,
                Set.of(id(1)), group -> false));
    }

    @Test
    void signalChangeAlsoPreventsNewLatchForThatUpdateWhileAnotherTrainCanLatch() {
        var consists = TrainDeadmanMath.cluster(List.of(carriage(1, List.of(2)), carriage(2, List.of()),
                carriage(3, List.of())));
        assertEquals(Set.of(id(3)), TrainDeadmanMath.updateLatches(Set.of(), consists,
                Set.of(id(2)), group -> true));
    }

    @Test
    void latchFollowsNewConnectionsAndResetUsesCurrentConsist() {
        var joined = TrainDeadmanMath.cluster(List.of(carriage(1, List.of(2)), carriage(2, List.of())));
        assertEquals(Set.of(id(1), id(2)), TrainDeadmanMath.updateLatches(Set.of(id(1)), joined,
                Set.of(), group -> false));
        assertEquals(Set.of(), TrainDeadmanMath.updateLatches(Set.of(id(1)), joined,
                Set.of(id(2)), group -> false));
    }

    @Test
    void afterUncouplingOnlyTheChangedConsistReleases() {
        var split = TrainDeadmanMath.cluster(List.of(carriage(1, List.of()), carriage(2, List.of())));
        assertEquals(Set.of(id(2)), TrainDeadmanMath.updateLatches(Set.of(id(1), id(2)), split,
                Set.of(id(1)), group -> false));
    }

    @Test
    void unattendedDetectionLatchesOnlyTheOffendingConsistAndRetainsExistingLatch() {
        var consists = TrainDeadmanMath.cluster(List.of(carriage(1, List.of(2)), carriage(2, List.of()),
                carriage(3, List.of()), carriage(4, List.of())));
        var next = TrainDeadmanMath.updateLatches(Set.of(id(3)), consists, Set.of(),
                group -> group.carriageIds().contains(id(1)));
        assertEquals(Set.of(id(1), id(2), id(3)), next);
    }

    @Test
    void temporarilyMissingCarriageRetainsLatchUntilItReturns() {
        assertEquals(Set.of(id(1)), TrainDeadmanMath.updateLatches(Set.of(id(1)), List.of(),
                Set.of(), group -> false));
    }
}
