package dimblend.experience.compat.dynlight;

import static org.junit.jupiter.api.Assertions.*;
import dimblend.experience.tuning.TuningOption;
import org.junit.jupiter.api.Test;

class HeadlightMathTest {
    @Test
    void selectsOnlyTheRequestedLampFamilies() {
        assertTrue(HeadlightMath.matches("dndecor", "brass_frontlight"));
        assertTrue(HeadlightMath.matches("dndecor", "industrial_frontlight"));
        assertTrue(HeadlightMath.matches("bellsandwhistles", "headlight"));
        assertFalse(HeadlightMath.matches("othermod", "brass_frontlight"));
        assertFalse(HeadlightMath.matches("dndecor", "brass_lamp"));
        assertFalse(HeadlightMath.matches("bellsandwhistles", "headlight_extra"));
    }

    @Test
    void radiusChangesReachWithoutIncreasingBrightness() {
        assertEquals(15, HeadlightMath.light(0, 7.75, 15));
        assertEquals(0, HeadlightMath.light(64, 7.75, 15));
        assertEquals(7.5, HeadlightMath.light(64, 16, 15));
        assertEquals(0, HeadlightMath.light(256, 16, 15));
        assertEquals(3, HeadlightMath.light(64, 16, 6));
        assertEquals(0, HeadlightMath.light(0, 16, 0));
    }

    @Test
    void refreshBoundsCoverRadiusAndSectionBordersIncludingNegativeCoordinates() {
        for (double center : new double[] {-32.5, -16, -0.5, 0, 15.99, 16, 32.5}) {
            for (double radius : new double[] {1, 7.75, 16, 32}) {
                int min = HeadlightMath.minimumSection(center, radius);
                int max = HeadlightMath.maximumSection(center, radius);
                for (int sample = (int) Math.floor(center - radius - 1); sample <= Math.ceil(center + radius + 1); sample++) {
                    int section = Math.floorDiv(sample, 16);
                    if (sample <= center + radius + 1) assertTrue(section >= min && section <= max);
                }
            }
        }
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
    }
}
