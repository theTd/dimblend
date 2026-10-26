package dimblend.experience.headlight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The server half of the observer blaze: one scan shared by every observer, one distance gate per observer. The
 * client draws what {@link HeadlightLavaScan#scan} collected ({@link HeadlightLavaMath#lit}); the server scans once
 * per recompute window and answers each observer with {@link HeadlightLavaMath#anyWithin}. These tests pin that
 * gate, the lamp pre-filter and the shared constants, so the two sides cannot drift apart silently.
 */
class ObserverBlazeRadiusTest {
    private static final Vec3 LENS = new Vec3(0, 10, 0);
    private static final Vec3 DOWN = new Vec3(0, -1, 0);
    private static final double RANGE = 24;
    private static final double ANGLE = 50;
    private static final double INTENSITY = 1;

    @Test
    void markedLavaIsExactlyLitLavaInsideTheRadius() {
        // A source lava block two blocks under the beam axis, surface at 8/9: lit by the shared test.
        int x = 0;
        int y = 1;
        int z = 0;
        float height = 8 / 9.0F;
        double surfaceY = y + height;
        assertTrue(HeadlightLavaMath.lit(LENS, DOWN, x + 0.5, surfaceY, z + 0.5, RANGE, ANGLE, INTENSITY),
                "the sample point must be lit for the composition test to mean anything");

        // Within the radius: marked. Outside it: not marked, however lit it is.
        Vec3 near = new Vec3(x + 0.5, surfaceY + 3.0, z + 0.5);
        assertTrue(HeadlightLavaMath.withinRadius(x, y, z, height, near, 4.0));
        Vec3 far = new Vec3(x + 0.5, surfaceY, z + 0.5 + 30.0);
        assertFalse(HeadlightLavaMath.withinRadius(x, y, z, height, far, 24.0));
        // A point beyond the beam's reach is never marked even when it is close.
        assertFalse(HeadlightLavaMath.lit(LENS, DOWN, x + 0.5, -20.5, z + 0.5, RANGE, ANGLE, INTENSITY));
    }

    @Test
    void radiusIsEuclideanFromTheTopFaceCentreAndIncludesTheFluidHeight() {
        float source = 8 / 9.0F;
        float flowing = 7 / 9.0F;
        Vec3 center = new Vec3(0.5, 64.5, 0.5);
        // The centre of the top face, not the block centre: a source face is 8/9 above the block base.
        assertEquals(0.0, HeadlightLavaMath.surfaceDistanceSqr(0, 64, 0, source, new Vec3(0.5, 64.0 + source, 0.5)), 1e-12);
        assertTrue(HeadlightLavaMath.withinRadius(0, 64, 0, source, center, 1.0));
        assertTrue(HeadlightLavaMath.withinRadius(3, 64, 0, source, new Vec3(0.5, 64.0 + source, 0.5), 3.0),
                "exactly on the boundary counts");
        assertFalse(HeadlightLavaMath.withinRadius(1, 64, 0, source, new Vec3(0.5, 64.0 + source, 0.5), 0.999));
        // The same block coordinates with a different fluid height are a different surface height.
        double sourceGap = HeadlightLavaMath.surfaceDistanceSqr(0, 64, 0, source, new Vec3(0.5, 64.0 + flowing, 0.5));
        assertTrue(sourceGap > 0.0);
        assertTrue(HeadlightLavaMath.withinRadius(0, 64, 0, source, new Vec3(0.5, 64.0 + flowing, 0.5), 0.2));
        // Off-axis distance is real 3D distance, not axis-aligned.
        assertTrue(HeadlightLavaMath.withinRadius(1, 64, 1, source, center, 1.5));
        assertFalse(HeadlightLavaMath.withinRadius(1, 64, 1, source, center, 1.4));
    }

    @Test
    void oneSharedScanAnswersEveryObserverWithItsOwnRadius() {
        // One scan result, as the tracker computes it once per window: two faces of one small pool.
        List<LavaSurface> marked = List.of(
                new LavaSurface(0, 64, 0, 8 / 9.0F),
                new LavaSurface(1, 64, 0, 8 / 9.0F));
        Vec3 near = new Vec3(0.5, 64.5, 2.5);
        Vec3 far = new Vec3(0.5, 64.5, 12.5);
        // The near observer is inside a short radius, the far observer is not -- from the same list.
        assertTrue(HeadlightLavaMath.anyWithin(marked, near, 8.0));
        assertFalse(HeadlightLavaMath.anyWithin(marked, far, 8.0));
        assertTrue(HeadlightLavaMath.anyWithin(marked, far, 24.0));
        // No marked face, no answer, whatever the radius.
        assertFalse(HeadlightLavaMath.anyWithin(List.of(), near, 24.0));
    }

    @Test
    void lampPreFilterIsAConservativeRangePlusRadiusBound() {
        // A lamp can only light points within its range, so a point farther than range + radius cannot be marked.
        Vec3 point = new Vec3(0, 0, 0);
        double radius = 24;
        assertTrue(HeadlightLavaMath.coneReaches(new Vec3(0, 0, 48), point, RANGE, radius), "lens exactly at range + radius");
        assertFalse(HeadlightLavaMath.coneReaches(new Vec3(0, 0, 48.001), point, RANGE, radius));
        // The bound ignores the direction: a lamp pointing away is still scanned (and then fails the lit test),
        // which keeps the filter an over-approximation rather than a second, disagreeing definition of reach.
        assertTrue(HeadlightLavaMath.coneReaches(new Vec3(10, 10, 10), point, RANGE, radius));
        assertTrue(HeadlightLavaMath.coneReaches(new Vec3(30, 0, 0), point, RANGE, radius));
        // A wide radius keeps a farther lamp in play, as the observer's own reach grows.
        assertTrue(HeadlightLavaMath.coneReaches(new Vec3(0, 0, 80), point, RANGE, 64));
        // Zero radius degenerates to the beam's own reach.
        assertTrue(HeadlightLavaMath.coneReaches(new Vec3(0, 0, RANGE), point, RANGE, 0));
        assertFalse(HeadlightLavaMath.coneReaches(new Vec3(0, 0, RANGE + 0.5), point, RANGE, 0));
    }

    @Test
    void bothSidesShareTheScanBudgetFaceCapAndThreshold() {
        // These constants are what makes "the server recomputes what the client outlines" true; a change here is a
        // deliberate decision, not a tuning detail. Both sides spend at most VOXEL_BUDGET reads per scan and keep at
        // most MAX_SURFACES faces: the client cuts by camera distance, the server (which has no camera) keeps the
        // first faces in scan order, so the count matches even though the cut cannot.
        assertEquals(65536, HeadlightLavaScan.VOXEL_BUDGET);
        assertEquals(1024, HeadlightLavaMath.MAX_SURFACES);
        assertEquals(0.04, HeadlightLavaMath.LIT_THRESHOLD);
        assertEquals(1.0, HeadlightLavaMath.PULSE_HZ);
    }
}
