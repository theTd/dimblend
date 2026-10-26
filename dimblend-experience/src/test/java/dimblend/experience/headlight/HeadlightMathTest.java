package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeadlightMathTest {
    @Test
    void attenuationIsBoundedMonotoneAndHasASmoothRangeCutoff() {
        for (double range : new double[] {4, 24, 64}) {
            for (double intensity : new double[] {0, 0.05, 1, 3}) {
                double previous = 1;
                for (int i = 0; i <= 1100; i++) {
                    double value = HeadlightMath.attenuation(range * i / 1000, range, intensity);
                    assertTrue(value >= 0 && value < 1);
                    assertTrue(value <= previous + 1e-12);
                    previous = value;
                }
                assertEquals(0, HeadlightMath.attenuation(range, range, intensity));
                assertTrue(HeadlightMath.attenuation(range * 0.9999, range, intensity) < 1e-6);
            }
        }
        assertTrue(HeadlightMath.attenuation(18, 24, 1) > 0.1, "beam must still reach useful distances");
        assertTrue(HeadlightMath.attenuation(0, 24, 3) < 1, "near light is softly capped");
    }

    @Test
    void coneUsesFullAngleAndTransitionsSmoothlyToAMildHotspot() {
        for (double fullAngle : new double[] {10, 50, 120}) {
            double outer = Math.cos(Math.toRadians(fullAngle / 2));
            double inner = Math.cos(Math.toRadians(fullAngle / 2 * 0.75));
            assertEquals(0, HeadlightMath.cone(-1, fullAngle));
            assertEquals(0, HeadlightMath.cone(outer, fullAngle));
            assertTrue(HeadlightMath.cone((outer + inner) / 2, fullAngle) > 0);
            assertEquals(1, HeadlightMath.cone(1, fullAngle), 1e-12);
            double previous = 0;
            for (int i = 0; i <= 100; i++) {
                double value = HeadlightMath.cone(outer + (1 - outer) * i / 100, fullAngle);
                assertTrue(value >= previous - 1e-12 && value <= 1);
                previous = value;
            }
        }
    }

    @Test
    void lensOffsetFollowsAllSixFacingsAndOnlyMovesWithoutBase() {
        Vec3 center = new Vec3(-33.5, 101.5, 42.5);
        for (Vec3 facing : new Vec3[] {new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
                new Vec3(0, 1, 0), new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
            assertEquals(center, HeadlightMath.lens(center, facing, false));
            assertEquals(center.subtract(facing.scale(0.25)), HeadlightMath.lens(center, facing, true));
        }
    }

    @Test
    void nearestEightUseCameraDistanceAndStableTies() {
        Vec3 camera = new Vec3(100, 0, 0);
        var beams = new ArrayList<HeadlightLens>();
        for (int i = 0; i < 20; i++) beams.add(new HeadlightLens(new Vec3(90 + i, 0, 0), new Vec3(1, 0, 0)));
        var selected = HeadlightMath.nearest(beams, camera);
        assertEquals(8, selected.size());
        assertEquals(100, selected.getFirst().lens().x);
        assertTrue(selected.stream().allMatch(beam -> beam.lens().distanceTo(camera) <= 4));
        Collections.reverse(beams);
        assertEquals(selected, HeadlightMath.nearest(beams, camera));
        assertEquals(0, HeadlightMath.nearest(java.util.List.of(), camera).size());
        assertEquals(1, HeadlightMath.nearest(java.util.List.of(beams.getFirst()), camera).size());
    }
}
