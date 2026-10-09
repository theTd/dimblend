package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DeathExperienceMathTest {
    @Test
    void fullClearZeroesEverything() {
        var scaled = DeathExperienceMath.scale(12, 0.4F, 500, 1.0D);
        assertEquals(0, scaled.level());
        assertEquals(0.0F, scaled.progress());
        assertEquals(0, scaled.total());
    }

    @Test
    void zeroClearKeepsEverything() {
        var scaled = DeathExperienceMath.scale(12, 0.4F, 500, 0.0D);
        assertEquals(12, scaled.level());
        assertEquals(0.4F, scaled.progress());
        assertEquals(500, scaled.total());
    }

    @Test
    void halfClearScalesLevelProgressAndTotal() {
        var scaled = DeathExperienceMath.scale(10, 0.5F, 101, 0.5D);
        assertEquals(5, scaled.level());
        assertEquals(0.25F, scaled.progress());
        assertEquals(51, scaled.total());
    }

    /** 复核 L1 回归：比率 0.80 时 keep = 0.19999999999999998，整 5 级曾得 0 级 + progress 1.0f。 */
    @Test
    void floatingPointUlpCannotRollProgressUpToOne() {
        var scaled = DeathExperienceMath.scale(5, 0.0F, 100, 0.8D);
        assertEquals(1, scaled.level());
        assertEquals(0.0F, scaled.progress());
    }

    @Test
    void progressNeverReachesOneAcrossAllPanelRatios() {
        for (int percent = 0; percent <= 100; percent++) {
            double ratio = percent / 100.0D;
            for (int level = 0; level <= 5000; level++) {
                for (float progress : new float[] {0.0F, 0.25F, 0.5F, 0.75F, 0.99F}) {
                    var scaled = DeathExperienceMath.scale(level, progress, 0, ratio);
                    assertTrue(scaled.progress() >= 0.0F && scaled.progress() < 1.0F,
                            "ratio " + ratio + " level " + level + " progress " + progress);
                    assertTrue(scaled.level() >= 0);
                }
            }
        }
    }
}
