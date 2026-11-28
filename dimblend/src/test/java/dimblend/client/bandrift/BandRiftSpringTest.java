package dimblend.client.bandrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Curve and state contracts of the band-rift spring. Everything here is pure math, so the tests
 * pin the numbers the shader sees rather than any Minecraft behaviour.
 */
class BandRiftSpringTest {
    private static final double STEP = 1.0 / 60.0;

    private static void run(BandRiftSpring spring, double distance, double speed, double seconds) {
        int steps = Math.max(1, (int) Math.round(seconds / STEP));
        for (int i = 0; i < steps; i++) {
            spring.advance(distance, speed, STEP);
        }
    }

    @Test
    void farDistanceClosesTheMembrane() {
        assertEquals(0.0, BandRiftSpring.target(44.0, 16.0, false), 1.0E-9);
        assertEquals(0.0, BandRiftSpring.target(200.0, 16.0, false), 1.0E-9);

        BandRiftSpring spring = new BandRiftSpring();
        run(spring, 60.0, 0.0, 2.0);
        assertTrue(spring.openFactor() < 0.01, "open at 60 blocks: " + spring.openFactor());
        assertFalse(spring.latched());
    }

    @Test
    void midDistanceHoldsThePreviewOpening() {
        double openNear = BandRiftSpring.openNear(0.0);
        double expected = 0.45 * BandRiftSpring.smoothstep(
                BandRiftSpring.FAR_DISTANCE, openNear, 25.0);
        assertEquals(expected, BandRiftSpring.target(25.0, openNear, false), 1.0E-9);
        assertTrue(expected > 0.0 && expected < 0.45, "the preview is a partial crack, was " + expected);

        BandRiftSpring spring = new BandRiftSpring();
        run(spring, 25.0, 0.0, 3.0);
        assertEquals(expected, spring.openFactor(), 0.01);
        assertFalse(spring.latched(), "25 blocks is outside the close-range latch (16 + 4.5)");
    }

    @Test
    void closeRangeLatchesFullyOpenAndReleasesPastTheHysteresis() {
        BandRiftSpring spring = new BandRiftSpring();
        run(spring, 10.0, 0.0, 1.5);
        assertTrue(spring.latched());
        assertEquals(1.0, spring.openFactor(), 0.01);

        // Still latched inside the release margin.
        spring.advance(15.0, 0.0, STEP);
        assertTrue(spring.latched(), "release only past openNear + 4.5");

        spring.advance(21.0, 0.0, STEP);
        assertFalse(spring.latched());

        run(spring, 21.0, 0.0, 2.0);
        double preview = 0.45 * BandRiftSpring.smoothstep(
                BandRiftSpring.FAR_DISTANCE, BandRiftSpring.openNear(0.0), 21.0);
        assertEquals(preview, spring.openFactor(), 0.02);
    }

    @Test
    void closingSpeedTearsTheMembraneEarly() {
        assertEquals(16.0, BandRiftSpring.openNear(0.0), 1.0E-9);
        assertEquals(16.0 + 1.35 * 8.0, BandRiftSpring.openNear(8.0), 1.0E-9);
        // The lead cap sits at 22, so 16 + 22 = 38 still leaves 6 blocks of closed curtain.
        assertEquals(38.0, BandRiftSpring.openNear(20.0), 1.0E-9);
        assertEquals(38.0, BandRiftSpring.openNear(500.0), 1.0E-9,
                "the lead is capped so openNear can never reach FAR_DISTANCE");
        assertTrue(BandRiftSpring.openNear(500.0) < BandRiftSpring.FAR_DISTANCE);

        BandRiftSpring standing = new BandRiftSpring();
        standing.advance(30.0, 0.0, STEP);
        assertFalse(standing.latched(), "30 blocks away at zero speed is only the preview");

        BandRiftSpring train = new BandRiftSpring();
        train.advance(30.0, 20.0, STEP);
        assertTrue(train.latched(), "a 20 m/s train starts tearing 38 blocks out");
        run(train, 30.0, 20.0, 1.0);
        assertTrue(train.openFactor() > 0.9);
    }

    @Test
    void solidScaleRampsBetweenItsEdges() {
        assertEquals(0.0, BandRiftSpring.solidScale(0.0), 1.0E-9);
        assertEquals(0.0, BandRiftSpring.solidScale(0.42), 1.0E-9);
        assertEquals(1.0, BandRiftSpring.solidScale(0.98), 1.0E-9);
        assertEquals(1.0, BandRiftSpring.solidScale(1.0), 1.0E-9);
        double middle = BandRiftSpring.solidScale(0.70);
        assertTrue(middle > 0.0 && middle < 1.0, "0.70 -> " + middle);
        assertTrue(BandRiftSpring.solidScale(0.60) < BandRiftSpring.solidScale(0.80));
    }

