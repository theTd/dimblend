package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.chassis.ChassisRailLockRules.Axis;
import dimblend.experience.chassis.ChassisRailLockRules.Context;
import dimblend.experience.chassis.ChassisRailLockRules.Decision;
import dimblend.experience.chassis.ChassisRailLockRules.TrackState;
import org.junit.jupiter.api.Test;

class ChassisRailLockRulesTest {
    @Test
    void lockedAxesAreExactlyWorldXFree() {
        assertEquals(5, ChassisRailLockRules.lockedAxes().size());
        assertTrue(ChassisRailLockRules.onlyWorldXFree());
        assertFalse(ChassisRailLockRules.lockedAxes().contains(Axis.LINEAR_X));
        assertTrue(ChassisRailLockRules.lockedAxes().containsAll(java.util.Set.of(
                Axis.LINEAR_Y, Axis.LINEAR_Z, Axis.ANGULAR_X, Axis.ANGULAR_Y, Axis.ANGULAR_Z)));
    }

    @Test
    void nominalDeckMatchesRailEvidence() {
        // Rail block y=64, control/anchor block y=65, deck top y=66.
        assertEquals(66.0, ChassisRailLockRules.nominalDeckHeight(65));
        assertEquals(10.0, ChassisRailLockRules.nominalDeckHeight(9));
    }

    @Test
    void onlyPowerAndCarriageAreStandardBlueprints() {
        assertTrue(ChassisRailLockRules.standardBlueprint(ChassisBlueprint.POWER));
        assertTrue(ChassisRailLockRules.standardBlueprint(ChassisBlueprint.CARRIAGE));
        assertEquals(2, ChassisBlueprint.values().length);
    }

    @Test
    void standardBlueprintsCarrySixAndTwoBogeys() {
        assertEquals(6, bogeyCount(ChassisBlueprint.POWER));
        assertEquals(2, bogeyCount(ChassisBlueprint.CARRIAGE));
    }

