package dimblend.experience.client.headlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadlightLavaMathTest {
    private static final Vec3 LENS = new Vec3(0, 0, 0);
    private static final Vec3 AXIS = new Vec3(1, 0, 0);
    private static final double RANGE = 24;
    private static final double ANGLE = 50;
    private static final double INTENSITY = 1;

    @Test
    void litFollowsTheBeamConeTheRangeAndTheForwardHalfSpace() {
        assertTrue(HeadlightLavaMath.lit(LENS, AXIS, 10, 0, 0, RANGE, ANGLE, INTENSITY));
        // The shader fades to nothing over the last fifth of the range; 23.5 blocks is already dark.
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 23.5, 0, 0, RANGE, ANGLE, INTENSITY));
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 24, 0, 0, RANGE, ANGLE, INTENSITY));
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 30, 0, 0, RANGE, ANGLE, INTENSITY));
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, -5, 0, 0, RANGE, ANGLE, INTENSITY));
        // 30 degrees off the axis is outside the 25 degree half angle; 10 degrees is inside it.
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 10 * Math.cos(Math.toRadians(30)),
                10 * Math.sin(Math.toRadians(30)), 0, RANGE, ANGLE, INTENSITY));
        assertTrue(HeadlightLavaMath.lit(LENS, AXIS, 10 * Math.cos(Math.toRadians(10)),
                10 * Math.sin(Math.toRadians(10)), 0, RANGE, ANGLE, INTENSITY));
        // A 90 degree full angle is a wider cone: the same 30 degree point is inside.
        assertTrue(HeadlightLavaMath.lit(LENS, AXIS, 10 * Math.cos(Math.toRadians(30)),
                10 * Math.sin(Math.toRadians(30)), 0, RANGE, 90, INTENSITY));
        // Intensity zero turns the highlight off with the light.
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 10, 0, 0, RANGE, ANGLE, 0));
    }

    @Test
    void litTestIsTheShaderPhotometryAboveTheThreshold() {
        for (double distance : new double[] {1, 5, 10, 15, 18, 20, 21, 22, 23.5}) {
            for (double offset : new double[] {0, 1, 3, 5}) {
                double x = distance;
                double y = offset;
                double length = Math.sqrt(x * x + y * y);
                double cosine = x / length;
                double expected = HeadlightMath.cone(cosine, ANGLE)
                        * HeadlightMath.attenuation(length, RANGE, INTENSITY);
                double actual = HeadlightLavaMath.intensity(LENS, AXIS, x, y, 0, RANGE, ANGLE, INTENSITY);
                assertEquals(expected, actual, 1e-12, "distance " + distance + " offset " + offset);
                assertEquals(expected > HeadlightLavaMath.LIT_THRESHOLD,
                        HeadlightLavaMath.lit(LENS, AXIS, x, y, 0, RANGE, ANGLE, INTENSITY),
                        "threshold at distance " + distance + " offset " + offset);
            }
        }
        // A zero-length offset has no direction and a distance at the range is skipped, as in the shader.
        assertEquals(0, HeadlightLavaMath.intensity(LENS, AXIS, 0, 0, 0, RANGE, ANGLE, INTENSITY));
        assertEquals(0, HeadlightLavaMath.intensity(LENS, AXIS, RANGE, 0, 0, RANGE, ANGLE, INTENSITY));
    }

    @Test
    void thresholdSitsWhereTheDefaultBeamIsStillVisible() {
        // Documented choice: 0.04 of the full beam. On the axis of the default beam that is ~22.1 blocks.
        double previous = Double.POSITIVE_INFINITY;
        double reach = 0;
        for (double distance = 0.5; distance < RANGE; distance += 0.01) {
            double value = HeadlightLavaMath.intensity(LENS, AXIS, distance, 0, 0, RANGE, ANGLE, INTENSITY);
            assertTrue(value <= previous + 1e-12, "axial intensity must not rise with distance");
            previous = value;
            if (value > HeadlightLavaMath.LIT_THRESHOLD) {
                reach = distance;
            }
        }
        assertTrue(reach > 21.5 && reach < 22.5, "axial lit reach " + reach);
        // Off axis the soft shoulder trims the outer edge of the cone: at 12 blocks the cone radius is 5.6
        // and the lit width is ~4.9, i.e. the outline hugs the visible pool rather than the geometric cone.
        assertTrue(HeadlightLavaMath.lit(LENS, AXIS, 12, 4.5, 0, RANGE, ANGLE, INTENSITY));
        assertFalse(HeadlightLavaMath.lit(LENS, AXIS, 12, 5.5, 0, RANGE, ANGLE, INTENSITY));
    }

    @Test
    void aSingleTopFaceDrawsItsFourSides() {
        LavaSurface surface = new LavaSurface(5, 64, 7, 8 / 9.0F);
        List<LavaEdge> edges = HeadlightLavaMath.edges(List.of(surface));
        assertEquals(4, edges.size());
        for (LavaEdge edge : edges) {
            assertEquals(surface.outlineY(), edge.y1(), 1e-9);
            assertEquals(surface.outlineY(), edge.y2(), 1e-9);
            assertEquals(1.0, length(edge), 1e-9);
            // Axis aligned and on the block's footprint.
            assertTrue(edge.x1() == 5 || edge.x1() == 6, "x inside the block: " + edge);
            assertTrue(edge.z1() == 7 || edge.z1() == 8, "z inside the block: " + edge);
        }
        double outline = surface.outlineY();
        assertTrue(hasEdge(edges, 5, outline, 7, 5, outline, 8), "west side");
        assertTrue(hasEdge(edges, 6, outline, 7, 6, outline, 8), "east side");
        assertTrue(hasEdge(edges, 5, outline, 7, 6, outline, 7), "north side");
        assertTrue(hasEdge(edges, 5, outline, 8, 6, outline, 8), "south side");
    }

    @Test
    void equalHeightNeighboursHideTheirSharedSides() {
        float height = 8 / 9.0F;
        List<LavaSurface> pair = List.of(new LavaSurface(0, 64, 0, height), new LavaSurface(1, 64, 0, height));
        List<LavaEdge> pairEdges = HeadlightLavaMath.edges(pair);
        assertEquals(6, pairEdges.size());
        assertFalse(hasSharedSide(pairEdges), "two equal-height blocks form one 1x2 region");
        // The two outer sides are still there.
        assertTrue(pairEdges.stream().anyMatch(edge -> edge.x1() == 0 && edge.x2() == 0));
        assertTrue(pairEdges.stream().anyMatch(edge -> edge.x1() == 2 && edge.x2() == 2));

        List<LavaSurface> row = List.of(new LavaSurface(0, 64, 0, height), new LavaSurface(1, 64, 0, height),
                new LavaSurface(2, 64, 0, height));
        assertEquals(8, HeadlightLavaMath.edges(row).size());

        List<LavaSurface> square = List.of(new LavaSurface(0, 64, 0, height), new LavaSurface(1, 64, 0, height),
                new LavaSurface(0, 64, 1, height), new LavaSurface(1, 64, 1, height));
        assertEquals(8, HeadlightLavaMath.edges(square).size());

        // Diagonals do not share a side.
        List<LavaSurface> diagonal = List.of(new LavaSurface(0, 64, 0, height), new LavaSurface(1, 64, 1, height));
        assertEquals(8, HeadlightLavaMath.edges(diagonal).size());
    }

    @Test
    void aStepKeepsBothSidesAtTheirOwnHeight() {
        // Source lava (8/9) next to one level of flowing lava (7/9): different surface heights.
        LavaSurface source = new LavaSurface(0, 64, 0, 8 / 9.0F);
        LavaSurface flowing = new LavaSurface(1, 64, 0, 7 / 9.0F);
        List<LavaEdge> edges = HeadlightLavaMath.edges(List.of(source, flowing));
        assertEquals(8, edges.size());
        Set<Double> heights = new HashSet<>();
        for (LavaEdge edge : edges) {
            heights.add(Math.round(edge.y1() * 1e6) / 1e6);
        }
        assertEquals(2, heights.size(), "each surface draws its sides at its own height");
        assertTrue(edges.stream().anyMatch(edge -> Math.abs(edge.y1() - source.outlineY()) < 1e-9));
        assertTrue(edges.stream().anyMatch(edge -> Math.abs(edge.y1() - flowing.outlineY()) < 1e-9));

        // A neighbour one block lower is not a neighbour at this layer at all.
        assertEquals(8, HeadlightLavaMath.edges(List.of(source, new LavaSurface(1, 63, 0, 8 / 9.0F))).size());
    }

    @Test
    void drawsEdgeComparesHeightsWithinTheDocumentedMargin() {
        assertTrue(HeadlightLavaMath.drawsEdge(false, 0.5F, 0.5F));
        assertFalse(HeadlightLavaMath.drawsEdge(true, 0.5F, 0.5F));
        assertFalse(HeadlightLavaMath.drawsEdge(true, 0.5F, 0.5F + 1e-6F));
        assertTrue(HeadlightLavaMath.drawsEdge(true, 0.5F, 0.5F + 1e-3F));
        assertTrue(HeadlightLavaMath.drawsEdge(true, 8 / 9.0F, 7 / 9.0F));
    }

    @Test
    void capKeepsTheNearestBlocksWhateverTheScanOrder() {
        Vec3 camera = new Vec3(0, 0, 0);
        List<LavaSurface> surfaceList = new ArrayList<>();
        for (int x = 0; x < 60; x++) {
            for (int z = 0; z < 50; z++) {
                surfaceList.add(new LavaSurface(x, 60, z, 8 / 9.0F));
            }
        }
        assertEquals(3000, surfaceList.size());
        List<LavaSurface> capped = HeadlightLavaMath.capped(surfaceList, camera, 1024);
        assertEquals(1024, capped.size());
        double farthestKept = capped.stream().mapToDouble(surface -> distanceSquared(surface, camera)).max().orElseThrow();
        double nearestDropped = surfaceList.stream()
                .filter(surface -> !capped.contains(surface))
                .mapToDouble(surface -> distanceSquared(surface, camera)).min().orElseThrow();
        assertTrue(farthestKept <= nearestDropped + 1e-9, "the cap must drop the far end, not the near end");

        // Same scene, different scan order: identical set.
        List<LavaSurface> shuffled = new ArrayList<>(surfaceList);
        Collections.shuffle(shuffled, new java.util.Random(7));
        assertEquals(new HashSet<>(capped), new HashSet<>(HeadlightLavaMath.capped(shuffled, camera, 1024)));

        // Under the limit nothing is dropped and the list is returned as-is.
        List<LavaSurface> small = List.of(new LavaSurface(0, 60, 0, 8 / 9.0F));
        assertSame(small, HeadlightLavaMath.capped(small, camera, 1024));
        assertEquals(0, HeadlightLavaMath.capped(List.of(), camera, 1024).size());
    }

    @Test
    void alphaPulsesOncePerSecondWithinAGentleBand() {
        assertEquals(0.55F, HeadlightLavaMath.alpha(0), 1e-5);
        assertEquals(0.75F, HeadlightLavaMath.alpha(5), 1e-5);
        assertEquals(0.55F, HeadlightLavaMath.alpha(10), 1e-5);
        assertEquals(0.35F, HeadlightLavaMath.alpha(15), 1e-5);
        assertEquals(HeadlightLavaMath.alpha(0), HeadlightLavaMath.alpha(20), 1e-6);
        float previous = HeadlightLavaMath.alpha(0);
        for (double tick = 0; tick <= 40; tick += 0.25) {
            float alpha = HeadlightLavaMath.alpha(tick);
            assertTrue(alpha >= 0.35F - 1e-5 && alpha <= 0.75F + 1e-5, "alpha " + alpha + " at tick " + tick);
            previous = alpha;
        }
        assertTrue(previous > 0);
    }

    @Test
    void outlineSitsJustAboveTheFluidSurface() {
        LavaSurface surface = new LavaSurface(-3, 40, 9, 8 / 9.0F);
        // The fluid height is a float (FluidState.getOwnHeight), so the expected value goes through float too.
        assertEquals(40 + (double) (8 / 9.0F) + HeadlightLavaMath.SURFACE_LIFT, surface.outlineY(), 1e-12);
        // The lift is small: the line rides the top face, it is not a floating box above it.
        assertTrue(HeadlightLavaMath.SURFACE_LIFT > 0 && HeadlightLavaMath.SURFACE_LIFT <= 0.02);
    }

    private static double length(LavaEdge edge) {
        double dx = edge.x2() - edge.x1();
        double dy = edge.y2() - edge.y1();
        double dz = edge.z2() - edge.z1();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceSquared(LavaSurface surface, Vec3 camera) {
        double dx = surface.centerX() - camera.x;
        double dy = surface.centerY() - camera.y;
        double dz = surface.centerZ() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Whether a lit block pair at x = 0 / x = 1 draws a segment on the plane between them. */
    private static boolean hasSharedSide(List<LavaEdge> edges) {
        return edges.stream().anyMatch(edge -> close(edge.x1(), 1) && close(edge.x2(), 1));
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
