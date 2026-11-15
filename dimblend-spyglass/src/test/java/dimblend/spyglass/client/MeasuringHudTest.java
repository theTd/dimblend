package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The "measuring" line belongs under the crosshair, not at the bottom of the screen. */
class MeasuringHudTest {
    @Test
    void theLineSitsJustBelowTheVerticalCenter() {
        assertEquals(310, MeasuringHud.baselineY(600));
        assertEquals(250, MeasuringHud.baselineY(480));
        // Odd GUI heights keep the same one-pixel rounding as the centering itself.
        assertEquals(310, MeasuringHud.baselineY(601));
        assertEquals(
                600 / 2 + MeasuringHud.CROSSHAIR_OFFSET_Y, MeasuringHud.baselineY(600));
    }

    @Test
    void theLineClearsTheVanillaCrosshairBox() {
        // The crosshair texture is 15x15 GUI pixels around the center, so it ends 7 pixels out.
        for (int guiHeight = 240; guiHeight <= 2160; guiHeight += 24) {
            int y = MeasuringHud.baselineY(guiHeight);
            assertTrue(y >= guiHeight / 2 + 8, "overlaps the crosshair at height " + guiHeight);
            assertTrue(y < guiHeight - 30, "still near the bottom at height " + guiHeight);
        }
    }

    @Test
    void theTopOfScreenDistanceRowCannotReachTheMeasuringLine() {
        // CrosshairDistanceLayer draws a doubled line at TOP_Y; both texts can share a frame.
        for (int guiHeight = 240; guiHeight <= 2160; guiHeight += 24) {
            assertTrue(
                    MeasuringHud.baselineY(guiHeight) > CrosshairDistanceLayer.TOP_Y + 2 * 9,
                    "the two texts would collide at height " + guiHeight);
        }
    }
}
