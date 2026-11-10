package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dimblend.spyglass.structure.StructureKey;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class EdgeArrowTargetTest {
    private static final StructureKey VILLAGE = key("village_plains", 1, 2);
    private static final StructureKey MANSION = key("mansion", 3, 4);
    private static final StructureKey MONUMENT = key("monument", 5, 6);

    @Test
    void withoutASelectionTheNearestPredictedMarkOutsideTheLensWins() {
        MarkScreenFrame frame = frame(
                mark(VILLAGE, false, false, true, 100.0),
                mark(MANSION, true, true, true, 50.0),
                mark(MONUMENT, true, false, true, 300.0));

        // VILLAGE is saved (!), MANSION is predicted but inside the lens: only MONUMENT qualifies.
        assertEquals(MONUMENT, EdgeArrowTarget.choose(frame, null).key());
    }

    @Test
    void anUnselectedSavedMarkNeverStealsTheFallback() {
        MarkScreenFrame frame = frame(
                mark(VILLAGE, false, false, true, 40.0),
                mark(MANSION, true, false, true, 500.0));

        assertEquals(MANSION, EdgeArrowTarget.choose(frame, null).key());
    }

    @Test
    void withASelectionOnlyThatMarkIsUsed() {
        MarkScreenFrame frame = frame(
                mark(VILLAGE, false, false, true, 900.0),
                mark(MANSION, true, false, true, 100.0));

        // A selected saved mark (no ?) still owns the arrow, even when a nearer ? exists.
        assertEquals(VILLAGE, EdgeArrowTarget.choose(frame, VILLAGE).key());
    }

    @Test
    void aSelectedMarkInsideTheLensGetsNoArrow() {
        MarkScreenFrame frame = frame(mark(VILLAGE, false, true, true, 100.0));

        assertNull(EdgeArrowTarget.choose(frame, VILLAGE));
    }

    @Test
    void aHiddenSelectedMarkStillLocatesTheArrow() {
        // Skipped by the overlap rule (drawn=false) but still projected: the arrow must not care.
        MarkScreenFrame frame = frame(mark(VILLAGE, false, false, false, 100.0));

        assertEquals(VILLAGE, EdgeArrowTarget.choose(frame, VILLAGE).key());
    }

    @Test
    void aMissingSelectionGetsNoArrowInsteadOfAFallback() {
        MarkScreenFrame frame = frame(mark(MANSION, true, false, true, 100.0));

        assertNull(EdgeArrowTarget.choose(frame, VILLAGE));
    }

    private static MarkScreenFrame frame(MarkScreenFrame.Mark... marks) {
        return new MarkScreenFrame(List.of(marks), 800.0, 600.0, 1_000_000_000L);
    }

    private static MarkScreenFrame.Mark mark(
            StructureKey key, boolean predicted, boolean inLens, boolean drawn, double distance) {
        return new MarkScreenFrame.Mark(
                new StructureSelection.Entry(key, 0.0, 64.0, 0.0, predicted),
                0.0F,
                0.0F,
                distance,
                drawn,
                inLens,
                16.0F,
                1.0F,
                0.0F,
                -1.0F);
    }

    private static StructureKey key(String path, int chunkX, int chunkZ) {
        return new StructureKey(
                ResourceLocation.fromNamespaceAndPath("minecraft", path), chunkX, chunkZ);
    }
}
