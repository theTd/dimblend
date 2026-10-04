package dimblend.radio.acoustics;

import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbisonicRotationTest {
    /** First-order coefficients of a sound arriving from {@code direction}, in Steam Audio's frame (W, Y, Z, X). */
    private static float[] arrivingFrom(Vec3 direction) {
        return new float[] {0.5f, (float) -direction.x, (float) direction.y, (float) -direction.z};
    }

    private static Vec3 turn(Quaterniond rotation, Vec3 direction) {
        Vector3d turned = rotation.transform(new Vector3d(direction.x, direction.y, direction.z));
        return new Vec3(turned.x, turned.y, turned.z);
    }

    @Test void aSoundFromOneDirectionArrivesFromWhereTheRotationTurnsIt() {
        var random = new Random(7);
        for (int i = 0; i < 50; i++) {
            Quaterniond rotation = new Quaterniond().rotateXYZ(random.nextDouble() * 6, random.nextDouble() * 6, random.nextDouble() * 6);
            Vec3 direction = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            float[] turned = AmbisonicRotation.of(rotation).apply(arrivingFrom(direction));
            float[] expected = arrivingFrom(turn(rotation, direction));
            assertArrayEquals(expected, turned, 1e-5f);
            assertTrue(new PathingField(new float[] {1, 1, 1}, turned, 1).arrival().distanceTo(turn(rotation, direction)) < 1e-5,
                    "the path's arrival reads the same convention");
        }
    }

    /** A structure turned a quarter to the west: what came from its front (-z) comes from the world's west (-x). */
    @Test void aQuarterTurnMovesTheFrontToTheSide() {
        var quarter = new Quaterniond().rotateY(Math.PI / 2);
        assertEquals(-1, turn(quarter, new Vec3(0, 0, -1)).x, 1e-9);
        Vec3 arrival = new PathingField(new float[] {1, 1, 1}, AmbisonicRotation.of(quarter).apply(arrivingFrom(new Vec3(0, 0, -1))), 1)
                .arrival();
        assertEquals(-1, arrival.x, 1e-6);
    }

    @Test void theIdentityAndWAreLeftAlone() {
        float[] sh = {0.3f, -0.2f, 0.7f, 0.1f};
        assertArrayEquals(sh, AmbisonicRotation.of(new Quaterniond()).apply(sh), 1e-7f);
        float[] turned = AmbisonicRotation.of(new Quaterniond().rotateZ(1.2)).apply(sh);
        assertEquals(sh[0], turned[0]);
        assertArrayEquals(new float[] {0.3f, -0.2f, 0.7f, 0.1f}, sh, "the input is left as it is");
    }

    @Test void channelsTurnAsTheirCoefficientsDo() {
        var rotation = AmbisonicRotation.of(new Quaterniond().rotateXYZ(0.3, -1.4, 2.2));
        var random = new Random(11);
        int frames = 64;
        float[][] channels = new float[4][frames];
        for (float[] channel : channels) for (int i = 0; i < frames; i++) channel[i] = (float) random.nextGaussian();
        float[][] expected = new float[4][frames];
        for (int i = 0; i < frames; i++) {
            float[] sample = rotation.apply(new float[] {channels[0][i], channels[1][i], channels[2][i], channels[3][i]});
            for (int c = 0; c < 4; c++) expected[c][i] = sample[c];
        }
        rotation.apply(channels, frames);
        for (int c = 0; c < 4; c++) assertArrayEquals(expected[c], channels[c], 1e-6f);
    }
}
