package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
    void hiddenEdgesUseFortyPercentOfThePulseAlpha() {
        assertEquals(0.4F, HeadlightLavaMath.HIDDEN_ALPHA_FACTOR, 1e-6F);
        for (float alpha : new float[] {0.0F, 0.35F, 0.55F, 0.75F, 1.0F}) {
            assertEquals(HeadlightLavaMath.HIDDEN_ALPHA_FACTOR * alpha, HeadlightLavaMath.hiddenAlpha(alpha), 1e-6F,
                    "pulse alpha " + alpha);
        }
        // The default pulse is 0.55 at tick 0 and 0.75 at its peak; the see-through pass is 40% of each.
        assertEquals(0.22F, HeadlightLavaMath.hiddenAlpha(HeadlightLavaMath.alpha(0)), 1e-6F);
        assertEquals(0.30F, HeadlightLavaMath.hiddenAlpha(HeadlightLavaMath.alpha(5)), 1e-6F);
        float full = HeadlightLavaMath.alpha(10);
        float dim = HeadlightLavaMath.hiddenAlpha(full);
        assertTrue(dim > 0.0F && dim < full, "hidden edges stay visible but dimmer: " + dim + " vs " + full);
    }

    private static double distanceSquared(LavaSurface surface, Vec3 camera) {
        double dx = surface.centerX() - camera.x;
        double dy = surface.centerY() - camera.y;
        double dz = surface.centerZ() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
