package dimblend.radio.acoustics;

import java.util.function.DoubleUnaryOperator;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticPathingTest {
    private static final DoubleUnaryOperator LINEAR = d -> Math.max(0, 1 - d / 96);

    /** Steam Audio's coefficients for a path of {@code length} blocks arriving from -z (its Ambisonic X axis). */
    private static float[] steamCoefficients(double length) {
        float w = (float) (AcousticPathing.W_UNIT / length);
        return new float[] {w, 0, 0, w * 0.8f};
    }

    @Test
    void aHiddenListenerGetsThePathOnTheSessionsDistanceCurve() {
        float[] sh = steamCoefficients(30);
        var field = AcousticPathing.shape(new float[] {0.6f, 0.4f, 0.2f}, sh, 0, 1, LINEAR);
        assertNotNull(field);
        assertArrayEquals(new float[] {0.6f, 0.4f, 0.2f}, field.eq(), 1e-6f, "fully hidden: the whole path");
        double scale = (1 - 30 / 96.0) * 30;
        assertEquals(sh[0] * scale, field.sh()[0], 1e-6);
        assertEquals(sh[3] * scale, field.sh()[3], 1e-6);
        assertEquals(AcousticPathing.W_UNIT * (1 - 30 / 96.0), field.sh()[0], 1e-6, "W carries only the distance curve");
    }

    @Test
    void theFieldKeepsThePathLengthAndArrivalDirection() {
        var field = AcousticPathing.shape(new float[] {1, 1, 1}, steamCoefficients(30), 0, 1, LINEAR);
        assertNotNull(field);
        assertEquals(30, field.length(), 1e-3);
        assertDirection(new Vec3(0, 0, -1), field.arrival());
        float[] eq = {1, 1, 1};
        // Steam Audio's Ambisonic frame: Y grows with arrival from -x, Z from +y, X from -z.
        assertDirection(new Vec3(1, 0, 0), new PathingField(eq, new float[] {0.1f, -0.1f, 0, 0}, 10).arrival());
        assertDirection(new Vec3(0, 1, 0), new PathingField(eq, new float[] {0.1f, 0, 0.1f, 0}, 10).arrival());
        assertDirection(new Vec3(0, 0, 1), new PathingField(eq, new float[] {0.1f, 0, 0, -0.1f}, 10).arrival());
        assertNull(new PathingField(eq, new float[] {0.1f, 0, 0, 0}, 10).arrival(), "W alone has no direction");
    }

    private static void assertDirection(Vec3 expected, Vec3 actual) {
        assertNotNull(actual);
        assertEquals(0, expected.distanceTo(actual), 1e-6, actual.toString());
    }

    @Test
    void theVisibleShareIsLeftToTheDirectPath() {
        assertNull(AcousticPathing.shape(new float[] {1, 1, 1}, steamCoefficients(10), 1, 1, LINEAR), "in view: nothing to add");
        var half = AcousticPathing.shape(new float[] {1, 1, 1}, steamCoefficients(10), 0.5f, 1, LINEAR);
        assertNotNull(half);
        for (int band = 0; band < 3; band++) {
            assertEquals(1 - AcousticDiffraction.bandOcclusion(0.5f, band), half.eq()[band], 1e-6);
        }
        assertTrue(half.eq()[0] < half.eq()[2], "more of the high band is hidden, so more of it comes by the path");
    }

    @Test
    void anOvershootAtTheShadowEdgeNeverBoosts() {
        var field = AcousticPathing.shape(new float[] {1.14f, 1.02f, 0.9f}, steamCoefficients(12), 0, 1, LINEAR);
        assertNotNull(field);
        assertEquals(1, field.eq()[0], 1e-6);
        assertEquals(1, field.eq()[1], 1e-6);
        assertEquals(0.9f, field.eq()[2], 1e-6);
    }

    @Test
    void thePathFadesOutAtTheEdgeOfTheProbeRegion() {
        assertEquals(1, AcousticPathing.coverage(10));
        assertEquals(1, AcousticPathing.coverage(AcousticPathing.REGION_RADIUS - 8));
        assertEquals(0.5f, AcousticPathing.coverage(AcousticPathing.REGION_RADIUS - 4), 1e-6);
        assertEquals(0, AcousticPathing.coverage(AcousticPathing.REGION_RADIUS));
        var half = AcousticPathing.shape(new float[] {1, 0.8f, 0.6f}, steamCoefficients(20), 0, 0.5f, LINEAR);
        assertArrayEquals(new float[] {0.5f, 0.4f, 0.3f}, half.eq(), 1e-6f);
        assertNull(AcousticPathing.shape(new float[] {1, 1, 1}, steamCoefficients(20), 0, 0, LINEAR), "outside: none");
    }

    @Test
    void noPathOrAnInaudibleOneGivesNothing() {
        assertNull(AcousticPathing.shape(new float[3], new float[4], 0, 1, LINEAR), "no path found");
        assertNull(AcousticPathing.shape(new float[] {1, 1, 1}, steamCoefficients(120), 0, 1, LINEAR), "beyond the audible range");
        assertNull(AcousticPathing.shape(new float[] {1, 1, 1}, new float[] {Float.NaN, 0, 0, 0}, 0, 1, LINEAR));
        assertNull(AcousticPathing.shape(new float[] {1, 1, 1}, new float[] {0.01f, Float.POSITIVE_INFINITY, 0, 0}, 0, 1, LINEAR));
        var nanBand = AcousticPathing.shape(new float[] {Float.NaN, 1, 1}, steamCoefficients(10), 0, 1, LINEAR);
        assertNotNull(nanBand);
        assertEquals(0, nanBand.eq()[0]);
    }
}
