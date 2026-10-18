package dimblend.experience.compat.dynlight;

import static org.junit.jupiter.api.Assertions.*;
import dimblend.experience.tuning.TuningOption;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

class HeadlightMathTest {
    @Test
    void slightlyTiltedNorthLampRetainsAUnitInvertibleRotation() {
        for (var direction : new Vector3d[] {new Vector3d(1.423e-10, 3.996e-7, -1),
                new Vector3d(0, 0, -1), new Vector3d(0, 0, 1), new Vector3d(1, 0, 0),
                new Vector3d(-1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, -1, 0)}) {
            var rotation = new Quaternionf();
            HeadlightMath.worldToLight(direction, rotation);
            assertEquals(1, rotation.lengthSquared(), 0.000001, "Veil must receive a unit quaternion");
            assertEquals(1, new Matrix4f().rotation(rotation).determinant(), 0.000001,
                    "Veil inverts this rotation in its area-light shader");
            var local = rotation.transform(new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
            assertTrue(local.distance(0, 0, 1) < 0.000001, "model direction maps to local +Z: " + direction);
        }
    }

    @Test
    void selectsOnlyTheRequestedLampFamilies() {
        assertTrue(HeadlightMath.matches("dndecor", "brass_frontlight"));
        assertTrue(HeadlightMath.matches("dndecor", "industrial_frontlight"));
        assertFalse(HeadlightMath.matches("bellsandwhistles", "headlight"));
        assertFalse(HeadlightMath.matches("othermod", "brass_frontlight"));
        assertFalse(HeadlightMath.matches("dndecor", "brass_lamp"));
        assertFalse(HeadlightMath.matches("bellsandwhistles", "headlight_extra"));
    }

    @Test
    void brightnessUsesSearchlightsScaleAndHonorsEmissionCapAndOff() {
        assertEquals(4.0F, HeadlightMath.brightness(true, 15, 15));
        assertEquals(1.6F, HeadlightMath.brightness(true, 15, 6), 0.0001F);
        assertEquals(0.8F, HeadlightMath.brightness(true, 3, 15), 0.0001F);
        assertEquals(0, HeadlightMath.brightness(false, 15, 15));
        assertEquals(0, HeadlightMath.brightness(true, 15, 0));
    }

    @Test
    void panelDefaultsPreserveCurrentLightingAndBoundsAreValidated() {
        assertEquals(1, TuningOption.SABLE_HEADLIGHT.defaultValue());
        assertEquals(7.75, TuningOption.SABLE_HEADLIGHT_RADIUS.defaultValue());
        assertEquals(15, TuningOption.SABLE_HEADLIGHT_BRIGHTNESS.defaultValue());
        assertTrue(TuningOption.SABLE_HEADLIGHT_RADIUS.isValid(32));
        assertTrue(TuningOption.SABLE_HEADLIGHT_RADIUS.isValid(7.75));
        assertFalse(TuningOption.SABLE_HEADLIGHT_RADIUS.isValid(0));
        assertFalse(TuningOption.SABLE_HEADLIGHT_RADIUS.isValid(32.01));
        assertFalse(TuningOption.SABLE_HEADLIGHT_RADIUS.isValid(7.755));
        assertTrue(TuningOption.SABLE_HEADLIGHT_BRIGHTNESS.isValid(0));
        assertFalse(TuningOption.SABLE_HEADLIGHT_BRIGHTNESS.isValid(15.1));
        assertEquals(60, TuningOption.SABLE_HEADLIGHT_ANGLE.defaultValue());
        assertTrue(TuningOption.SABLE_HEADLIGHT_ANGLE.isValid(5));
        assertTrue(TuningOption.SABLE_HEADLIGHT_ANGLE.isValid(160));
        assertFalse(TuningOption.SABLE_HEADLIGHT_ANGLE.isValid(60.5));
        assertFalse(TuningOption.SABLE_HEADLIGHT_ANGLE.isValid(161));
        assertEquals(1, TuningOption.SABLE_HEADLIGHT_OCCLUSION.defaultValue());
    }
}
