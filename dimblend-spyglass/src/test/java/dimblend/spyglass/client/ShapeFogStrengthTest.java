package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import org.junit.jupiter.api.Test;

/**
 * The share of real fog each mesh receives: the near share for overlay meshes, and the far-pass ramp
 * from the fog end to 1024 blocks. Invalid inputs must stay within the two configured shares and
 * never produce NaN.
 */
class ShapeFogStrengthTest {
    @Test
    void overlayUsesTheNearShareAndClampsIt() {
        assertEquals(0.5, ShapeFogStrength.overlay(0.5));
        assertEquals(0.0, ShapeFogStrength.overlay(0.0));
        assertEquals(1.0, ShapeFogStrength.overlay(1.0));
        assertEquals(0.0, ShapeFogStrength.overlay(-0.25));
        assertEquals(1.0, ShapeFogStrength.overlay(3.0));
        assertEquals(0.0, ShapeFogStrength.overlay(Double.NaN));
    }

    @Test
    void farReachesTheNearShareAtTheFogEndAndTheFarShareAtTheRevealLimit() {
        double near = 0.25;
        double far = 0.75;
        double fogEnd = 400.0;

        assertEquals(near, ShapeFogStrength.far(near, far, fogEnd, 0.0));
        assertEquals(near, ShapeFogStrength.far(near, far, fogEnd, fogEnd));
        // Halfway between the fog end and the reveal limit is exactly halfway between the shares.
        assertEquals(0.5, ShapeFogStrength.far(near, far, fogEnd, 712.0), 1.0E-12);
        assertEquals(far, ShapeFogStrength.far(near, far, fogEnd, 1024.0));
        assertEquals(far, ShapeFogStrength.far(near, far, fogEnd, 4000.0));
    }

    @Test
    void farClampsDistancesAndStrengths() {
        double near = 0.25;
        double far = 0.75;

        // Negative and NaN distances are "at the camera" and get the near share.
        assertEquals(near, ShapeFogStrength.far(near, far, 400.0, -10.0));
        assertEquals(near, ShapeFogStrength.far(near, far, 400.0, Double.NaN));
        // An infinite distance is past the reveal limit.
        assertEquals(far, ShapeFogStrength.far(near, far, 400.0, Double.POSITIVE_INFINITY));

        // Out-of-range strengths are clamped before interpolating.
        assertEquals(0.0, ShapeFogStrength.far(-2.0, -1.0, 400.0, 700.0));
        assertEquals(1.0, ShapeFogStrength.far(2.0, 3.0, 400.0, 700.0));

        double withNaNs = ShapeFogStrength.far(Double.NaN, Double.NaN, 400.0, 800.0);
        assertTrue(Double.isFinite(withNaNs));
        assertEquals(0.0, withNaNs);
    }

    @Test
    void aFogEndOutsideTheBandUsesTheNearShare() {
        double near = 0.25;
        double far = 0.75;

        // The formula divides by 1024 - fogEnd, so 1024 and beyond cannot ramp.
        assertEquals(near, ShapeFogStrength.far(near, far, 1024.0, 1024.0));
        assertEquals(near, ShapeFogStrength.far(near, far, 2000.0, 1024.0));
        assertEquals(near, ShapeFogStrength.far(near, far, Double.NaN, 800.0));
        assertEquals(near, ShapeFogStrength.far(near, far, Double.POSITIVE_INFINITY, 800.0));
        // A negative end cannot describe the terrain band; fall back to the near share.
        assertEquals(near, ShapeFogStrength.far(near, far, -1.0, 800.0));
    }

    @Test
    void centerDistanceUsesTheHorizontalBoxCenterAndIgnoresHeight() {
        // Inclusive max: the box spans [10, 20) x [-5, 6), so the horizontal center is (15, 0.5).
        OutlineBox box = new OutlineBox(10, 0, -5, 19, 9, 5);

        assertEquals(Math.hypot(15.0, 0.5), ShapeFogStrength.centerDistance(box, 0.0, 0.0), 1.0E-9);
        assertEquals(0.0, ShapeFogStrength.centerDistance(box, 15.0, 0.5), 1.0E-9);
    }
}
