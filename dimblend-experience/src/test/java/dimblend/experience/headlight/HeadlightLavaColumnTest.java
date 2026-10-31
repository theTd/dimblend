package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-cell rule of the lit-lava scan for the block above a lava cell: an exposed top face, or one cell of a
 * vertical falling lava column, or nothing. Lava that falls straight down is a column of {@code level=8} +
 * {@code FALLING=true} blocks whose every cell has the same fluid above (LavaFluid.isSame treats LAVA and
 * FLOWING_LAVA as one fluid), so the plain "same fluid above means the face is higher up" rule skipped the whole
 * column and the beams lit nothing there (reported 2026-10-31).
 */
class HeadlightLavaColumnTest {
    private static final float FALLING_LEVEL_8 = 8 / 9.0F;
    private static final float FLOWING_LEVEL_7 = 7 / 9.0F;
    private static final float SOURCE = 8 / 9.0F;

    @Test
    void aFallingColumnCellIsReportedEvenWhenTheSameFluidSitsAbove() {
        // The whole column: every cell but the top one has lava above it.
        assertEquals(HeadlightLavaColumn.Decision.COLUMN_FACE,
                HeadlightLavaColumn.decide(true, true, FALLING_LEVEL_8));
        assertEquals(HeadlightLavaColumn.Decision.COLUMN_FACE,
                HeadlightLavaColumn.decide(true, true, FLOWING_LEVEL_7));
    }

    @Test
    void aNonFallingCellUnderTheSameFluidStaysSkipped() {
        // A standing pool or a stair-stepped flow: the surface is the higher block, never this one.
        assertEquals(HeadlightLavaColumn.Decision.SKIP,
                HeadlightLavaColumn.decide(true, false, FALLING_LEVEL_8));
        assertEquals(HeadlightLavaColumn.Decision.SKIP,
                HeadlightLavaColumn.decide(true, false, SOURCE));
        assertEquals(HeadlightLavaColumn.Decision.SKIP,
                HeadlightLavaColumn.decide(true, false, FLOWING_LEVEL_7));
    }

    @Test
    void anOrdinaryTopFaceKeepsTheExistingRule() {
        // Air, a solid block or another fluid above: the top face is this cell's own, falling or not.
        assertEquals(HeadlightLavaColumn.Decision.TOP_FACE,
                HeadlightLavaColumn.decide(false, false, SOURCE));
        assertEquals(HeadlightLavaColumn.Decision.TOP_FACE,
                HeadlightLavaColumn.decide(false, true, FALLING_LEVEL_8));
        assertEquals(HeadlightLavaColumn.Decision.TOP_FACE,
                HeadlightLavaColumn.decide(false, false, FLOWING_LEVEL_7));
    }

    @Test
    void aCellBelowTheMinimumOwnHeightIsNeverReported() {
        // getOwnHeight() is amount / 9 (FlowingFluid.java:468-471): a level-0 flowing state is 0 and is not a face.
        assertEquals(HeadlightLavaColumn.Decision.SKIP, HeadlightLavaColumn.decide(false, false, 0.0F));
        assertEquals(HeadlightLavaColumn.Decision.SKIP, HeadlightLavaColumn.decide(true, true, 0.0F));
        assertEquals(HeadlightLavaColumn.Decision.SKIP, HeadlightLavaColumn.decide(true, true, 1.0E-4F));
        // At the threshold itself the face appears, so the boundary is part of the rule, not an accident.
        assertEquals(HeadlightLavaColumn.Decision.COLUMN_FACE,
                HeadlightLavaColumn.decide(true, true, HeadlightLavaColumn.MIN_HEIGHT));
        assertEquals(HeadlightLavaColumn.Decision.TOP_FACE,
                HeadlightLavaColumn.decide(false, false, HeadlightLavaColumn.MIN_HEIGHT));
    }

