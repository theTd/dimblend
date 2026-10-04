package dimblend.radio.acoustics;

import java.util.function.BiFunction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticDirectTransmissionTest {
    private static final AcousticVoxelTrace.Cell STONE = new AcousticVoxelTrace.Cell(Shapes.block(), AcousticMaterials.STONE);
    /** Full-block walls spanning y, z in [-8, 8] at the given x. */
    private static BiFunction<Vec3, Vec3, AcousticRay> walls(int... xs) {
        AcousticVoxelTrace.Lookup lookup = pos -> Math.abs(pos.getY()) <= 8 && Math.abs(pos.getZ()) <= 8
                && java.util.Arrays.stream(xs).anyMatch(x -> x == pos.getX()) ? STONE : null;
        return (from, to) -> AcousticVoxelTrace.cast(from, to, lookup);
    }

    @Test void anOpenPathPassesEverything() {
        assertArrayEquals(new float[] {1, 1, 1},
                AcousticDirectTransmission.between(new Vec3(-3.5, 0.5, 0.5), new Vec3(6.5, 0.5, 0.5), walls()), 1e-6f);
    }

    @Test void everyWallCountsOnceWhateverItsCount() {
        Vec3 listener = new Vec3(-3.5, 0.5, 0.5), source = new Vec3(9.5, 0.5, 0.5);
        float[] one = AcousticMaterials.transmission(AcousticMaterials.STONE, 1);
        float[] thick = AcousticMaterials.transmission(AcousticMaterials.STONE, 3);
        for (int band = 0; band < 3; band++) {
            // The bundle's outer rays cross the walls slightly obliquely: a little more stone.
            assertEquals(one[band], AcousticDirectTransmission.between(listener, source, walls(0))[band], one[band] * 0.03);
            assertEquals(thick[band], AcousticDirectTransmission.between(listener, source, walls(0, 1, 2))[band], thick[band] * 0.03);
            float five = (float) Math.pow(one[band], 5);
            assertEquals(five, AcousticDirectTransmission.between(listener, source, walls(0, 2, 4, 6, 8))[band], five * 0.1,
                    "five walls multiply, band " + band);
        }
    }

    @Test void aListenerAgainstAWallDoesNotHearThroughItsOwnSide() {
        // The listener's head is 0.3 blocks from the wall face: no ray of the bundle may start inside it.
        Vec3 listener = new Vec3(-0.3, 0.5, 0.5), source = new Vec3(6.5, 0.5, 0.5);
        float[] one = AcousticMaterials.transmission(AcousticMaterials.STONE, 1);
        float[] heard = AcousticDirectTransmission.between(listener, source, walls(0));
        for (int band = 0; band < 3; band++) assertEquals(one[band], heard[band], one[band] * 0.1, "band " + band);
    }

    @Test void sweepingAcrossAPillarChangesTheLevelGradually() {
        AcousticVoxelTrace.Lookup pillar = pos -> pos.getX() == 0 && pos.getZ() == 0 && Math.abs(pos.getY()) <= 8 ? STONE : null;
        BiFunction<Vec3, Vec3, AcousticRay> tracer = (from, to) -> AcousticVoxelTrace.cast(from, to, pillar);
        Vec3 source = new Vec3(0.5, 0.5, -10.5);
        float[] one = AcousticMaterials.transmission(AcousticMaterials.STONE, 1);
        for (int band : new int[] {0, 2}) {
            // No step above a fifth of what the pillar takes away in this band.
            float previous = 1, deepest = 1, limit = 0.2f * (1 - one[band]);
            for (double x = -2; x <= 3; x += 1.0 / 16) {
                float level = AcousticDirectTransmission.between(new Vec3(x, 0.5, 10.5), source, tracer)[band];
                assertTrue(Math.abs(level - previous) < limit,
                        "no jump in band " + band + " at x=" + x + ": " + previous + " -> " + level);
                deepest = Math.min(deepest, level);
                previous = level;
            }
            // Straight behind it the whole bundle crosses the pillar: a full block of stone.
            assertEquals(one[band], deepest, one[band] * 0.05, "the pillar still shadows band " + band);
        }
    }
}
