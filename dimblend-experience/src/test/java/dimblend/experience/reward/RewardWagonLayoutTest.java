package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The 9 x 3 deck, read straight off the requirement's drawing. */
class RewardWagonLayoutTest {

    @Test
    void theDeckIsNineLongAndThreeWide() {
        assertEquals(9, RewardWagonLayout.LENGTH);
        assertEquals(3, RewardWagonLayout.WIDTH);
        assertEquals(RewardWagonLayout.LENGTH * RewardWagonLayout.WIDTH, RewardWagonLayout.cells().size());
    }

    @Test
    void theStructureIsTwentyThreeBlocks() {
        // 21 slabs (2 x 8 middle-column-ish rows + the body) plus two couplers plus one bogey.
        assertEquals(23, RewardWagonLayout.structure().size());
    }

    @Test
    void theOreGroupsAreTenSixAndOne() {
        assertEquals(10, RewardWagonLayout.marked(1).size(), "group 1");
        assertEquals(6, RewardWagonLayout.marked(2).size(), "group 2");
        assertEquals(1, RewardWagonLayout.marked(3).size(), "group 3");
        assertEquals(17, RewardWagonLayout.marked(1).size()
                + RewardWagonLayout.marked(2).size()
                + RewardWagonLayout.marked(3).size(), "total marked cells");
    }

    @Test
    void groupThreeIsTheBogeyCell() {
        RewardWagonLayout.Cell bogey = RewardWagonLayout.bogey();
        List<RewardWagonLayout.Cell> three = RewardWagonLayout.marked(3);
        assertEquals(1, three.size());
        assertEquals(bogey.dx(), three.get(0).dx());
        assertEquals(bogey.dz(), three.get(0).dz());
        assertEquals(RewardWagonLayout.CellKind.BOGEY, bogey.kind());
    }

    @Test
    void theTwoCouplerRowsHoldNothingButTheCoupler() {
        for (int row : new int[] {0, 8}) {
            int dx = row - RewardWagonLayout.HALF_LENGTH;
            for (RewardWagonLayout.Cell cell : RewardWagonLayout.cells()) {
                if (cell.dx() != dx) {
                    continue;
                }
                if (cell.dz() == 0) {
                    assertEquals(RewardWagonLayout.CellKind.COUPLER, cell.kind(),
                            "middle column of row " + row);
                } else {
                    assertEquals(RewardWagonLayout.CellKind.AIR, cell.kind(),
                            "side column of row " + row);
                }
            }
        }
    }

    @Test
    void rowsOneAndSevenAreDoubleSlabsInTheMiddle() {
        for (int row : new int[] {1, 7}) {
            int dx = row - RewardWagonLayout.HALF_LENGTH;
            for (RewardWagonLayout.Cell cell : RewardWagonLayout.cells()) {
                if (cell.dx() != dx) {
                    continue;
                }
                assertEquals(cell.dz() == 0
                        ? RewardWagonLayout.CellKind.DOUBLE_SLAB
                        : RewardWagonLayout.CellKind.SLAB, cell.kind(), "row " + row + " dz " + cell.dz());
            }
        }
    }

    @Test
    void theWholeMiddleColumnIsOpaqueExceptTheCouplerRows() {
        for (RewardWagonLayout.Cell cell : RewardWagonLayout.cells()) {
            if (cell.dz() != 0) {
                continue;
            }
            boolean endRow = Math.abs(cell.dx()) == RewardWagonLayout.HALF_LENGTH;
            if (endRow) {
                assertEquals(RewardWagonLayout.CellKind.COUPLER, cell.kind());
            } else {
                assertTrue(cell.kind() == RewardWagonLayout.CellKind.SLAB
                        || cell.kind() == RewardWagonLayout.CellKind.DOUBLE_SLAB
                        || cell.kind() == RewardWagonLayout.CellKind.BOGEY,
                        "middle column at dx " + cell.dx());
            }
        }
    }

    @Test
    void theMotorTrainSitsWestOfTheBogeyInOrder() {
        RewardWagonLayout.MotorParts parts = RewardWagonLayout.motorParts();
        RewardWagonLayout.Cell bogey = RewardWagonLayout.bogey();
        // Motor, then clutch, then the bogey: the power runs west to east.
        assertEquals(bogey.dx() - 2, parts.motor().dx(), "motor is two west of the bogey");
        assertEquals(bogey.dx() - 1, parts.clutch().dx(), "clutch is one west of the bogey");
        assertEquals(bogey.dz(), parts.motor().dz());
        assertEquals(bogey.dz(), parts.clutch().dz());
        assertEquals(bogey.dz(), parts.lever().dz(), "the lever stays on the clutch's column");
        assertEquals(parts.clutch().dx(), parts.lever().dx(), "the lever is above the clutch");
        assertTrue(parts.motor().dx() < parts.clutch().dx());
        assertTrue(parts.clutch().dx() < bogey.dx());
    }

    @Test
    void theLeverSitsOneLayerAboveTheDeck() {
        assertEquals(RewardWagonLayout.CONTENT_DY - RewardWagonLayout.STRUCTURE_DY,
                RewardWagonLayout.LEVER_DY, "the lever is on the content layer");
    }

    @Test
    void theMotorTrainCellsAreOreMarkersTwo() {
        // Both deck cells the motor and clutch occupy are marked 2 in the drawing, so they are
        // part of the six the ore procedure treats as group 2 — nothing needs a new marker.
        RewardWagonLayout.MotorParts parts = RewardWagonLayout.motorParts();
        for (RewardWagonLayout.Cell cell : new RewardWagonLayout.Cell[] {parts.motor(), parts.clutch()}) {
            assertTrue(RewardWagonLayout.marked(2).stream()
                    .anyMatch(marked -> marked.dx() == cell.dx() && marked.dz() == cell.dz()),
                    "cell " + cell.dx() + "," + cell.dz() + " is not marked 2");
        }
    }

    @Test
    void theContentLayerIsOneAboveTheStructureAndOreOneAboveThat() {
        assertEquals(RewardWagonLayout.STRUCTURE_DY + 1, RewardWagonLayout.CONTENT_DY);
        assertEquals(RewardWagonLayout.CONTENT_DY + 1, RewardWagonLayout.ORE_TOP_DY);
    }

    @Test
    void airCellsCarryNoOreMarker() {
        for (RewardWagonLayout.Cell cell : RewardWagonLayout.cells()) {
            if (cell.kind() == RewardWagonLayout.CellKind.AIR) {
                assertEquals(0, cell.marker(), "air cell at " + cell.dx() + "," + cell.dz());
            }
        }
    }

    @Test
    void thereIsExactlyOneBogeyAndTwoCouplers() {
        assertEquals(1, RewardWagonLayout.cells().stream()
                .filter(cell -> cell.kind() == RewardWagonLayout.CellKind.BOGEY).count());
        assertEquals(2, RewardWagonLayout.couplers().size());
    }

    @Test
    void everyCellStaysInsideTheDeckBounds() {
        for (RewardWagonLayout.Cell cell : RewardWagonLayout.cells()) {
            assertTrue(Math.abs(cell.dx()) <= RewardWagonLayout.HALF_LENGTH,
                    "dx " + cell.dx() + " out of bounds");
            assertTrue(Math.abs(cell.dz()) <= RewardWagonLayout.HALF_WIDTH,
                    "dz " + cell.dz() + " out of bounds");
        }
    }
}