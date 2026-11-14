package dimblend.dragonchase.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the three blueprints to {@code docs/structures.md}: the cell counts the requirement names (20
 * ore holes, one chest hole, the cars' 12 end-stone cells), the symmetry every drawing has, and the
 * positions the runtime rules address directly (bogey, couplers, the brake car's redstone block and
 * lamp).
 */
class CarPlansTest {

    @Test
    void nestHasTwentyOreHolesAndOneChestOnTheCentrePillar() {
        CarPlans.Plan nest = CarPlans.of(CarKind.NEST);
        assertEquals(11, nest.halfLength() * 2 + 1, "the nest is 11 long");
        assertEquals(7, nest.halfWidth() * 2 + 1, "the nest is 7 wide");
        assertEquals(20, nest.oreCells().size(), "20 矿 cells");
        assertNotNull(nest.chestCell(), "one 箱 cell");
        assertEquals(0, nest.chestCell().dx(), "the chest sits on the centre column");
        assertEquals(4, nest.chestCell().dy(), "layer 5 is dy 4 above the deck");
        assertEquals(0, nest.chestCell().dz());
        assertTrue(nest.isNest());
    }

    @Test
    void nestPillarRisesThroughLayersThreeAndFour() {
        CarPlans.Plan nest = CarPlans.of(CarKind.NEST);
        List<CarPlans.Cell> pillar = nest.all(CarPlans.CellKind.PURPUR_SLAB).stream()
                .filter(cell -> cell.dx() == 0 && cell.dz() == 0)
                .toList();
        assertEquals(List.of(1, 2, 3), pillar.stream().map(CarPlans.Cell::dy).sorted().toList(),
                "the ore-layer centre plus the two pillar layers");
    }

    @Test
    void nestDeckCarriesTheBogeyFourBeamsAndTwoCouplers() {
        CarPlans.Plan nest = CarPlans.of(CarKind.NEST);
        assertEquals(46, nest.parts().size(), "every placed cell");
        assertEquals(4, nest.all(CarPlans.CellKind.BEAM).size());
        assertEquals(39, nest.all(CarPlans.CellKind.PURPUR_SLAB).size());
        assertEquals(2, nest.couplers().size());
        assertEquals(-5, nest.couplers().get(0).dx());
        assertEquals(5, nest.couplers().get(1).dx());
        assertEquals(0, nest.bogey().dx());
        assertEquals(0, nest.bogey().dz());
        assertEquals(0, nest.bogey().dy());
        assertNull(nest.redstone(), "the nest has no brake circuit");
        assertNull(nest.lamp());
        assertEquals(4, nest.topDy(), "the reward layer is inside the plotted box");
    }

    @Test
    void nestPartsNeverIncludeTheRewardHoles() {
        for (CarPlans.Cell cell : CarPlans.of(CarKind.NEST).parts()) {
            assertTrue(cell.kind() != CarPlans.CellKind.ORE && cell.kind() != CarPlans.CellKind.CHEST,
                    "reward holes are left empty at build time: " + cell);
            assertTrue(cell.kind() != CarPlans.CellKind.AIR, "air is not a part: " + cell);
        }
    }

    @Test
    void brakeCarIsThirteenLongWithFourteenRedstoneOreCellsAndSixObsidian() {
        CarPlans.Plan brake = CarPlans.of(CarKind.BRAKE);
        assertEquals(13, brake.halfLength() * 2 + 1);
        assertEquals(29 + 2, brake.parts().size(), "29 deck cells plus the redstone block and the lamp");
        assertEquals(14, brake.all(CarPlans.CellKind.REDSTONE_ORE_SLAB).size());
        assertEquals(6, brake.all(CarPlans.CellKind.OBSIDIAN_SLAB).size());
        assertEquals(6, brake.all(CarPlans.CellKind.BEAM).size());
        assertEquals(2, brake.couplers().size());
        assertTrue(brake.oreCells().isEmpty());
        assertNull(brake.chestCell());
        assertTrue(brake.endCells().isEmpty());
    }

    @Test
    void brakeRedstoneBlockSitsOnTheBogeyAndTheLampAboveIt() {
        CarPlans.Plan brake = CarPlans.of(CarKind.BRAKE);
        CarPlans.Cell bogey = brake.bogey();
        assertNotNull(brake.redstone());
        assertEquals(bogey.dx(), brake.redstone().dx());
        assertEquals(bogey.dz(), brake.redstone().dz());
        assertEquals(bogey.dy() + 1, brake.redstone().dy(), "the bogey reads the signal from above");
        assertNotNull(brake.lamp());
        assertEquals(bogey.dx(), brake.lamp().dx());
        assertEquals(bogey.dz(), brake.lamp().dz());
        assertEquals(bogey.dy() + 2, brake.lamp().dy());
        assertEquals(CarPlans.CellKind.REDSTONE_BLOCK, brake.redstone().kind());
        assertEquals(CarPlans.CellKind.REDSTONE_LAMP, brake.lamp().kind());
        assertEquals(2, brake.topDy(), "the lamp is the highest block");
    }

    @Test
    void crystalCarHasTwelveEndCellsInACheckerBoard() {
        CarPlans.Plan crystal = CarPlans.of(CarKind.CRYSTAL);
        assertEquals(13, crystal.halfLength() * 2 + 1);
        assertEquals(12, crystal.endCells().size(), "12 末 cells");
        assertEquals(12, crystal.all(CarPlans.CellKind.OBSIDIAN_SLAB).size());
        assertEquals(33, crystal.parts().size());
        assertEquals(6, crystal.all(CarPlans.CellKind.BEAM).size());
        assertEquals(2, crystal.couplers().size());
        assertEquals(1, crystal.all(CarPlans.CellKind.BOGEY).size());
        assertNull(crystal.redstone());
        assertEquals(0, crystal.topDy());
        // The checkerboard rows never put two end cells next to each other along Z.
        for (CarPlans.Cell cell : crystal.endCells()) {
            long neighbours = crystal.endCells().stream()
                    .filter(other -> other.dy() == cell.dy()
                            && Math.abs(other.dx() - cell.dx()) + Math.abs(other.dz() - cell.dz()) == 1)
                    .count();
            assertEquals(0, neighbours, "end cells alternate with obsidian: " + cell);
        }
    }

    @Test
    void everyKindHasOneBogeyAndAUniqueCellPerPosition() {
        for (CarKind kind : CarKind.values()) {
            CarPlans.Plan plan = CarPlans.of(kind);
            Set<List<Integer>> seen = new HashSet<>();
            for (CarPlans.Cell cell : plan.parts()) {
                assertTrue(seen.add(List.of(cell.dx(), cell.dy(), cell.dz())),
                        kind + " places two blocks at " + cell);
                assertTrue(Math.abs(cell.dx()) <= plan.halfLength(), kind + " cell outside the length: " + cell);
                assertTrue(Math.abs(cell.dz()) <= plan.halfWidth(), kind + " cell outside the width: " + cell);
                assertTrue(cell.dy() >= 0 && cell.dy() <= plan.topDy(), kind + " cell above the plot box: " + cell);
            }
            assertEquals(1, plan.all(CarPlans.CellKind.BOGEY).size(), kind + " has exactly one bogey");
            assertEquals(2, plan.couplers().size(), kind + " couples at both ends");
        }
    }

    @Test
    void bothFlatCarsShareTheSameBogeyAndCouplerOffsets() {
        CarPlans.Plan brake = CarPlans.of(CarKind.BRAKE);
        CarPlans.Plan crystal = CarPlans.of(CarKind.CRYSTAL);
        assertEquals(brake.bogey(), crystal.bogey());
        assertEquals(brake.couplers(), crystal.couplers());
    }
}
