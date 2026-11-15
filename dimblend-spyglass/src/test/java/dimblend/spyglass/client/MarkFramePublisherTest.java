package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.api.HudWorldMarkers;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/** Scoped frames carry every in-range structure; lowered ones carry the selected mark only. */
class MarkFramePublisherTest {
    private static final ResourceLocation VILLAGE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "village_plains");
    private static final ResourceLocation MANSION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "mansion");
    private static final StructureKey VILLAGE_KEY = new StructureKey(VILLAGE, 3, 4);
    private static final StructureKey MANSION_KEY = new StructureKey(MANSION, 9, 9);
    private static final Matrix4f WORLD_TO_CLIP = new Matrix4f().translate(1.0F, 2.0F, 3.0F);
    private static final Matrix4f MODEL_VIEW = new Matrix4f().scale(2.0F);

    @Test
    void scopingPublishesEveryInRangeStructureOnce() {
        StructureSelection.Entry selected = entry(VILLAGE_KEY, 300.0, 40.0, false);
        List<StructureOutline> structures =
                List.of(outline(VILLAGE_KEY, 280, 320), outline(MANSION_KEY, 900, 960));

        MarkFrame frame = publish(true, true, selected, structures);

        assertNotNull(frame);
        assertEquals(2, frame.marks().size(), "the selection is already in the snapshot");
        assertEquals(VILLAGE_KEY, frame.marks().get(0).entry().key());
        assertEquals(MANSION_KEY, frame.marks().get(1).entry().key());
        assertEquals(WORLD_TO_CLIP, frame.worldToClip());
        assertEquals(MODEL_VIEW, frame.modelView());
    }

    @Test
    void scopingWithoutAUsableFogPublishesOnlyTheSnapshot() {
        StructureSelection.Entry selected = entry(VILLAGE_KEY, 300.0, 40.0, false);
        List<StructureOutline> structures = List.of(outline(MANSION_KEY, 900, 960));

        MarkFrame frame = publish(true, false, selected, structures);

        assertNotNull(frame);
        assertEquals(1, frame.marks().size(), "no fog means no distance, so no added selection");
        assertEquals(MANSION_KEY, frame.marks().get(0).entry().key());
    }

    @Test
    void loweringTheSpyglassPublishesOnlyTheSelectedMark() {
        StructureSelection.Entry selected = entry(MANSION_KEY, 900.0, 900.0, false);
        List<StructureOutline> structures =
                List.of(outline(VILLAGE_KEY, 280, 320), outline(MANSION_KEY, 880, 920));

        MarkFrame frame = publish(false, true, selected, structures);

        assertNotNull(frame);
        assertEquals(1, frame.marks().size(), "unmarked structures never reach the normal HUD");
        assertEquals(MANSION_KEY, frame.marks().get(0).entry().key());
        assertEquals(selected, frame.marks().get(0).entry());
    }

    @Test
    void loweringTheSpyglassWithoutASelectionPublishesNothing() {
        List<StructureOutline> structures = List.of(outline(VILLAGE_KEY, 280, 320));

        assertNull(publish(false, true, null, structures));
        assertNull(publish(false, false, null, structures));
    }

    @Test
    void theMarkedStructureSurvivesAnEmptySnapshotWithItsSavedAnchorAndDistance() {
        StructureSelection.Entry selected = entry(VILLAGE_KEY, 300.0, 40.0, true);

        MarkFrame frame = publish(false, true, selected, List.of());

        assertNotNull(frame);
        assertEquals(1, frame.marks().size());
        assertEquals(300.0, frame.marks().get(0).entry().centerX());
        assertEquals(81.0, frame.marks().get(0).entry().anchorY(), "bounds.maxY() + 1 at selection");
        assertEquals(40.0, frame.marks().get(0).entry().centerZ());
        // Horizontal union-bounds centre distance from camera (100, ?, 40): the same basis as scoped.
        assertEquals(200.0, frame.marks().get(0).centerDistance());
    }

    @Test
    void theMarkedStructureKeepsItsGlyphKindWhenLowered() {
        StructureSelection.Entry predicted = entry(VILLAGE_KEY, 300.0, 40.0, true);

        MarkFrame frame = publish(false, true, predicted, List.of());

        assertNotNull(frame);
        assertTrue(frame.marks().get(0).entry().predicted());
        assertEquals("[?]", MarkerGlyph.of(frame.marks().get(0).entry().predicted(), true));

        StructureSelection.Entry saved = entry(MANSION_KEY, 500.0, 500.0, false);
        MarkFrame savedFrame = publish(false, true, saved, List.of());
        assertNotNull(savedFrame);
        assertFalse(savedFrame.marks().get(0).entry().predicted());
        assertEquals("[!]", MarkerGlyph.of(savedFrame.marks().get(0).entry().predicted(), true));
    }

    @Test
    void externalMarkersAttachToAnEmptyFrame() {
        HudWorldMarkers.Marker marker =
                new HudWorldMarkers.Marker("photo_origin", "@", 300.0, 80.0, 40.0, false);

        MarkFrame frame =
                MarkFrame.attach(null, List.of(marker), WORLD_TO_CLIP, MODEL_VIEW, 100.0, 64.0, 40.0);

        assertNotNull(frame);
        assertTrue(frame.marks().isEmpty(), "a marker is not a structure mark");
        assertEquals(List.of(marker), frame.markers());
    }

    @Test
    void externalMarkersRideAlongWithStructureMarks() {
        HudWorldMarkers.Marker marker =
                new HudWorldMarkers.Marker("photo_origin", "@", 300.0, 80.0, 40.0, true);
        MarkFrame structures = publish(true, true, null, List.of(outline(VILLAGE_KEY, 280, 320)));

        MarkFrame frame = MarkFrame.attach(
                structures, List.of(marker), WORLD_TO_CLIP, MODEL_VIEW, 100.0, 64.0, 40.0);

        assertNotNull(frame);
        assertEquals(1, frame.marks().size());
        assertEquals(List.of(marker), frame.markers());
        assertEquals(WORLD_TO_CLIP, frame.worldToClip());
    }

    @Test
    void externalMarkersSurviveAddingTheSelection() {
        HudWorldMarkers.Marker marker =
                new HudWorldMarkers.Marker("photo_origin", "@", 300.0, 80.0, 40.0, false);
        MarkFrame frame = MarkFrame.attach(
                null, List.of(marker), WORLD_TO_CLIP, MODEL_VIEW, 100.0, 64.0, 40.0);

        MarkFrame extended = frame.withSelection(entry(VILLAGE_KEY, 300.0, 40.0, false));

        assertEquals(1, extended.marks().size());
        assertEquals(List.of(marker), extended.markers(), "markers are not structure marks");
    }

    @Test
    void noMarkersKeepsTheEmptyHandOff() {
        assertNull(MarkFrame.attach(null, List.of(), WORLD_TO_CLIP, MODEL_VIEW, 100.0, 64.0, 40.0));
    }

    private static MarkFrame publish(
            boolean scoping,
            boolean revealEnabled,
            StructureSelection.Entry selected,
            List<StructureOutline> structures) {
        return MarkFramePublisher.frameToPublish(
                scoping,
                revealEnabled,
                selected,
                structures,
                WORLD_TO_CLIP,
                MODEL_VIEW,
                100.0,
                64.0,
                40.0);
    }

    private static StructureSelection.Entry entry(
            StructureKey key, double centerX, double centerZ, boolean predicted) {
        return new StructureSelection.Entry(key, centerX, 81.0, centerZ, predicted);
    }

    private static StructureOutline outline(StructureKey key, int minX, int maxX) {
        return StructureOutline.of(key, List.of(new OutlineBox(minX, 64, 0, maxX, 80, 100)));
    }
}
