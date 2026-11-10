package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.StructureKey;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MarkScreenFrameTest {
    private static final StructureKey VILLAGE = key("village_plains", 1, 2);
    private static final StructureKey MANSION = key("mansion", 3, 4);

    @Test
    void onlyDrawnMarksCanBeHit() {
        // The undrawn mark sits exactly under the crosshair; the drawn one 10 pixels to the right.
        MarkScreenFrame frame = frame(
                mark(VILLAGE, 100.0F, 100.0F, false, 16.0F, false),
                mark(MANSION, 110.0F, 100.0F, true, 16.0F, false));

        assertEquals(1, frame.hit(100.0, 100.0, 4.0));
    }

    @Test
    void theSelectionHalfWidthWidensTheClickBox() {
        MarkScreenFrame wide = frame(mark(VILLAGE, 121.0F, 100.0F, true, 22.0F, false));
        MarkScreenFrame plain = frame(mark(VILLAGE, 121.0F, 100.0F, true, 16.0F, false));

        assertEquals(0, wide.hit(100.0, 100.0, 4.0));
        assertEquals(-1, plain.hit(100.0, 100.0, 4.0));
    }

    @Test
    void theNearestCentreWinsAcrossDifferentBoxWidths() {
        MarkScreenFrame frame = frame(
                mark(VILLAGE, 119.0F, 100.0F, true, 22.0F, false),
                mark(MANSION, 105.0F, 100.0F, true, 16.0F, false));

        assertEquals(1, frame.hit(100.0, 100.0, 4.0));
    }

    @Test
    void aMarkWithoutAScreenPositionIsNeverHit() {
        MarkScreenFrame frame = frame(mark(VILLAGE, Float.NaN, Float.NaN, true, 16.0F, false));

        assertEquals(-1, frame.hit(100.0, 100.0, 4.0));
    }

    @Test
    void aFreshFrameMustBeRecentAndMatchTheGuiSize() {
        MarkScreenFrame frame = new MarkScreenFrame(
                List.of(mark(VILLAGE, 100.0F, 100.0F, true, 16.0F, false)),
                800.0,
                600.0,
                1_000_000_000L);

        assertTrue(frame.isFresh(1_000_000_000L, 800.0, 600.0, 250_000_000L));
        assertTrue(frame.isFresh(1_250_000_000L, 800.0, 600.0, 250_000_000L));
        assertFalse(frame.isFresh(1_250_000_001L, 800.0, 600.0, 250_000_000L), "too old");
        assertFalse(frame.isFresh(1_000_000_000L, 801.0, 600.0, 250_000_000L), "resized");
        assertFalse(frame.isFresh(1_000_000_000L, 800.0, 601.0, 250_000_000L), "resized");
        assertFalse(frame.isFresh(999_999_999L, 800.0, 600.0, 250_000_000L), "clock before publish");
    }

    @Test
    void theEmptyFrameIsNeverFresh() {
        assertFalse(MarkScreenFrame.EMPTY.isFresh(100L, 0.0, 0.0, 250_000_000L));
    }

    @Test
    void aHiddenSelectedMarkIsStillLocatableByKey() {
        MarkScreenFrame frame = frame(
                mark(VILLAGE, 100.0F, 100.0F, false, 22.0F, true),
                mark(MANSION, 300.0F, 100.0F, true, 16.0F, false));

        MarkScreenFrame.Mark selected = frame.markWithKey(VILLAGE);

        assertNotNull(selected);
        assertFalse(selected.drawn());
        assertNull(frame.markWithKey(key("end_city", 9, 9)));
    }

    @Test
    void theMarkCarriesTheSelectionIdentityAndPredictionFlag() {
        MarkScreenFrame.Mark projectedMark = mark(VILLAGE, 100.0F, 100.0F, true, 16.0F, true);

        assertEquals(VILLAGE, projectedMark.key());
        assertTrue(projectedMark.predicted());
        assertEquals(VILLAGE, projectedMark.entry().key());
    }

    private static MarkScreenFrame frame(MarkScreenFrame.Mark... marks) {
        return new MarkScreenFrame(List.of(marks), 800.0, 600.0, 1_000_000_000L);
    }

    private static MarkScreenFrame.Mark mark(
            StructureKey key, float x, float y, boolean drawn, float halfWidth, boolean predicted) {
        return new MarkScreenFrame.Mark(
                new StructureSelection.Entry(key, 0.0, 64.0, 0.0, predicted),
                x,
                y,
                300.0,
                drawn,
                false,
                halfWidth,
                0.0F,
                0.0F,
                -1.0F);
    }

    private static StructureKey key(String path, int chunkX, int chunkZ) {
        return new StructureKey(
                ResourceLocation.fromNamespaceAndPath("minecraft", path), chunkX, chunkZ);
    }
}