    @Test
    void theReportedFaceSitsAtTheFluidsOwnHeightNotAtAFullBlock() {
        // A falling column's cells are level 8, so the face is 8/9 above the block base -- the height of the
        // fluid the highlight is attached to, not the block's top (FlowingFluid.getHeight would say 1.0 only
        // when the same fluid is above, which is exactly the state this rule reports as its own face).
        double falling = new LavaSurface(5, 64, 7, FALLING_LEVEL_8).centerY();
        assertEquals(64.0 + (double) FALLING_LEVEL_8, falling, 1e-12);
        assertTrue(falling < 65.0, "never the block top");
        assertEquals(64.0 + (double) FLOWING_LEVEL_7, new LavaSurface(5, 64, 7, FLOWING_LEVEL_7).centerY(), 1e-12);
        assertEquals(64.0 + (double) SOURCE, new LavaSurface(5, 64, 7, SOURCE).centerY(), 1e-12);
        // The horizontal sample point is the block centre, as for every other face.
        assertEquals(5.5, new LavaSurface(5, 64, 7, FALLING_LEVEL_8).centerX(), 1e-12);
        assertEquals(7.5, new LavaSurface(5, 64, 7, FALLING_LEVEL_8).centerZ(), 1e-12);
    }

    @Test
    void everyCellOfAFallingColumnMergesIntoOneVerticalTube() {
        // The column reports one face per block; LavaBoxOutline merges the touching 2x2x2 boxes, so a four-block
        // column is one 2x5x2 shell with no ring between its blocks -- the outline needs no change for this.
        List<LavaSurface> column = new ArrayList<>();
        for (int y = 64; y < 68; y++) {
            assertEquals(HeadlightLavaColumn.Decision.COLUMN_FACE,
                    HeadlightLavaColumn.decide(true, true, FALLING_LEVEL_8), "cell at y " + y);
            column.add(new LavaSurface(0, y, 0, FALLING_LEVEL_8));
        }
        List<LavaEdge> edges = LavaBoxOutline.edges(column);
        assertEquals(12, edges.size(), "one tube, not four boxes");
        for (LavaEdge edge : edges) {
            double dx = Math.abs(edge.x2() - edge.x1());
            double dy = Math.abs(edge.y2() - edge.y1());
            double dz = Math.abs(edge.z2() - edge.z1());
            assertEquals(1, (dx > 0 ? 1 : 0) + (dy > 0 ? 1 : 0) + (dz > 0 ? 1 : 0), "axis aligned: " + edge);
        }
        // Two blocks wide and tall in x/z, five cells' worth of height over four blocks.
        double lowY = Double.MAX_VALUE;
        double highY = -Double.MAX_VALUE;
        double lowX = Double.MAX_VALUE;
        double highX = -Double.MAX_VALUE;
        for (LavaEdge edge : edges) {
            lowY = Math.min(lowY, Math.min(edge.y1(), edge.y2()));
            highY = Math.max(highY, Math.max(edge.y1(), edge.y2()));
            lowX = Math.min(lowX, Math.min(edge.x1(), edge.x2()));
            highX = Math.max(highX, Math.max(edge.x1(), edge.x2()));
        }
        assertEquals(-0.5, lowX, 1e-9);
        assertEquals(1.5, highX, 1e-9);
        assertEquals(63.5, lowY, 1e-9);
        assertEquals(68.5, highY, 1e-9);
    }

    @Test
    void aStairSteppedFlowIsUnchangedBecauseItsStepsHaveAirAbove() {
        // Two flowing cells stepping down: each has air above, so each is an ordinary top face.
        assertEquals(HeadlightLavaColumn.Decision.TOP_FACE,
                HeadlightLavaColumn.decide(false, false, FLOWING_LEVEL_7));
        List<LavaSurface> step = List.of(new LavaSurface(0, 64, 0, SOURCE), new LavaSurface(1, 63, 0, FLOWING_LEVEL_7));
        assertEquals(24, LavaBoxOutline.edges(step).size(), "the stepped shape from LavaBoxOutlineTest");
    }
}