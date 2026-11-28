package dimblend.client.bandrift;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression cover for the P4 fog race and for the "no snap at 750 ms" requirement.
 *
 * <p>Frame and tick rates are the reported ones: fog is computed per render frame (up to 280 Hz)
 * while the lane identity arrives with the 20 Hz client tick, so a frame can see a band change
 * before the lane packet exists.</p>
 */
class BandFogTransitionTest {
    /** 280 Hz render frame. */
    private static final long FRAME_NANOS = 1_000_000_000L / 280L;
    /** 20 Hz client tick. */
    private static final long TICK_NANOS = 50_000_000L;
    private static final long T0 = 10_000_000_000L;

    private static final float FOREST_RED = 0.20F;
    private static final float FOREST_GREEN = 0.30F;
    private static final float FOREST_BLUE = 0.15F;
    private static final float SPOOKY_RED = 0.05F;
    private static final float SPOOKY_GREEN = 0.06F;
    private static final float SPOOKY_BLUE = 0.08F;

    /** One settled frame in the starting band. */
    private static void prime(BandFogTransitionState state) {
        state.observe(0, "forest", T0);
        state.stepColor(FOREST_RED, FOREST_GREEN, FOREST_BLUE, T0);
        state.stepSky(0.2F, 80.0F, T0);
        state.stepTerrain(0.1F, 120.0F, T0);
        state.settleIfConverged(T0);
    }

    @Test
    void frameThatSeesTheNewRegionFirstCannotSettleBeforeTheLanePacket() {
        BandFogTransitionState state = new BandFogTransitionState();
        prime(state);
        assertFalse(state.active(), "a stationary player must not arm the blend");

        // First frame after crossing: the camera already sits in the next region, the lane packet
        // is still one tick away, so the fog targets are still the old band's values.
        long frame = T0 + FRAME_NANOS;
        assertTrue(state.observe(1, "forest", frame), "the region change must arm the blend");
        state.stepColor(FOREST_RED, FOREST_GREEN, FOREST_BLUE, frame);
        state.stepSky(0.2F, 80.0F, frame);
        state.stepTerrain(0.1F, 120.0F, frame);
        state.settleIfConverged(frame);

        assertTrue(state.active(), "grace window must survive an already-converged early frame");
        assertFalse(state.terrainValid() && state.terrainNear() == 0.0F, "terrain plane stays valid");
    }

    @Test
    void laneArrivingOneTickLaterStillBlendsInsteadOfSnapping() {
        BandFogTransitionState state = new BandFogTransitionState();
        prime(state);

        long frame = T0 + FRAME_NANOS;
        state.observe(1, "forest", frame);
        state.stepColor(FOREST_RED, FOREST_GREEN, FOREST_BLUE, frame);
        state.stepSky(0.2F, 80.0F, frame);
        state.stepTerrain(0.1F, 120.0F, frame);

        // 20 Hz tick: the new lane target finally exists.
        long tick = frame + TICK_NANOS;
        assertTrue(state.observe(1, "spooky", tick), "the lane change arms the blend again");
        state.stepColor(SPOOKY_RED, SPOOKY_GREEN, SPOOKY_BLUE, tick);
        state.stepSky(0.2F, 80.0F, tick);
        state.stepTerrain(0.1F, 120.0F, tick);

        // A few frames later the colour must be strictly between the two bands: a snapped value
        // would already equal the spooky target.
        long t = tick;
        for (int i = 0; i < 8; i++) {
            t += FRAME_NANOS;
            state.stepColor(SPOOKY_RED, SPOOKY_GREEN, SPOOKY_BLUE, t);
            state.stepSky(0.2F, 80.0F, t);
            state.stepTerrain(0.1F, 120.0F, t);
            state.settleIfConverged(t);
        }
        assertTrue(state.outGreen() < FOREST_GREEN && state.outGreen() > SPOOKY_GREEN,
                "green mid-blend should sit strictly between the bands, was " + state.outGreen());
        assertTrue(state.active(), "8 frames is far inside the 250 ms grace window");
    }

    @Test
    void settlesOnlyAfterTheGraceWindow() {
        BandFogTransitionState state = new BandFogTransitionState();
        prime(state);

        long arm = T0 + FRAME_NANOS;
        state.observe(1, "forest", arm);
        state.stepColor(FOREST_RED, FOREST_GREEN, FOREST_BLUE, arm);
        state.stepSky(0.2F, 80.0F, arm);
        state.stepTerrain(0.1F, 120.0F, arm);

        state.settleIfConverged(arm + BandFogTransitionState.MIN_ARM_NANOS - 1L);
        assertTrue(state.active(), "just inside the grace window the blend must stay armed");

        state.settleIfConverged(arm + BandFogTransitionState.MIN_ARM_NANOS);
        assertFalse(state.active(), "converged and past the grace window: stop paying for the blend");
    }

    /**
     * The 750 ms tau is the visible half of the fog fix: after {@code FOG_TAU} the sky plane has
     * travelled {@code 1 - 1/e} of the way, which is neither the old nor the new distance.
     */
    @Test
    void fogPlaneAfter750msIsMidTransitionNotASnap() {
        BandFogTransitionState state = new BandFogTransitionState();
        state.observe(0, "forest", T0);
        state.stepSky(0.2F, 80.0F, T0);
        state.stepTerrain(0.1F, 120.0F, T0);
        state.settleIfConverged(T0);

        long arm = T0 + FRAME_NANOS;
        state.observe(1, "forest", arm);
        state.stepSky(0.2F, 80.0F, arm);

        // New band opens the sky plane wide; feed the new target for exactly one tau at 280 Hz.
        long elapsed = 0L;
        long now = arm;
        while (elapsed < 750_000_000L) {
            now += FRAME_NANOS;
            elapsed += FRAME_NANOS;
            state.stepSky(0.2F, 300.0F, now);
            state.stepTerrain(0.1F, 120.0F, now);
            state.settleIfConverged(now);
        }
        float expected = 80.0F + (300.0F - 80.0F) * (float) (1.0 - Math.exp(-1.0));
        assertTrue(Math.abs(state.skyFar() - expected) < 1.5F,
                "750 ms should land at 1 - 1/e of the gap (" + expected + "), was " + state.skyFar());
        assertTrue(state.skyFar() < 300.0F, "the sky plane must not snap to the new distance");
        assertTrue(state.active(), "750 ms is still inside the blend");
    }

    @Test
    void resetForgetsThePreviousDimension() {
        BandFogTransitionState state = new BandFogTransitionState();
        prime(state);
        state.reset();
        assertFalse(state.active());
        assertFalse(state.colorValid());
        assertFalse(state.skyValid());
        assertFalse(state.terrainValid());
        // The first observation after a reset is a fresh baseline, not a lane change.
        assertFalse(state.observe(3, "spooky", T0 + TICK_NANOS));
        assertFalse(state.active());
    }
}
