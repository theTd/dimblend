package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The section-bucketed piece membership used by the buried-block search: it must agree with a plain
 * "inside any piece" test, including at negative coordinates and section boundaries.
 */
class PieceBoxIndexTest {
    @Test
    void membershipMatchesThePlainPieceTest() {
        List<OutlineBox> pieces = List.of(
                new OutlineBox(-20, 0, -20, -5, 10, -5),
                new OutlineBox(0, 5, 0, 15, 15, 15),
                new OutlineBox(16, 0, 16, 31, 3, 31));
        PieceBoxIndex index = new PieceBoxIndex(pieces);
        int checks = 0;
        for (int y = -8; y <= 24; y += 4) {
            for (int z = -24; z <= 36; z += 4) {
                for (int x = -24; x <= 36; x += 4) {
                    assertEquals(insideAnyPiece(pieces, x, y, z), index.contains(x, y, z),
                            "cell " + x + "," + y + "," + z);
                    checks++;
                }
            }
        }
        assertTrue(checks > 1000);
    }

    @Test
    void distantCellsAreRejectedWithoutScanningThePieceList() {
        PieceBoxIndex index = new PieceBoxIndex(List.of(new OutlineBox(0, 0, 0, 15, 15, 15)));

        assertTrue(index.contains(0, 0, 0));
        assertTrue(index.contains(15, 15, 15));
        assertFalse(index.contains(16, 15, 15));
        assertFalse(index.contains(0, 16, 0));
        assertFalse(index.contains(-1, 0, 0));
        assertEquals(1, index.sectionBuckets());
    }

    private static boolean insideAnyPiece(List<OutlineBox> pieces, int x, int y, int z) {
        for (OutlineBox piece : pieces) {
            if (x >= piece.minX() && x <= piece.maxX()
                    && y >= piece.minY() && y <= piece.maxY()
                    && z >= piece.minZ() && z <= piece.maxZ()) {
                return true;
            }
        }
        return false;
    }
}
