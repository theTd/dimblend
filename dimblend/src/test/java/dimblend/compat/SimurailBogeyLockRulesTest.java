package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimurailBogeyLockRulesTest {
    /** Vanilla's release sentinel: {@code (double) Float.MAX_VALUE}. */
    private static final double VANILLA_MAX = 3.4028234663852886E38;

    @Test
    void settledAxleIsPinnedToTheRail() {
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -0.0, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -0.0, 0.2));
    }

    @Test
    void settledAxleStaysPinnedWhenVanillaReleases() {
        // Lateral/crest overspeed release and allowVerticalMovement free lift all widen the bound.
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -VANILLA_MAX, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(true, -VANILLA_MAX, -0.1));
    }

    @Test
    void settlingAxleKeepsVanillaSqueeze() {
        assertEquals(0.25, SimurailBogeyLockRules.captiveHalfWidth(false, -0.25, 0.3));
        assertEquals(0.25, SimurailBogeyLockRules.captiveHalfWidth(false, -0.25, -0.3));
    }

    @Test
    void releasedSettlingAxleIsHeldWhereItIs() {
        assertEquals(0.3, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, 0.3));
        assertEquals(0.3, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, -0.3));
    }

    @Test
    void captiveLimitNeverExceedsCurrentOffset() {
        assertEquals(0.1, SimurailBogeyLockRules.captiveHalfWidth(false, -0.4, 0.1));
    }

    @Test
    void degenerateInputsCollapseToPinned() {
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(false, 0.0, 0.0));
        assertEquals(0.0, SimurailBogeyLockRules.captiveHalfWidth(false, -VANILLA_MAX, Double.NaN));
        assertTrue(SimurailBogeyLockRules.captiveHalfWidth(false, 0.1, 0.2) == 0.0,
                "a positive lower bound is never a valid squeeze");
    }

    @Test
    void stretchedAxleBeyondVanillaToleranceStaysOnItsRail() {
        // Vanilla drops the track past 0.5 sideways or 0.375 upwards.
        assertTrue(SimurailBogeyLockRules.withinCaptiveReach(0.8, 0.0));
        assertTrue(SimurailBogeyLockRules.withinCaptiveReach(-0.8, 0.6));
        assertTrue(SimurailBogeyLockRules.withinCaptiveReach(0.0, -1.9));
    }

    @Test
    void captiveReachIsInclusiveAndSymmetric() {
        double reach = SimurailBogeyLockRules.CAPTIVE_REACH;
        assertTrue(SimurailBogeyLockRules.withinCaptiveReach(reach, -reach));
        assertTrue(SimurailBogeyLockRules.withinCaptiveReach(-reach, reach));
    }

    @Test
    void axleBeyondCaptiveReachIsLeftToDerail() {
        double beyond = Math.nextUp(SimurailBogeyLockRules.CAPTIVE_REACH);
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(beyond, 0.0));
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(0.0, -beyond));
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(40.0, 40.0));
    }

    @Test
    void curvePiecesOverlapTheirNeighbours() {
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(0.0));
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(1.0));
        // Vanilla stops at 1/32 past either end; the lock keeps half a piece.
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(1.4));
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(-0.4));
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(1.0 + SimurailBogeyLockRules.CURVE_PIECE_OVERLAP));
        assertFalse(SimurailBogeyLockRules.withinCurvePieceSpan(1.6));
        assertFalse(SimurailBogeyLockRules.withinCurvePieceSpan(-0.6));
        assertFalse(SimurailBogeyLockRules.withinCurvePieceSpan(Double.NaN));
    }

    @Test
    void leavingAPieceLandsInsideTheNextOne() {
        // Just past the overlap of piece i is well inside piece i+1 (shown for equal pieces;
        // with unequal ones it still is, since the next span starts half a piece early).
        double pastEnd = Math.nextUp(1.0 + SimurailBogeyLockRules.CURVE_PIECE_OVERLAP);
        assertFalse(SimurailBogeyLockRules.withinCurvePieceSpan(pastEnd));
        assertTrue(SimurailBogeyLockRules.withinCurvePieceSpan(pastEnd - 1.0));
    }

    @Test
    void brokenAxlePositionIsOutsideTheReach() {
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(Double.NaN, 0.0));
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(0.0, Double.NaN));
        assertFalse(SimurailBogeyLockRules.withinCaptiveReach(Double.POSITIVE_INFINITY, 0.0));
    }
}