    @Test
    void stableStandardCarIsCreatedOnlyAfterThreeChecks() {
        Context context = good();
        assertTrue(ChassisRailLockRules.createEligible(context));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 0));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 1));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 2));
        assertEquals(Decision.CREATE, ChassisRailLockRules.decide(context, 3));
        assertEquals(Decision.CREATE, ChassisRailLockRules.decide(context, 4));
    }

    @Test
    void createRejectsEveryNonStandardIdentityFlag() {
        assertFalse(ChassisRailLockRules.createEligible(new Builder().registered(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().standardBlueprint(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().rotatingDimension(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().loaded(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().bodyValid(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().standardBogeys(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().expectedBogeys(0).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().trackedBogeys(5).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().derailed(true).build()));
    }

    @Test
    void createNeedsAllLoadedBogeyTracksStraightX() {
        assertTrue(new TrackState(true, true, true, true).createReady());
        assertFalse(new TrackState(false, true, true, true).createReady());
        assertFalse(new TrackState(true, false, true, true).createReady());
        assertFalse(new TrackState(true, true, false, true).createReady());
        assertFalse(new TrackState(true, true, true, false).createReady());
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(false, false, true, true)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(true, false, true, true)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(true, true, false, true)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(true, true, true, false)).build()));
    }

    @Test
    void createWindowIsInclusiveAtItsBoundaries() {
        assertTrue(ChassisRailLockRules.createEligible(new Builder().deckY(67.0).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().deckY(Math.nextUp(67.0)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().deckY(Math.nextDown(65.0)).build()));
        assertTrue(ChassisRailLockRules.createEligible(
                new Builder().lateralError(0.6).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().lateralError(Math.nextUp(0.6)).build()));
        assertTrue(ChassisRailLockRules.createEligible(
                new Builder().yawErrorDegrees(10.0).pitchDegrees(10.0).rollDegrees(-10.0).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().yawErrorDegrees(Math.nextUp(10.0)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().pitchDegrees(Math.nextUp(10.0)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().rollDegrees(-Math.nextUp(10.0)).build()));
    }

    @Test
    void nonFiniteFactsFailClosed() {
        assertFalse(ChassisRailLockRules.createEligible(new Builder().deckY(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().nominalY(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().lateralError(Double.POSITIVE_INFINITY).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().yawErrorDegrees(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().pitchDegrees(Double.NEGATIVE_INFINITY).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().rollDegrees(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.holdEligible(new Builder().deckY(Double.NaN).build()));
    }

    @Test
    void missingTrackStateFailsClosed() {
        Context context = new Builder().track(null).build();
        assertFalse(context.baseEligible());
        assertFalse(ChassisRailLockRules.createEligible(context));
        assertFalse(ChassisRailLockRules.holdEligible(context));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 99));
    }

    @Test
    void holdToleratesUnloadedTrackButNotLoadedWrongTrack() {
        assertTrue(new TrackState(false, false, true, true).holdOk());
        assertTrue(new TrackState(true, true, true, true).holdOk());
        assertFalse(new TrackState(true, false, true, true).holdOk());
        assertFalse(new TrackState(true, true, true, false).holdOk());

        assertTrue(ChassisRailLockRules.holdEligible(
                new Builder().track(new TrackState(false, false, true, true)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().track(new TrackState(true, false, true, true)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().track(new TrackState(true, true, true, false)).build()));
    }

    @Test
    void holdWindowUsesItsOwnWiderLimits() {
        assertTrue(ChassisRailLockRules.holdEligible(new Builder().deckY(67.0).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().deckY(Math.nextUp(67.0)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().deckY(Math.nextDown(65.0)).build()));
        assertTrue(ChassisRailLockRules.holdEligible(
                new Builder().lateralError(0.75).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().lateralError(Math.nextUp(0.75)).build()));
        assertTrue(ChassisRailLockRules.holdEligible(
                new Builder().yawErrorDegrees(15.0).pitchDegrees(-15.0).rollDegrees(15.0).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().yawErrorDegrees(Math.nextUp(15.0)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().pitchDegrees(Math.nextUp(15.0)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().rollDegrees(-Math.nextUp(15.0)).build()));
        assertEquals(Decision.HOLD, ChassisRailLockRules.verify(good()));
        assertEquals(Decision.RELEASE, ChassisRailLockRules.verify(
                new Builder().derailed(true).build()));
        assertEquals(Decision.RELEASE, ChassisRailLockRules.verify(
                new Builder().track(new TrackState(true, false, true, true)).build()));
    }

    @Test
    void xAlignedYawTargetsOnlyZeroOrOneEighty() {
        assertEquals(0.0, ChassisRailLockRules.nearestXAlignedYaw(0.0));
        assertEquals(0.0, ChassisRailLockRules.nearestXAlignedYaw(5.0));
        assertEquals(0.0, ChassisRailLockRules.nearestXAlignedYaw(-5.0));
        assertEquals(0.0, ChassisRailLockRules.nearestXAlignedYaw(90.0));
        assertEquals(180.0, ChassisRailLockRules.nearestXAlignedYaw(91.0));
        assertEquals(180.0, ChassisRailLockRules.nearestXAlignedYaw(179.0));
        assertEquals(180.0, ChassisRailLockRules.nearestXAlignedYaw(-179.0));
        assertEquals(180.0, ChassisRailLockRules.nearestXAlignedYaw(180.0));

        assertEquals(-1.0, ChassisRailLockRules.yawErrorDegrees(179.0));
        assertEquals(1.0, ChassisRailLockRules.yawErrorDegrees(-179.0));
        assertEquals(90.0, ChassisRailLockRules.yawErrorDegrees(90.0));
        assertEquals(-90.0, ChassisRailLockRules.yawErrorDegrees(-90.0));
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(180.0));
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(-180.0));
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(540.0));

        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().yawErrorDegrees(ChassisRailLockRules.yawErrorDegrees(45.0)).build()));
    }

    private static Context good() {
        return new Builder().build();
    }

    private static int bogeyCount(ChassisBlueprint blueprint) {
        return (int) blueprint.cells().stream()
                .filter(cell -> cell.symbol() == 'W' || cell.symbol() == 'I')
                .count();
    }

    private static final class Builder {
        boolean registered = true;
        boolean standardBlueprint = true;
        boolean rotatingDimension = true;
        boolean loaded = true;
        boolean bodyValid = true;
        boolean standardBogeys = true;
        int expectedBogeys = 6;
        int trackedBogeys = 6;
        boolean derailed;
        TrackState track = new TrackState(true, true, true, true);
        double deckY = 66.0;
        double nominalY = 66.0;
        double lateralError;
        double yawErrorDegrees;
        double pitchDegrees;
        double rollDegrees;

        Builder registered(boolean value) {
            registered = value;
            return this;
        }

        Builder standardBlueprint(boolean value) {
            standardBlueprint = value;
            return this;
        }

        Builder rotatingDimension(boolean value) {
            rotatingDimension = value;
            return this;
        }

        Builder loaded(boolean value) {
            loaded = value;
            return this;
        }

        Builder bodyValid(boolean value) {
            bodyValid = value;
            return this;
        }

        Builder standardBogeys(boolean value) {
            standardBogeys = value;
            return this;
        }

        Builder expectedBogeys(int value) {
            expectedBogeys = value;
            return this;
        }

        Builder trackedBogeys(int value) {
            trackedBogeys = value;
            return this;
        }

        Builder derailed(boolean value) {
            derailed = value;
            return this;
        }

        Builder track(TrackState value) {
            track = value;
            return this;
        }

        Builder deckY(double value) {
            deckY = value;
            return this;
        }

        Builder nominalY(double value) {
            nominalY = value;
            return this;
        }

        Builder lateralError(double value) {
            lateralError = value;
            return this;
        }

        Builder yawErrorDegrees(double value) {
            yawErrorDegrees = value;
            return this;
        }

        Builder pitchDegrees(double value) {
            pitchDegrees = value;
            return this;
        }

        Builder rollDegrees(double value) {
            rollDegrees = value;
            return this;
        }

        Context build() {
            return new Context(registered, standardBlueprint, rotatingDimension, loaded, bodyValid,
                    standardBogeys, expectedBogeys, trackedBogeys, derailed, track, deckY, nominalY,
                    lateralError, yawErrorDegrees, pitchDegrees, rollDegrees);
        }
    }
}
