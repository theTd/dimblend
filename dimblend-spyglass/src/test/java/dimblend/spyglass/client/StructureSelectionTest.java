package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StructureSelectionTest {
    private static final ResourceLocation VILLAGE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "village_plains");
    private static final ResourceLocation MANSION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "mansion");

    @AfterEach
    void forgetSelection() {
        StructureSelection.clear();
    }

    @Test
    void clickingTheSelectedStructureAgainClearsIt() {
        StructureSelection.Entry selected = entry(VILLAGE, 3, 4, 200.0, -50.0, false);
        StructureSelection.toggle(selected);
        assertTrue(StructureSelection.isSelected(selected.key()));

        // A later snapshot may deliver a new entry for the same start: only the key matters.
        StructureSelection.toggle(entry(VILLAGE, 3, 4, 200.0, -50.0, false));

        assertNull(StructureSelection.current());
        assertFalse(StructureSelection.isSelected(selected.key()));
    }

    @Test
    void clickingAnotherStructureReplacesTheSelection() {
        StructureSelection.Entry village = entry(VILLAGE, 3, 4, 200.0, -50.0, false);
        StructureSelection.Entry mansion = entry(MANSION, 9, 9, 500.0, 500.0, true);

        StructureSelection.toggle(village);
        StructureSelection.toggle(mansion);

        assertNotNull(StructureSelection.current());
        assertEquals(mansion.key(), StructureSelection.current().key());
        assertFalse(StructureSelection.isSelected(village.key()));
    }

    @Test
    void aPredictedOutlineThatBecomesSavedKeepsTheSelection() {
        StructureKey key = new StructureKey(VILLAGE, 1, 2);
        StructureSelection.toggle(new StructureSelection.Entry(key, 100.0, 80.0, 120.0, true));

        // The world generated the structure: the same key now arrives as a saved start.
        StructureSelection.observe(key, false, 100.0, 80.0, 120.0);

        assertTrue(StructureSelection.isSelected(key), "the key, not the glyph, is the identity");
        assertFalse(StructureSelection.current().predicted());
    }

    @Test
    void aMarkOfAnotherStructureDoesNotTouchTheSelection() {
        StructureSelection.Entry village = entry(VILLAGE, 3, 4, 200.0, -50.0, false);
        StructureSelection.Entry mansion = entry(MANSION, 9, 9, 500.0, 500.0, false);
        StructureSelection.toggle(village);

        StructureSelection.observe(mansion.key(), true, 0.0, 0.0, 0.0);

        assertEquals(village, StructureSelection.current());
    }

    @Test
    void anEntryUsesTheUnionBoundsTopCentreAndTheOutlineFlag() {
        OutlineBox pieceA = new OutlineBox(10, 60, 20, 30, 64, 40);
        OutlineBox pieceB = new OutlineBox(-5, 61, 0, 5, 70, 9);
        StructureOutline outline = StructureOutline.of(
                new StructureKey(VILLAGE, 2, 3), List.of(pieceA, pieceB));

        StructureSelection.Entry entry = StructureSelection.Entry.of(outline);

        assertEquals(outline.key(), entry.key());
        assertEquals(outline.bounds().centerX(), entry.centerX());
        assertEquals(outline.bounds().centerZ(), entry.centerZ());
        assertEquals(71.0, entry.anchorY(), "the anchor sits one block above the highest piece");
        assertFalse(entry.predicted());
    }

    @Test
    void theSavedCentreIsTheDistanceBasisAndTheRingIsInclusive() {
        StructureSelection.Entry entry = entry(VILLAGE, 3, 4, 300.0, 0.0, false);

        StructureSelection.toggle(entry);
        StructureSelection.pruneOutOfRange(300.0 + 128.0, 0.0, true);
        assertTrue(StructureSelection.isSelected(entry.key()), "128 is inside the ring");

        StructureSelection.pruneOutOfRange(300.0 + 1024.0, 0.0, true);
        assertTrue(StructureSelection.isSelected(entry.key()), "1024 is inside the ring");

        StructureSelection.pruneOutOfRange(300.0 + 127.999, 0.0, true);
        assertNull(StructureSelection.current(), "inside the inner edge clears");

        StructureSelection.toggle(entry);
        StructureSelection.pruneOutOfRange(300.0 + 1024.001, 0.0, true);
        assertNull(StructureSelection.current(), "past the outer edge clears");
    }

    @Test
    void anUnusableFogNeverPrunesTheSelection() {
        StructureSelection.Entry entry = entry(VILLAGE, 3, 4, 0.0, 0.0, false);
        StructureSelection.toggle(entry);

        // Underwater, in lava, blind: the fog says nothing about the structure's distance.
        StructureSelection.pruneOutOfRange(99999.0, -99999.0, false);

        assertTrue(StructureSelection.isSelected(entry.key()));
    }

    @Test
    void turningAndEmptySnapshotsDoNotPruneTheSelection() {
        StructureSelection.Entry entry = entry(VILLAGE, 3, 4, 300.0, 0.0, false);
        StructureSelection.toggle(entry);

        // A frame with no marks at all: nothing calls observe(), and the saved centre stays in the
        // ring, so the per-frame prune keeps it.
        for (int frame = 0; frame < 10; frame++) {
            StructureSelection.pruneOutOfRange(300.0 + 200.0 + frame, 0.0, true);
        }

        assertTrue(StructureSelection.isSelected(entry.key()));
    }

    @Test
    void pruningWithoutASelectionIsHarmless() {
        StructureSelection.pruneOutOfRange(0.0, 0.0, true);
        assertNull(StructureSelection.current());
    }

    @Test
    void clearForgetsTheSelection() {
        StructureSelection.toggle(entry(VILLAGE, 3, 4, 200.0, -50.0, false));
        StructureSelection.clear();
        assertNull(StructureSelection.current());
    }

    @Test
    void clearingTheClientLevelStateClearsTheSelection() {
        StructureSelection.toggle(entry(VILLAGE, 3, 4, 200.0, -50.0, false));

        // Dimension change, logout and level unload all funnel through the client state reset.
        SpyglassClientState.clear();

        assertNull(StructureSelection.current());
    }

    @Test
    void aNonFiniteAnchorIsRejected() {
        StructureKey key = new StructureKey(VILLAGE, 1, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructureSelection.Entry(key, Double.NaN, 64.0, 0.0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StructureSelection.Entry(key, 0.0, Double.POSITIVE_INFINITY, 0.0, false));
    }

    private static StructureSelection.Entry entry(
            ResourceLocation id,
            int startChunkX,
            int startChunkZ,
            double centerX,
            double centerZ,
            boolean predicted) {
        return new StructureSelection.Entry(
                new StructureKey(id, startChunkX, startChunkZ), centerX, 64.0, centerZ, predicted);
    }
}
