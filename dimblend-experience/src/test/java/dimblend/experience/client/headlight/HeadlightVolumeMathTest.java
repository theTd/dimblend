package dimblend.experience.client.headlight;

import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadlightVolumeMathTest {
    private static final Vec3 AXIS = new Vec3(0, 0, 1);
    private static final double COS = Math.cos(Math.toRadians(25));

    @Test
    void clipsAxialInsideSidewaysAndSceneLimitedRays() {
        assertSegment(5, 29, new Vec3(0, 0, -5), AXIS, 100);
        assertSegment(0, 24, Vec3.ZERO, AXIS, 100);
        assertSegment(0, 19, new Vec3(0, 0, 5), AXIS, 100);
        assertSegment(0, 5, new Vec3(0, 0, 5), AXIS.scale(-1), 100);
        assertSegment(5, 12, new Vec3(0, 0, -5), AXIS, 12);
        double radius = 10 * Math.tan(Math.toRadians(25));
        assertSegment(10 - radius, 10 + radius, new Vec3(-10, 0, 10), new Vec3(1, 0, 0), 100);
        assertNull(clip(new Vec3(0, 0, -5), AXIS.scale(-1), 100));
        assertNull(clip(new Vec3(40, 0, 0), AXIS, 100));
        assertNull(clip(new Vec3(0, 0, -5), AXIS, 4));
        assertNull(clip(new Vec3(0, 0, -1), new Vec3(1, 0, 0), 100));
    }

    @Test
    void nearGeneratorAndTranslatedRotatedRaysMatchGeometry() {
        Vec3 lens = new Vec3(1024.25, -80.5, -400.75);
        Vec3 axis = new Vec3(1, 2, 3).normalize();
        Vec3 perpendicular = axis.cross(new Vec3(0, 1, 0)).normalize();
        for (double angle : new double[] {24.999, 25, 25.001}) {
            Vec3 ray = axis.scale(Math.cos(Math.toRadians(angle)))
                    .add(perpendicular.scale(Math.sin(Math.toRadians(angle))));
            var segment = HeadlightVolumeMath.clip(lens, ray, lens, axis, 24, COS, 100);
            if (angle <= 25) {
                assertNotNull(segment);
                assertEquals(24, segment.end(), 1e-7);
            } else assertNull(segment);
        }
        var translated = HeadlightVolumeMath.clip(lens.subtract(axis.scale(5)), axis, lens, axis, 24, COS, 100);
        assertNotNull(translated);
        assertEquals(5, translated.start(), 1e-7);
        assertEquals(29, translated.end(), 1e-7);
    }

    @Test
    void randomClipsAgreeWithIndependentPointInConeMembership() {
        var random = new Random(44271);
        for (int i = 0; i < 2000; i++) {
            Vec3 origin = randomVector(random).scale(40);
            Vec3 ray = randomVector(random).normalize();
            Vec3 axis = randomVector(random).normalize();
            double cosine = Math.cos(Math.toRadians(5 + random.nextDouble() * 55));
            var segment = HeadlightVolumeMath.clip(origin, ray, Vec3.ZERO, axis, 24, cosine, 60);
            for (int j = 0; j < 120; j++) {
                double t = (j + 0.37) * 0.5;
                Vec3 point = origin.add(ray.scale(t));
                boolean inside = point.lengthSqr() < 24 * 24 && point.dot(axis) > point.length() * cosine;
                boolean clipped = segment != null && t > segment.start() && t < segment.end();
                assertEquals(inside, clipped, "ray " + i + " at " + t);
            }
        }
    }

    @Test
    void hgIntegratesToOneAndFavorsLightTravellingTowardViewer() {
        for (double g : new double[] {0, 0.5, -0.5}) {
            double integral = 0;
            int bins = 100000;
            for (int i = 0; i < bins; i++) integral += HeadlightVolumeMath.phase(-1 + (i + 0.5) * 2 / bins, g);
            assertEquals(1, integral * 4 * Math.PI / bins, 1e-8);
        }
        assertEquals(1 / (4 * Math.PI), HeadlightVolumeMath.phase(0.3, 0), 1e-12);
        assertTrue(HeadlightVolumeMath.phase(1, 0.5) > HeadlightVolumeMath.phase(0, 0.5));
        assertTrue(HeadlightVolumeMath.phase(0, 0.5) > HeadlightVolumeMath.phase(-1, 0.5));
    }

    @Test
    void raysThroughTheApexKeepTheirFullInConeSegment() {
        // Regression for the black lamp spot: camera inside the cone, 8 blocks in front, aiming at the lens.
        Vec3 origin = new Vec3(1.2, 1.1, 8);
        for (double offset : new double[] {0, 1e-4, 0.01, 0.2}) {
            Vec3 ray = new Vec3(offset, 0, 0).subtract(origin).normalize();
            var segment = clip(origin, ray, 100);
            assertNotNull(segment);
            assertEquals(0, segment.start(), 0);
            double toLens = new Vec3(offset, 0, 0).subtract(origin).length();
            // The ray leaves the front nappe just before the lens, within a few offsets of it.
            assertEquals(toLens, segment.end(), offset * 4 + 1e-6, "offset " + offset);
        }
    }

    @Test
    void defaultNightHazeIsClearlyVisibleButCapped() {
        // Defaults: range 24, angle 50, intensity 1, density 0.35, 8 samples. Beam along +z from the origin.
        double side = haze(march(new Vec3(-10, 0, 10), new Vec3(1, 0, 0), 8));
        double driver = haze(march(new Vec3(0, 1.5, -2), new Vec3(0, -0.05, 1).normalize(), 8));
        double into = haze(march(new Vec3(0, 0.5, 20), new Vec3(0, -0.5, -20).normalize(), 8));
        assertTrue(side > 0.15 && side < 0.3, "side " + side);
        assertTrue(driver > 0.25 && driver < 0.45, "driver " + driver);
        assertTrue(into > driver && into <= HeadlightVolumeMath.HAZE_CAP, "into " + into);
        assertEquals(0, HeadlightVolumeMath.haze(0, 5), 0);
        assertTrue(HeadlightVolumeMath.haze(2, 100) <= HeadlightVolumeMath.HAZE_CAP);
    }

    @Test
    void cameraInsideBeamOnlySeesHazeAlongTheBeam() {
        // Standing 8 blocks in front of the lamp: looking sideways or at the sky must stay night-dark,
        // looking down the beam shows the shaft, looking back at the lamp gives the glow.
        Vec3 camera = new Vec3(0, 1, 8);
        double sideways = haze(march(camera, new Vec3(1, 0, 0), 8));
        double up = haze(march(camera, new Vec3(0, 1, 0), 8));
        double along = haze(march(camera, AXIS, 8));
        double back = haze(march(camera, new Vec3(0, -1, -8).normalize(), 8));
        assertTrue(sideways < 0.05, "sideways " + sideways);
        assertTrue(up < 0.05, "up " + up);
        assertTrue(along > 0.15, "along " + along);
        assertTrue(back > 0.45, "back " + back);
    }

    @Test
    void nearFadeOnlyAppliesWhileTheCameraStandsInTheBeam() {
        // Lens at the origin facing +z: in front on the axis = inside; behind (driver) or beside = outside.
        assertEquals(1, HeadlightVolumeMath.cameraInside(1, COS), 1e-12);
        assertEquals(0, HeadlightVolumeMath.cameraInside(-1, COS), 1e-12);
        assertEquals(0, HeadlightVolumeMath.cameraInside(0, COS), 1e-12);
        assertEquals(1, HeadlightVolumeMath.nearFade(0.5, 0), 1e-12);
        assertEquals(0, HeadlightVolumeMath.nearFade(0, 1), 1e-12);
        // From behind the lamp the shaft starts at the lens: the first blocks of the beam contribute fully.
        Vec3 driver = new Vec3(0, 1.5, -2);
        Vec3 ray = new Vec3(0, -1.2, 3).normalize();
        double nearLens = HeadlightVolumeMath.march(driver, ray, Vec3.ZERO, AXIS, 24, 1, COS, 4, 8, 0.5);
        assertTrue(nearLens > 0.3, "near lens " + nearLens);
    }

    @Test
    void hazeBehindATranslucentSurfaceIsDimmedNotDropped() {
        Vec3 side = new Vec3(-10, 0, 6);
        Vec3 ray = new Vec3(1, 0, 0);
        double open = march(side, ray, 8);
        double glazed = HeadlightVolumeMath.march(side, ray, Vec3.ZERO, AXIS, 24, 1, COS, 1000, 2, 8, 0.5);
        assertEquals(open * HeadlightVolumeMath.BEHIND_TRANSLUCENT, glazed, open * 1e-9);
        double inFront = HeadlightVolumeMath.march(side, ray, Vec3.ZERO, AXIS, 24, 1, COS, 1000, 1000, 8, 0.5);
        assertEquals(open, inFront, 1e-12);
    }

    @Test
    void beamIsBrightestNearTheLensAndOnItsAxis() {
        assertTrue(HeadlightVolumeMath.beamFalloff(0.5, 24, 1) > 2 * HeadlightVolumeMath.beamFalloff(6, 24, 1));
        assertEquals(0, HeadlightVolumeMath.beamFalloff(24, 24, 1), 1e-12);
        assertEquals(1, HeadlightVolumeMath.fogCone(1, COS), 1e-12);
        assertEquals(0, HeadlightVolumeMath.fogCone(COS, COS), 1e-12);
        double half = Math.cos(Math.toRadians(12.5));
        assertTrue(HeadlightVolumeMath.fogCone(half, COS) < 0.45, "core " + HeadlightVolumeMath.fogCone(half, COS));
    }

    @Test
    void equiAngularMarchTracksDenseReference() {
        Vec3[][] views = {
            {new Vec3(-10, 0, 10), new Vec3(1, 0, 0)},
            {new Vec3(0, 1.5, -2), new Vec3(0, -0.05, 1).normalize()},
            {new Vec3(0, 1, 8), AXIS},
            {new Vec3(-6, 0, 4), new Vec3(1, 0, 0.4).normalize()},
        };
        for (Vec3[] view : views) {
            double reference = reference(view[0], view[1]);
            for (double jitter : new double[] {0.1, 0.5, 0.9}) {
                double estimate = HeadlightVolumeMath.march(view[0], view[1], Vec3.ZERO, AXIS, 24, 1, COS, 1000, 8, jitter);
                assertEquals(reference, estimate, reference * 0.2, view[0] + " jitter " + jitter);
            }
        }
    }

    @Test
    void marchIsFiniteForRaysThroughTheLens() {
        for (Vec3 origin : new Vec3[] {new Vec3(0, 0, 5), new Vec3(0, 0, -5), new Vec3(0, 0, 1e-3)}) {
            Vec3 ray = origin.lengthSqr() == 0 ? AXIS : origin.scale(-1).normalize();
            double value = HeadlightVolumeMath.march(origin, ray, Vec3.ZERO, AXIS, 24, 1, COS, 1000, 8, 0.5);
            assertTrue(Double.isFinite(value) && value >= 0, origin + " " + value);
        }
    }

    private static double haze(double scatter) { return HeadlightVolumeMath.haze(0.35, scatter); }

    private static double march(Vec3 origin, Vec3 ray, int samples) {
        return HeadlightVolumeMath.march(origin, ray, Vec3.ZERO, AXIS, 24, 1, COS, 1000, samples, 0.5);
    }

    /** Dense uniform midpoint integral of the same integrand, independent of the equi-angular estimator. */
    private static double reference(Vec3 origin, Vec3 ray) {
        var segment = clip(origin, ray, 1000);
        if (segment == null) return 0;
        double inside = HeadlightVolumeMath.cameraInside(origin.dot(AXIS) / origin.length(), COS);
        int steps = 20000;
        double step = (segment.end() - segment.start()) / steps;
        double sum = 0;
        for (int i = 0; i < steps; i++) {
            double t = segment.start() + (i + 0.5) * step;
            Vec3 offset = origin.add(ray.scale(t));
            double distance = offset.length();
            Vec3 direction = offset.scale(1 / distance);
            sum += HeadlightVolumeMath.fogCone(direction.dot(AXIS), COS) * HeadlightVolumeMath.beamFalloff(distance, 24, 1)
                    * HeadlightVolumeMath.scatterPhase(-direction.dot(ray)) * HeadlightVolumeMath.nearFade(t, inside) * step;
        }
        return sum;
    }

    private static Vec3 randomVector(Random random) {
        return new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
    }

    private static HeadlightVolumeMath.Segment clip(Vec3 origin, Vec3 ray, double maxDistance) {
        return HeadlightVolumeMath.clip(origin, ray, Vec3.ZERO, AXIS, 24, COS, maxDistance);
    }

    private static void assertSegment(double start, double end, Vec3 origin, Vec3 ray, double maxDistance) {
        var segment = clip(origin, ray, maxDistance);
        assertNotNull(segment);
        assertEquals(start, segment.start(), 1e-8);
        assertEquals(end, segment.end(), 1e-8);
    }
}
