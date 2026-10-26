package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LavaBoxOutlineTest {
    private static final float SOURCE = 8 / 9.0F;

    @Test
    void aLoneBlockIsATwoBlockBoxCentredOnIt() {
        List<LavaEdge> edges = LavaBoxOutline.edges(List.of(new LavaSurface(5, 64, 7, SOURCE)));
        assertEquals(12, edges.size());
        for (LavaEdge edge : edges) {
            assertEquals(2.0, length(edge), 1e-9, "every side of the box is two blocks: " + edge);
        }
        // The block [5,6] x [64,65] x [7,8] grown by half a block on every side.
        assertBounds(edges, 4.5, 63.5, 6.5, 6.5, 65.5, 8.5);
        assertTrue(hasEdge(edges, 4.5, 63.5, 6.5, 6.5, 63.5, 6.5), "bottom north edge");
        assertTrue(hasEdge(edges, 4.5, 65.5, 8.5, 6.5, 65.5, 8.5), "top south edge");
        assertTrue(hasEdge(edges, 6.5, 63.5, 8.5, 6.5, 65.5, 8.5), "south-east upright");
    }

    @Test
    void touchingBlocksShareOneBoxWithNoInnerLines() {
        List<LavaEdge> pair = LavaBoxOutline.edges(List.of(new LavaSurface(0, 64, 0, SOURCE),
                new LavaSurface(1, 64, 0, SOURCE)));
        assertEquals(12, pair.size(), "two side-by-side blocks are one 3x2x2 box");
        assertBounds(pair, -0.5, 63.5, -0.5, 2.5, 65.5, 1.5);
        assertEquals(4, pair.stream().filter(edge -> close(length(edge), 3.0)).count());
        assertFalse(pair.stream().anyMatch(edge -> close(edge.x1(), 0.5) && close(edge.x2(), 0.5)
                        || close(edge.x1(), 1.5) && close(edge.x2(), 1.5)),
                "no segment on the planes inside the merged box");

        List<LavaSurface> pool = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                pool.add(new LavaSurface(x, 64, z, SOURCE));
            }
        }
        List<LavaEdge> poolEdges = LavaBoxOutline.edges(pool);
        assertEquals(12, poolEdges.size(), "a rectangular pool is one box");
        assertBounds(poolEdges, -0.5, 63.5, -0.5, 5.5, 65.5, 5.5);
    }

    @Test
    void aOneBlockGapIsBridgedBecauseTheBoxesTouch() {
        // [-0.5, 1.5] and [1.5, 3.5] meet on x = 1.5: the outline is the merged 4x2x2 box.
        List<LavaEdge> edges = LavaBoxOutline.edges(List.of(new LavaSurface(0, 64, 0, SOURCE),
                new LavaSurface(2, 64, 0, SOURCE)));
        assertEquals(12, edges.size());
        assertBounds(edges, -0.5, 63.5, -0.5, 3.5, 65.5, 1.5);
        // Two blocks of gap leave two separate boxes.
        assertEquals(24, LavaBoxOutline.edges(List.of(new LavaSurface(0, 64, 0, SOURCE),
                new LavaSurface(3, 64, 0, SOURCE))).size());
    }

    @Test
    void diagonalBlocksMergeIntoOneSteppedShell() {
        // Footprint: two overlapping 2x2 squares, an eight-cornered outline extruded two blocks.
        List<LavaEdge> edges = LavaBoxOutline.edges(List.of(new LavaSurface(0, 64, 0, SOURCE),
                new LavaSurface(1, 64, 1, SOURCE)));
        assertEquals(24, edges.size());
        assertBounds(edges, -0.5, 63.5, -0.5, 2.5, 65.5, 2.5);
        // Concave corner at (1.5, 0.5): an upright where three of the four cells around it are filled.
        assertTrue(hasEdge(edges, 1.5, 63.5, 0.5, 1.5, 65.5, 0.5));
        assertTrue(hasEdge(edges, 0.5, 63.5, 1.5, 0.5, 65.5, 1.5));
    }

    @Test
    void aFlowingStepFollowsBothLayers() {
        // Source at y 64 next to lava one block lower (a step down a slope).
        List<LavaEdge> edges = LavaBoxOutline.edges(List.of(new LavaSurface(0, 64, 0, SOURCE),
                new LavaSurface(1, 63, 0, 7 / 9.0F)));
        assertEquals(24, edges.size(), "an L-shaped cross-section extruded along z");
        assertBounds(edges, -0.5, 62.5, -0.5, 2.5, 65.5, 1.5);
        // The step's two concave corners: three of the four cells around each of these z edges are filled.
        assertTrue(hasEdge(edges, 1.5, 64.5, -0.5, 1.5, 64.5, 1.5));
        assertTrue(hasEdge(edges, 0.5, 63.5, -0.5, 0.5, 63.5, 1.5));
    }

    @Test
    void everySegmentIsAxisAlignedOnTheHalfBlockGrid() {
        Random random = new Random(11);
        List<LavaSurface> scattered = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            scattered.add(new LavaSurface(random.nextInt(20) - 10, 60 + random.nextInt(4),
                    random.nextInt(20) - 10, SOURCE));
        }
        List<LavaEdge> edges = LavaBoxOutline.edges(scattered);
        assertFalse(edges.isEmpty());
        for (LavaEdge edge : edges) {
            int changed = (close(edge.x1(), edge.x2()) ? 0 : 1) + (close(edge.y1(), edge.y2()) ? 0 : 1)
                    + (close(edge.z1(), edge.z2()) ? 0 : 1);
            assertEquals(1, changed, "axis aligned: " + edge);
            for (double value : new double[] {edge.x1(), edge.y1(), edge.z1(), edge.x2(), edge.y2(), edge.z2()}) {
                assertTrue(close(value - Math.floor(value), 0.5), "on a block centre plane: " + edge);
            }
        }
        // Order of the scan does not change the outline; duplicates do not either.
        List<LavaSurface> shuffled = new ArrayList<>(scattered);
        shuffled.addAll(scattered.subList(0, 30));
        Collections.shuffle(shuffled, new Random(3));
        assertEquals(normalized(edges), normalized(LavaBoxOutline.edges(shuffled)));
    }

    @Test
    void ringRuleDrawsCornersAndCreasesButNotFacesOrInteriors() {
        assertFalse(LavaBoxOutline.outlineRing(false, false, false, false), "empty");
        assertFalse(LavaBoxOutline.outlineRing(true, true, true, true), "interior");
        assertTrue(LavaBoxOutline.outlineRing(true, false, false, false), "convex corner");
        assertTrue(LavaBoxOutline.outlineRing(false, false, false, true), "convex corner");
        assertTrue(LavaBoxOutline.outlineRing(false, true, true, true), "concave corner");
        assertTrue(LavaBoxOutline.outlineRing(true, true, true, false), "concave corner");
        assertFalse(LavaBoxOutline.outlineRing(true, true, false, false), "flat face");
        assertFalse(LavaBoxOutline.outlineRing(true, false, true, false), "flat face");
        assertFalse(LavaBoxOutline.outlineRing(false, true, false, true), "flat face");
        assertFalse(LavaBoxOutline.outlineRing(false, false, true, true), "flat face");
        assertTrue(LavaBoxOutline.outlineRing(true, false, false, true), "diagonal crease");
        assertTrue(LavaBoxOutline.outlineRing(false, true, true, false), "diagonal crease");
    }

    @Test
    void nothingLitDrawsNothing() {
        assertEquals(0, LavaBoxOutline.edges(List.of()).size());
    }

    private static void assertBounds(List<LavaEdge> edges, double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
        double lowX = Double.MAX_VALUE, lowY = Double.MAX_VALUE, lowZ = Double.MAX_VALUE;
        double highX = -Double.MAX_VALUE, highY = -Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
        for (LavaEdge edge : edges) {
            lowX = Math.min(lowX, Math.min(edge.x1(), edge.x2()));
            lowY = Math.min(lowY, Math.min(edge.y1(), edge.y2()));
            lowZ = Math.min(lowZ, Math.min(edge.z1(), edge.z2()));
            highX = Math.max(highX, Math.max(edge.x1(), edge.x2()));
            highY = Math.max(highY, Math.max(edge.y1(), edge.y2()));
            highZ = Math.max(highZ, Math.max(edge.z1(), edge.z2()));
        }
        assertEquals(minX, lowX, 1e-9);
        assertEquals(minY, lowY, 1e-9);
        assertEquals(minZ, lowZ, 1e-9);
        assertEquals(maxX, highX, 1e-9);
        assertEquals(maxY, highY, 1e-9);
        assertEquals(maxZ, highZ, 1e-9);
    }

    private static Set<String> normalized(List<LavaEdge> edges) {
        Set<String> set = new HashSet<>();
        for (LavaEdge edge : edges) {
            String a = edge.x1() + "," + edge.y1() + "," + edge.z1();
            String b = edge.x2() + "," + edge.y2() + "," + edge.z2();
            set.add(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
        }
        assertEquals(edges.size(), set.size(), "no segment is emitted twice");
        return set;
    }

    private static double length(LavaEdge edge) {
        double dx = edge.x2() - edge.x1();
        double dy = edge.y2() - edge.y1();
        double dz = edge.z2() - edge.z1();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean hasEdge(List<LavaEdge> edges, double x1, double y1, double z1,
                                   double x2, double y2, double z2) {
        for (LavaEdge edge : edges) {
            if ((close(edge.x1(), x1) && close(edge.y1(), y1) && close(edge.z1(), z1)
                    && close(edge.x2(), x2) && close(edge.y2(), y2) && close(edge.z2(), z2))
                    || (close(edge.x2(), x1) && close(edge.y2(), y1) && close(edge.z2(), z1)
                    && close(edge.x1(), x2) && close(edge.y1(), y2) && close(edge.z1(), z2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 1e-9;
    }
}