    @Test
    void glitchDensityPeaksBeforeTheCoreTearsThenIsSuppressed() {
        assertEquals(0.0, BandRiftSpring.glitchDensity(0.0), 1.0E-9);
        double early = BandRiftSpring.glitchDensity(0.20);
        double peak = BandRiftSpring.glitchDensity(0.45);
        double late = BandRiftSpring.glitchDensity(0.95);
        assertTrue(peak <= BandRiftSpring.GLITCH_PEAK + 1.0E-9, "peak " + peak);
        assertTrue(peak > early, "holes appear before the core opens");
        assertTrue(peak > late, "the torn core suppresses the random holes");
        assertTrue(late >= 0.0);
    }

    @Test
    void carriageFadeRemovesTheMembraneBeforeItCanCutTheCabin() {
        assertEquals(0.0, BandRiftSpring.carriageFade(0.5, true, 40.0), 1.0E-9, "box straddles the plane");
        assertEquals(0.0, BandRiftSpring.carriageFade(0.0, false, 40.0), 1.0E-9, "camera inside the plane");
        assertEquals(1.0, BandRiftSpring.carriageFade(3.0, false, 40.0), 1.0E-9);
        assertEquals(0.0, BandRiftSpring.carriageFade(5.0, false, 0.4), 1.0E-9, "box almost touching");

        double cameraFade = BandRiftSpring.carriageFade(1.7, false, 40.0);
        assertTrue(cameraFade > 0.0 && cameraFade < 1.0, "camera fade " + cameraFade);

        double carriageFade = BandRiftSpring.carriageFade(5.0, false, 1.5);
        assertTrue(carriageFade > 0.0 && carriageFade < 1.0, "carriage fade " + carriageFade);

        assertNotEquals(0.0, BandRiftSpring.carriageFade(Double.POSITIVE_INFINITY, false, Double.POSITIVE_INFINITY));
    }

    @Test
    void burstFiresOncePerPassAndRearmsAfterSixBlocks() {
        BandRiftSpring spring = new BandRiftSpring();
        assertFalse(spring.updateBurst(0.0, 100.0, STEP), "first sample only records the camera");
        assertTrue(spring.burstArmed(), "0 vs 100 is far outside the re-arm window");

        assertFalse(spring.updateBurst(99.0, 100.0, STEP));
        assertTrue(spring.updateBurst(101.0, 100.0, STEP), "crossing the plane fires");
        assertTrue(spring.burstIntensity() > 0.99);
        assertTrue(spring.flashIntensity() > 0.99);
        assertFalse(spring.burstArmed());

        assertFalse(spring.updateBurst(103.0, 100.0, STEP), "same pass never re-fires");
        assertTrue(spring.burstIntensity() < 1.0);

        for (int i = 0; i < 120; i++) {
            spring.updateBurst(103.0, 100.0, STEP);
        }
        assertTrue(spring.burstIntensity() < 0.001, "burst decays in ~0.25 s, got " + spring.burstIntensity());
        assertTrue(spring.flashIntensity() < 0.001);

        // 3 blocks away does not re-arm; a full 10 away does.
        assertFalse(spring.updateBurst(97.0, 100.0, STEP));
        assertFalse(spring.burstArmed());
        spring.updateBurst(90.0, 100.0, STEP);
        assertTrue(spring.burstArmed());
        assertTrue(spring.updateBurst(101.0, 100.0, STEP), "second pass after re-arming fires again");
    }

    @Test
    void fogBlendConvergesOnTheTargetAndIgnoresBrokenTimeSteps() {
        double value = 0.0;
        for (int i = 0; i < 80; i++) {
            value = BandRiftSpring.fogBlendStep(value, 1.0, 1.0 / 20.0);
        }
        assertEquals(1.0, value, 0.01, "4 s is over five time constants");

        assertEquals(0.25, BandRiftSpring.fogBlendStep(0.25, 1.0, 0.0), 1.0E-9);
        assertEquals(0.5, BandRiftSpring.fogBlendStep(Double.NaN, 0.5, 0.1), 1.0E-9);
        assertEquals(0.5, BandRiftSpring.fogBlendStep(0.5, Double.NaN, 0.1), 1.0E-9);
    }

    @Test
    void brokenTimeStepsCannotInjectEnergy() {
        assertEquals(0.0, BandRiftSpring.sanitizeDt(Double.NaN), 1.0E-12);
        assertEquals(0.0, BandRiftSpring.sanitizeDt(-1.0), 1.0E-12);
        assertEquals(0.0, BandRiftSpring.sanitizeDt(Double.POSITIVE_INFINITY), 1.0E-12);
        assertEquals(0.25, BandRiftSpring.sanitizeDt(30.0), 1.0E-12);

        BandRiftSpring spring = new BandRiftSpring();
        double before = spring.advance(10.0, 0.0, Double.NaN);
        assertEquals(0.0, before, 1.0E-12, "a NaN frame leaves the opening untouched");
        assertTrue(spring.latched(), "the latch is a distance decision, not a time one");

        run(spring, 10.0, 0.0, 3.0);
        assertEquals(1.0, spring.openFactor(), 0.01, "no overshoot past full open");
        assertEquals(0.0, spring.velocity(), 1.0E-6, "clamping at 1 kills the velocity");
    }
}
