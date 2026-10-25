package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SpyglassSnapshotLimiterTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("test", "world");
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final StructureKey KEY = new StructureKey(ID, 0, 0);
    /** Inner edge used by tests that only exercise the outer cap. */
    private static final double NO_INNER_EDGE = 0.0;

    @Test
    void filtersByThePaddedRingAndSortsByDistance() {
        double reveal = 300.0;
        StructureOutline near = outlineAt(300, 0, 1);
        StructureOutline farther = outlineAt(700, 0, 1);
        StructureOutline justInsideThePaddedInnerEdge = outlineAt(251, 0, 1);
        StructureOutline justBelowThePaddedInnerEdge = outlineAt(250, 0, 1);
        StructureOutline beyondPadding = outlineAt(1080, 0, 1);

        SpyglassSnapshotLimiter.LimitResult result = SpyglassSnapshotLimiter.limit(
                0,
                0,
                reveal,
                List.of(
                        farther,
                        beyondPadding,
                        near,
                        justBelowThePaddedInnerEdge,
                        justInsideThePaddedInnerEdge));

        assertEquals(
                List.of(justInsideThePaddedInnerEdge, near, farther), result.structures());
    }

    @Test
    void keepsALongStructureWhoseCenterIsInsideTheRevealDistance() {
        // A 400-block-long building: its center is only 200 blocks away, but its far footprint
        // reaches 400 blocks, i.e. past a reveal distance of 300.
        StructureOutline longHouse = StructureOutline.of(
                KEY, List.of(new OutlineBox(0, 0, 0, 399, 10, 10)));

        assertEquals(
                1,
                SpyglassSnapshotLimiter.limit(0, 0, 300.0, List.of(longHouse)).selected().size());
        // ... and it is dropped when even its far end is inside the fog, beyond the snapshot's inner
        // padding (reveal - SNAPSHOT_PAD_BLOCKS).
        assertEquals(
                0,
                SpyglassSnapshotLimiter.limit(0, 0, 449.0, List.of(longHouse)).selected().size());
    }

    @Test
    void truncatesOversizedStructuresToTheirLargestPieces() {
        List<OutlineBox> pieces = new ArrayList<>();
        List<OutlineBox> large = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OutlineBox box = new OutlineBox(300 + i * 4, 0, 0, 303 + i * 4, 7, 7);
            large.add(box);
            pieces.add(box);
        }
        int tinyCount = SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE - 5 + 10;
        for (int i = 0; i < tinyCount; i++) {
            int x = 300 + i % 20;
            pieces.add(new OutlineBox(x, 0, 8, x, 0, 8));
        }
        StructureOutline outline = StructureOutline.of(KEY, pieces);

        SpyglassSnapshotLimiter.LimitResult result =
                SpyglassSnapshotLimiter.limit(0, 0, NO_INNER_EDGE, List.of(outline));

        assertEquals(1, result.truncatedStructures());
        assertEquals(0, result.omittedStructures());
        StructureOutline transmitted = result.structures().get(0);
        assertEquals(SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE, transmitted.pieces().size());
        assertEquals(outline.bounds(), transmitted.bounds());
        assertTrue(transmitted.pieces().containsAll(large), "the largest pieces must survive the cap");
        assertEquals(SpyglassSnapshotPayload.MAX_PIECES_PER_STRUCTURE, result.totalPieces());
        new SpyglassSnapshotPayload(DIMENSION, 1, result.structures());
    }

    @Test
    void dropsTheFarthestStructuresWhenTheTotalCapIsHit() {
        List<StructureOutline> outlines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            outlines.add(outlineAt(300 + i, 0, 4000));
        }

        SpyglassSnapshotLimiter.LimitResult result =
                SpyglassSnapshotLimiter.limit(0, 0, NO_INNER_EDGE, outlines);

        assertEquals(1, result.omittedStructures());
        assertEquals(0, result.truncatedStructures());
        assertEquals(16000, result.totalPieces());
        assertEquals(4, result.structures().size());
        assertEquals(outlines.get(0), result.structures().get(0));
        assertEquals(outlines.get(3), result.structures().get(3));
        new SpyglassSnapshotPayload(DIMENSION, 2, result.structures());
    }

    @Test
    void aFartherSmallerStructureDoesNotTakeTheBudgetANearerOneMissed() {
        // 4000 * 4 = 16000 pieces fit; the 5th (nearer than the 6th) needs 500 more and does not fit.
        // The 6th would fit (300) but is farther, so it must be dropped too.
        List<StructureOutline> outlines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            outlines.add(outlineAt(300 + i, 0, 4000));
        }
        outlines.add(outlineAt(400, 0, 500));
        outlines.add(outlineAt(500, 0, 300));

        SpyglassSnapshotLimiter.LimitResult result =
                SpyglassSnapshotLimiter.limit(0, 0, NO_INNER_EDGE, outlines);

        assertEquals(4, result.structures().size());
        assertEquals(16000, result.totalPieces());
        assertEquals(2, result.omittedStructures());
    }

    @Test
    void capsStructureCountAndKeepsTheNearest() {
        List<StructureOutline> outlines = new ArrayList<>();
        for (int i = 0; i < SpyglassSnapshotPayload.MAX_STRUCTURES + 1; i++) {
            outlines.add(outlineAt(400 + i, 0, 1));
        }

        SpyglassSnapshotLimiter.LimitResult result =
                SpyglassSnapshotLimiter.limit(0, 0, NO_INNER_EDGE, outlines);

        assertEquals(SpyglassSnapshotPayload.MAX_STRUCTURES, result.structures().size());
        assertEquals(1, result.omittedStructures());
        assertEquals(outlines.get(0), result.structures().get(0));
        new SpyglassSnapshotPayload(DIMENSION, 3, result.structures());
    }

    @Test
    void largestPiecesKeepsTheHeaviestOnesInOriginalOrder() {
        List<OutlineBox> pieces = List.of(
                new OutlineBox(0, 0, 0, 0, 0, 0),
                new OutlineBox(0, 0, 0, 9, 9, 9),
                new OutlineBox(0, 0, 0, 1, 1, 1),
                new OutlineBox(0, 0, 0, 19, 0, 0));

        assertEquals(
                List.of(pieces.get(1), pieces.get(3)),
                SpyglassSnapshotLimiter.largestPieces(pieces, 2));
        assertEquals(pieces, SpyglassSnapshotLimiter.largestPieces(pieces, 10));
    }

    private static StructureOutline outlineAt(int centerX, int centerZ, int pieceCount) {
        OutlineBox box = new OutlineBox(centerX, 0, centerZ, centerX, 1, centerZ);
        return StructureOutline.of(KEY, Collections.nCopies(pieceCount, box));
    }
}
