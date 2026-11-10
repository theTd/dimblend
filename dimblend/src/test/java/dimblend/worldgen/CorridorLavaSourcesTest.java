package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.worldgen.CorridorLavaPlan.Cell;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CorridorLavaSourcesTest {
    @Test
    void sourceBoundsIncludeBothZEdgesAndExcludeY66() {
        for (int z : new int[]{-64, 0, 64}) {
            assertTrue(CorridorLavaPlan.inSourceRange(67, z));
            assertTrue(CorridorLavaPlan.inSourceRange(319, z));
            assertFalse(CorridorLavaPlan.inSourceRange(66, z));
        }
        assertFalse(CorridorLavaPlan.inSourceRange(100, -65));
        assertFalse(CorridorLavaPlan.inSourceRange(100, 65));
    }

    @Test
    void outputRangeIncludesGlassOverhangInBothDirections() {
        assertTrue(CorridorLavaPlan.touchesOutput(64, 79));
        assertTrue(CorridorLavaPlan.touchesOutput(-80, -65));
        assertFalse(CorridorLavaPlan.touchesOutput(80, 95));
        assertFalse(CorridorLavaPlan.touchesOutput(-96, -81));
    }

    @Test
    void oneSourceClearsExactlyItsColumnDownTo66AndNeverItself() {
        Cell source = new Cell(-17, 70, 64);
        CorridorLavaPlan plan = new CorridorLavaPlan();
        List<Cell> air = List.of(source.offset(1, 0, 0), source.offset(0, 0, 1));
        plan.openSource(source, air);
        assertEquals(Set.of(source.offset(0, -1, 0), source.offset(0, -2, 0),
                source.offset(0, -3, 0), source.offset(0, -4, 0)), clears(plan));
        assertEquals(Set.copyOf(air), glass(plan));
        assertFalse(plan.clears(source));
        assertFalse(plan.clears(source.offset(1, -1, 0)));
        assertFalse(plan.clears(new Cell(-17, 65, 64)));
    }

    @Test
    void lowestEligibleSourceOnlyClearsY66() {
        CorridorLavaPlan plan = new CorridorLavaPlan();
        plan.openSource(new Cell(15, 67, -64), List.of());
        assertEquals(Set.of(new Cell(15, 66, -64)), clears(plan));
    }

    @Test
    void stackedSourcesAreBothPlannedButUpperShaftDeletesLowerSource() {
        Cell upper = new Cell(0, 75, 0);
        Cell lower = new Cell(0, 70, 0);
        CorridorLavaPlan plan = new CorridorLavaPlan();
        plan.openSource(upper, List.of(upper.offset(1, 0, 0)));
        plan.openSource(lower, List.of(lower.offset(-1, 0, 0)));
        assertTrue(plan.clears(lower), "lower source has no exemption from the upper shaft");
        assertEquals(9, clears(plan).size());
        assertEquals(Set.of(upper.offset(1, 0, 0), lower.offset(-1, 0, 0)), glass(plan),
                "the lower source's original horizontal-air decision must still apply");
    }

    @Test
    void shaftAlwaysWinsOverAnotherSourcesGlassRegardlessOfPlanOrder() {
        Cell upper = new Cell(15, 75, 0);
        Cell side = new Cell(16, 70, 0);
        CorridorLavaPlan forward = overlap(upper, side, false);
        CorridorLavaPlan reverse = overlap(upper, side, true);
        assertEquals(clears(forward), clears(reverse));
        assertEquals(glass(forward), glass(reverse));
        assertFalse(glass(forward).contains(new Cell(15, 70, 0)));
        assertTrue(forward.clears(new Cell(15, 70, 0)));
    }

    @Test
    void crossChunkGlassAndNegativeChunkOwnershipHaveNoClipping() {
        for (int x : new int[]{-17, -16, -1, 0, 15, 16}) {
            Cell source = new Cell(x, 70, 0);
            List<Cell> air = List.of(source.offset(-1, 0, 0), source.offset(1, 0, 0));
            CorridorLavaPlan plan = new CorridorLavaPlan();
            plan.openSource(source, air);
            assertEquals(Set.copyOf(air), glass(plan));
            Map<Integer, Set<Cell>> byChunk = new HashMap<>();
            plan.forEachGlass(cell -> byChunk.computeIfAbsent(Math.floorDiv(cell.x(), 16),
                    ignored -> new HashSet<>()).add(cell));
            for (Cell cell : air) assertTrue(byChunk.get(Math.floorDiv(cell.x(), 16)).contains(cell));
        }
    }

    @Test
    void enclosedSourceWithoutOperationLeavesEverythingAlone() {
        CorridorLavaPlan plan = new CorridorLavaPlan();
        assertTrue(clears(plan).isEmpty());
        assertTrue(glass(plan).isEmpty());
    }

    private static CorridorLavaPlan overlap(Cell upper, Cell side, boolean reverse) {
        CorridorLavaPlan plan = new CorridorLavaPlan();
        if (reverse) plan.openSource(side, List.of(side.offset(-1, 0, 0)));
        plan.openSource(upper, List.of(upper.offset(1, 0, 0)));
        if (!reverse) plan.openSource(side, List.of(side.offset(-1, 0, 0)));
        return plan;
    }

    private static Set<Cell> clears(CorridorLavaPlan plan) {
        Set<Cell> result = new HashSet<>();
        plan.forEachClear(result::add);
        return result;
    }

    private static Set<Cell> glass(CorridorLavaPlan plan) {
        Set<Cell> result = new HashSet<>();
        plan.forEachGlass(result::add);
        return result;
    }
}
