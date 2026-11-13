package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.chassis.ChassisRailLockRules.Axis;
import dimblend.experience.chassis.ChassisRailLockRules.Context;
import dimblend.experience.chassis.ChassisRailLockRules.Decision;
import dimblend.experience.chassis.ChassisRailLockRules.TrackState;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChassisRailLockRulesTest {
    @Test
    void lockedAxesKeepHorizontalPlaneAndYawFree() {
        assertEquals(3, ChassisRailLockRules.lockedAxes().size());
        assertTrue(ChassisRailLockRules.horizontalAndYawFree());
        assertTrue(ChassisRailLockRules.lockedAxes().containsAll(
                Set.of(Axis.LINEAR_Y, Axis.ANGULAR_X, Axis.ANGULAR_Z)));
        assertFalse(ChassisRailLockRules.lockedAxes().contains(Axis.LINEAR_X));
        assertFalse(ChassisRailLockRules.lockedAxes().contains(Axis.LINEAR_Z));
        assertFalse(ChassisRailLockRules.lockedAxes().contains(Axis.ANGULAR_Y));

        // Micro-roll capable rail locks keep LINEAR_Y and ANGULAR_Z hard-locked while leaving
        // ANGULAR_X to dynamic [0, 0] / [-2.5 deg, +2.5 deg] joint limits.
        assertEquals(Set.of(Axis.LINEAR_Y, Axis.ANGULAR_Z), ChassisRailLockRules.microRollLockedAxes());
        assertEquals(2.5, ChassisRailLockRules.MAX_MICRO_ROLL_DEGREES, 1.0E-9);
        assertEquals(Math.toRadians(2.5), ChassisRailLockRules.MAX_MICRO_ROLL_RADIANS, 1.0E-9);
    }

    @Test
    void nominalDeckMatchesRailEvidence() {
        assertEquals(66.0, ChassisRailLockRules.DEFAULT_NOMINAL_DECK_HEIGHT);
        assertEquals(66.0, ChassisRailLockRules.nominalDeckHeight(65));
        assertEquals(10.0, ChassisRailLockRules.nominalDeckHeight(9));
    }

    @Test
    void onlyPowerAndCarriageAreStandardBlueprints() {
        assertTrue(ChassisRailLockRules.standardBlueprint(ChassisBlueprint.POWER));
        assertTrue(ChassisRailLockRules.standardBlueprint(ChassisBlueprint.CARRIAGE));
        assertEquals(2, ChassisBlueprint.values().length);
        assertEquals(6, bogeyCount(ChassisBlueprint.POWER));
        assertEquals(2, bogeyCount(ChassisBlueprint.CARRIAGE));
    }

    @Test
    void stableCarIsCreatedOnlyAfterThreeChecks() {
        Context context = good();
        assertTrue(ChassisRailLockRules.createEligible(context));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 0));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 1));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(context, 2));
        assertEquals(Decision.CREATE, ChassisRailLockRules.decide(context, 3));
        assertEquals(Decision.CREATE, ChassisRailLockRules.decide(context, 4));
    }

    @Test
    void singleBogeyCurvesSwitchesSidingsAndArbitraryYawAreEligible() {
        // Unregistered / custom 1-bogey or multi-bogey cars on switches, curves, ZO sidings,
        // and any yaw angle are eligible as long as all enabled bogeys have track and chunks ready.
        Context singleBogey = new Builder().expectedBogeys(1).trackedBogeys(1).build();
        assertTrue(ChassisRailLockRules.createEligible(singleBogey));
        assertTrue(ChassisRailLockRules.holdEligible(singleBogey));
        assertEquals(Decision.HOLD, ChassisRailLockRules.verify(singleBogey));
    }

    @Test
    void createAndHoldRejectBaseIneligibility() {
        assertFalse(ChassisRailLockRules.createEligible(null));
        assertFalse(ChassisRailLockRules.holdEligible(null));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().rotatingDimension(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().loaded(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().bodyValid(false).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().expectedBogeys(0).trackedBogeys(0).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().trackedBogeys(5).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().derailed(true).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(false, true)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().track(new TrackState(true, false)).build()));
        assertEquals(Decision.RELEASE, ChassisRailLockRules.verify(
                new Builder().track(new TrackState(true, false)).build()));
        assertEquals(Decision.RELEASE, ChassisRailLockRules.verify(
                new Builder().derailed(true).build()));
    }

    @Test
    void createWindowIsInclusiveAtItsBoundaries() {
        assertTrue(ChassisRailLockRules.createEligible(new Builder().deckY(67.0).build()));
        assertTrue(ChassisRailLockRules.createEligible(new Builder().deckY(65.0).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().deckY(Math.nextUp(67.0)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().deckY(Math.nextDown(65.0)).build()));

        assertTrue(ChassisRailLockRules.createEligible(
                new Builder().pitchDegrees(10.0).rollDegrees(-10.0).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().pitchDegrees(Math.nextUp(10.0)).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().rollDegrees(-Math.nextUp(10.0)).build()));
    }

    @Test
    void holdWindowUsesOnePointFiveVerticalAndFifteenDegreeLimits() {
        assertTrue(ChassisRailLockRules.holdEligible(new Builder().deckY(67.5).build()));
        assertTrue(ChassisRailLockRules.holdEligible(new Builder().deckY(64.5).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().deckY(Math.nextUp(67.5)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().deckY(Math.nextDown(64.5)).build()));

        assertTrue(ChassisRailLockRules.holdEligible(
                new Builder().pitchDegrees(-15.0).rollDegrees(15.0).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().pitchDegrees(Math.nextUp(15.0)).build()));
        assertFalse(ChassisRailLockRules.holdEligible(
                new Builder().rollDegrees(-Math.nextUp(15.0)).build()));
        assertEquals(Decision.HOLD, ChassisRailLockRules.verify(good()));
    }

    @Test
    void nonFiniteFactsAndMissingTrackStateFailClosed() {
        assertFalse(ChassisRailLockRules.createEligible(new Builder().deckY(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().nominalY(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.createEligible(
                new Builder().pitchDegrees(Double.NEGATIVE_INFINITY).build()));
        assertFalse(ChassisRailLockRules.createEligible(new Builder().rollDegrees(Double.NaN).build()));
        assertFalse(ChassisRailLockRules.holdEligible(new Builder().deckY(Double.NaN).build()));

        Context missingTrack = new Builder().track(null).build();
        assertFalse(missingTrack.baseEligible());
        assertFalse(ChassisRailLockRules.createEligible(missingTrack));
        assertFalse(ChassisRailLockRules.holdEligible(missingTrack));
        assertEquals(Decision.WAIT, ChassisRailLockRules.decide(missingTrack, 99));
    }

    @Test
    void frameRefreshChecksThirtyTwoBlockDriftAndFifteenDegreeNormalizedYaw() {
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(180.0));
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(-180.0));
        assertEquals(180.0, ChassisRailLockRules.normalizeDegrees(540.0));
        assertEquals(10.0, ChassisRailLockRules.normalizeDegrees(-350.0), 1.0E-9);

        // Horizontal drift boundary: 32.0 blocks inclusive does not refresh; > 32.0 refreshes.
        assertFalse(ChassisRailLockRules.needsFrameRefresh(32.0, 0.0, 0.0, 0.0));
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, -32.0, 45.0, 45.0));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(Math.nextUp(32.0), 0.0, 0.0, 0.0));
        assertFalse(ChassisRailLockRules.needsFrameRefresh(22.0, 22.0, 0.0, 0.0));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(23.0, 23.0, 0.0, 0.0));

        // Yaw delta boundary: 15.0 degrees inclusive does not refresh; > 15.0 refreshes.
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, 15.0, 0.0));
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, -15.0, 0.0));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, Math.nextUp(15.0), 0.0));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, -Math.nextUp(15.0), 0.0));

        // +/-180 degree seam wraparound.
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, -175.0, 175.0));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, -170.0, 170.0));

        // Non-finite inputs fail closed without triggering setFrame1 with NaN.
        assertFalse(ChassisRailLockRules.needsFrameRefresh(Double.NaN, 0.0, 0.0, 0.0));
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, Double.NaN, 0.0));

        // When micro-roll is open, world-frame yaw refreshes past 3.0 deg to prevent curve cross-coupling.
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, 3.0, 0.0, true));
        assertTrue(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, Math.nextUp(3.0), 0.0, true));
        assertFalse(ChassisRailLockRules.needsFrameRefresh(0.0, 0.0, 10.0, 0.0, false));
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
        boolean rotatingDimension = true;
        boolean loaded = true;
        boolean bodyValid = true;
        int expectedBogeys = 6;
        int trackedBogeys = 6;
        boolean derailed;
        TrackState track = new TrackState(true, true);
        double deckY = 66.0;
        double nominalY = 66.0;
        double pitchDegrees;
        double rollDegrees;

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

        Builder pitchDegrees(double value) {
            pitchDegrees = value;
            return this;
        }

        Builder rollDegrees(double value) {
            rollDegrees = value;
            return this;
        }

        Context build() {
            return new Context(
                    rotatingDimension,
                    loaded,
                    bodyValid,
                    expectedBogeys,
                    trackedBogeys,
                    derailed,
                    track,
                    deckY,
                    nominalY,
                    pitchDegrees,
                    rollDegrees);
        }
    }
}
