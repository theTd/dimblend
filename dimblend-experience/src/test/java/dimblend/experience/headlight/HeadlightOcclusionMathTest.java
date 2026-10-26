package dimblend.experience.headlight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeadlightOcclusionMathTest {
    private static final double EPS = 1e-9;

    private static HeadlightOcclusionMath.Occupancy cells(int[]... occupied) {
        Set<Long> set = new HashSet<>();
        for (int[] c : occupied) set.add(key(c[0], c[1], c[2]));
        return (x, y, z) -> set.contains(key(x, y, z));
    }

    private static long key(int x, int y, int z) { return ((long) x << 40) ^ ((long) y << 20) ^ z; }

    @Test
    void occluderPredicate() {
        assertTrue(HeadlightOcclusionMath.occludes(false, true, 1.0));      // stone
        assertTrue(HeadlightOcclusionMath.occludes(false, true, 0.75));     // stairs
        assertFalse(HeadlightOcclusionMath.occludes(false, true, 0.5));     // slab
        assertFalse(HeadlightOcclusionMath.occludes(false, false, 1.0));    // glass, leaves, fluids
        assertFalse(HeadlightOcclusionMath.occludes(true, true, 1.0));      // the lamp itself
    }

    @Test
    void toroidalWrapHandlesNegativeCoordinates() {
        assertEquals(0, HeadlightOcclusionMath.wrap(0, 128));
        assertEquals(127, HeadlightOcclusionMath.wrap(-1, 128));
        assertEquals(1, HeadlightOcclusionMath.wrap(129, 128));
        assertEquals(0, HeadlightOcclusionMath.wrap(-128, 128));
        assertEquals(63, HeadlightOcclusionMath.wrap(-65, 64));
        // Every world coordinate in a window of `size` maps to a distinct texel.
        for (int origin : new int[]{-300, -16, 0, 48, 1000}) {
            Set<Integer> seen = new HashSet<>();
            for (int w = origin; w < origin + 64; w++) assertTrue(seen.add(HeadlightOcclusionMath.wrap(w, 64)));
        }
    }

    @Test
    void windowKeepsCameraNearItsCentre() {
        for (double camera = -200; camera < 200; camera += 0.37) {
            int origin = HeadlightOcclusionMath.windowOriginBrick(camera, 8) * 16;
            assertTrue(camera - origin >= 56 - EPS && camera - origin <= 72 + EPS, "x=" + camera);
            int originY = HeadlightOcclusionMath.windowOriginBrick(camera, 4) * 16;
            assertTrue(camera - originY >= 24 - EPS && camera - originY <= 40 + EPS, "y=" + camera);
        }
    }

    @Test
    void rayAlongAxisHitsFaceOfFirstOccupiedCell() {
        var grid = cells(new int[]{5, 2, 2});
        double t = HeadlightOcclusionMath.trace(0.5, 2.5, 2.5, 10.5, 2.5, 2.5, 16, 16, 16, grid);
        assertEquals(0.45, t, EPS); // enters x = 5 at fraction 4.5 / 10
        // Negative direction: enters the cell's +x face at x = 6.
        double back = HeadlightOcclusionMath.trace(10.5, 2.5, 2.5, 0.5, 2.5, 2.5, 16, 16, 16, grid);
        assertEquals(0.45, back, EPS);
    }

    @Test
    void startCellIsSkippedButNextCellIsNot() {
        var grid = cells(new int[]{3, 3, 3}, new int[]{4, 3, 3});
        double t = HeadlightOcclusionMath.trace(3.5, 3.5, 3.5, 7.5, 3.5, 3.5, 8, 8, 8, grid);
        assertEquals(0.5 / 4.0, t, EPS);
        var only = cells(new int[]{3, 3, 3});
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(3.5, 3.5, 3.5, 7.5, 3.5, 3.5, 8, 8, 8, only));
    }

    @Test
    void segmentStartingOutsideTestsTheEntryCell() {
        var grid = cells(new int[]{0, 1, 1});
        double t = HeadlightOcclusionMath.trace(-2.0, 1.5, 1.5, 2.0, 1.5, 1.5, 4, 4, 4, grid);
        assertEquals(0.5, t, EPS);
    }

    @Test
    void exitsTheGridWithoutHit() {
        var grid = cells(new int[]{9, 0, 0});
        // Occupied cell lies beyond the grid box (size 8): never visible.
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(1.5, 0.5, 0.5, 20.5, 0.5, 0.5, 8, 8, 8, grid));
        // Entirely outside the box.
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(-5, -5, -5, -1, -1, -1, 8, 8, 8, grid));
        // Parallel to an axis outside the slab.
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(1, 9, 1, 7, 9, 1, 8, 8, 8, grid));
    }

    @Test
    void segmentEndingBeforeTheOccluderMisses() {
        var grid = cells(new int[]{6, 0, 0});
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(0.5, 0.5, 0.5, 5.5, 0.5, 0.5, 8, 8, 8, grid));
    }

    @Test
    void diagonalRayVisitsEdgeConnectedCellsWithoutSkipping() {
        // A 1x1 pillar at (3, *, 3) must stop a 45 degree ray in the xz plane.
        var grid = cells(new int[]{3, 0, 3});
        double t = HeadlightOcclusionMath.trace(0.5, 0.5, 0.5, 6.5, 0.5, 6.5, 8, 8, 8, grid);
        assertEquals(2.5 / 6.0, t, 1e-6);
        // A ray passing beside the pillar is not blocked.
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(0.5, 0.5, 2.5, 6.5, 0.5, 2.5, 8, 8, 8, grid));
    }

    @Test
    void shallowGrazingRayOverFloorDoesNotHitFloor() {
        // Floor at y = 0 (cells y = 0), lens 0.5 above its top: a ray rising slightly stays clear.
        HeadlightOcclusionMath.Occupancy floor = (x, y, z) -> y == 0;
        assertEquals(HeadlightOcclusionMath.NO_HIT,
                HeadlightOcclusionMath.trace(0.5, 1.5, 0.5, 30.5, 1.6, 0.5, 32, 8, 8, floor));
        // Descending at 0.5 / 10 blocks hits the floor top face after 10 blocks.
        double t = HeadlightOcclusionMath.trace(0.5, 1.5, 0.5, 20.5, 0.5, 0.5, 32, 8, 8, floor);
        assertEquals(0.5, t, 1e-6);
    }
}
