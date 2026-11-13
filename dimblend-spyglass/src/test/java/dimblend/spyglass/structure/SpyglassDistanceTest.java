package dimblend.spyglass.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SpyglassDistanceTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final StructureKey KEY = new StructureKey(ID, 0, 0);

    @Test
    void measuresHorizontalDistanceToEnclosingBoxCenter() {
        StructureOutline outline = StructureOutline.of(KEY, List.of(
                new OutlineBox(-20, 20, -10, -1, 30, -1),
                new OutlineBox(0, 20, 0, 19, 30, 9)));

        assertEquals(0.0, SpyglassDistance.horizontal(0, 0, outline));
        assertEquals(256.0, SpyglassDistance.horizontal(256, 0, outline));
        assertEquals(500.0, SpyglassDistance.horizontal(0, 500, outline));
    }

    @Test
    void usesTheProvidedBoundsInsteadOfTheTransmittedPieces() {
        // The piece list was truncated: only the small piece is transmitted, the bounds still span
        // the full structure and must drive the center.
        StructureOutline truncated = new StructureOutline(
                KEY,
                new OutlineBox(-100, 0, -100, 99, 10, 99),
                List.of(new OutlineBox(-1, 0, -1, 0, 10, 0)));

        assertEquals(0.0, SpyglassDistance.horizontal(0, 0, truncated));
        assertEquals(300.0, SpyglassDistance.horizontal(300, 0, truncated));
    }

    @Test
    void widensCoordinatesBeforeSummingThem() {
        // Both ends at the top of the allowed range: (minX + maxX) + 1 overflows an int.
        OutlineBox bounds = new OutlineBox(
                OutlineBox.MAX_COORDINATE, 0, OutlineBox.MAX_COORDINATE,
                OutlineBox.MAX_COORDINATE, 0, OutlineBox.MAX_COORDINATE);
        StructureOutline outline = new StructureOutline(KEY, bounds, List.of(bounds));
        double center = OutlineBox.MAX_COORDINATE + 0.5;

        assertEquals(0.0, SpyglassDistance.horizontal(center, center, outline));
        assertEquals(1000.0, SpyglassDistance.horizontal(center + 1000.0, center, outline));
    }

    @Test
    void rangeIsFixedAtSixtyFourToTenTwentyFourBlocks() {
        assertFalse(SpyglassDistance.inRange(63.9));
        assertTrue(SpyglassDistance.inRange(64.0));
        assertTrue(SpyglassDistance.inRange(1024.0));
        assertFalse(SpyglassDistance.inRange(1024.1));
    }

    @Test
    void snapshotRangeIsPaddedOnBothEnds() {
        double pad = 48.0;

        assertTrue(SpyglassDistance.inSnapshotRange(64.0 - pad, pad));
        assertFalse(SpyglassDistance.inSnapshotRange(64.0 - pad - 0.01, pad));
        assertTrue(SpyglassDistance.inSnapshotRange(1024.0 + pad, pad));
        assertFalse(SpyglassDistance.inSnapshotRange(1024.0 + pad + 0.01, pad));
        assertThrows(IllegalArgumentException.class,
                () -> SpyglassDistance.inSnapshotRange(500.0, -1.0));
    }

    @Test
    void roundsToNearestTenAndFormatsWithoutUnit() {
        assertEquals("~260", SpyglassDistance.format(264));
        assertEquals("~270", SpyglassDistance.format(265));
        assertEquals("~1020", SpyglassDistance.format(1024));
    }

    @Test
    void boundingBoxVolumeRanksPiecesAndEnclosureIsChecked() {
        OutlineBox small = new OutlineBox(0, 0, 0, 1, 1, 1);
        OutlineBox large = new OutlineBox(0, 0, 0, 9, 0, 0);

        assertEquals(8.0, small.volume());
        assertEquals(10.0, large.volume());
        assertTrue(OutlineBox.enclosing(List.of(small, large)).encloses(large));
        assertFalse(small.encloses(large));
    }

    @Test
    void rejectsOutlinesThatAreInvertedOrNotEnclosed() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureOutline.of(KEY, List.of(new OutlineBox(5, 0, 0, 1, 1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new StructureOutline(KEY, new OutlineBox(0, 0, 0, 1, 1, 1),
                        List.of(new OutlineBox(0, 0, 0, 5, 1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> StructureOutline.of(KEY, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OutlineBox(0, 0, 0, OutlineBox.MAX_COORDINATE + 1, 1, 1));
    }
}
