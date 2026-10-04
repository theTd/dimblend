package dimblend.radio.acoustics;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reflections simulated in a turned frame (a structure's: {@link AcousticFrame}) and turned back
 * into world axes by {@link AmbisonicRotation} are heard from where the same scene simulated in
 * the world's frame is: checks the Ambisonic convention the turn assumes against Steam Audio.
 */
class SteamFrameReflectionsTest {
    private static final Vec3 LISTENER = Vec3.ZERO, SOURCE = new Vec3(0, 0, -4);
    private static final int BLOCKS = 40;

    /** One large wall 3 blocks to the listener's left (-x), seen from both sides; nothing else. */
    private static AcousticMesh.Data wall(Quaterniond toFrame) {
        double[][] corners = {{-3, -12, -12}, {-3, -12, 12}, {-3, 12, 12}, {-3, 12, -12}};
        float[] vertices = new float[12];
        for (int i = 0; i < 4; i++) {
            Vector3d corner = toFrame.transform(new Vector3d(corners[i][0], corners[i][1], corners[i][2]));
            vertices[i * 3] = (float) corner.x;
            vertices[i * 3 + 1] = (float) corner.y;
            vertices[i * 3 + 2] = (float) corner.z;
        }
        int[] triangles = {0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2};
        int[] materials = new int[4];
        Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    /**
     * (left - right) / (left + right) of the decoded echo of an impulse, the scene simulated in the
     * frame {@code frame} turns into the world (identity: the world's), turned back when {@code turnBack}.
     */
    private static double leftOverRight(Quaterniond frame, boolean turnBack) {
        Quaterniond toFrame = new Quaterniond(frame).conjugate();
        try (var simulation = new SteamSimulation(44100, SteamSimulation.REFLECTIONS, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            Vector3d listener = toFrame.transform(new Vector3d(LISTENER.x, LISTENER.y, LISTENER.z));
            Vector3d source = toFrame.transform(new Vector3d(SOURCE.x, SOURCE.y, SOURCE.z));
            var outputs = simulation.simulateGpu(wall(toFrame), new Vec3(listener.x, listener.y, listener.z),
                    new Vec3(source.x, source.y, source.z), 64, 128);
            assertNotNull(outputs.reflections.ir);
            renderer.reflectionsReady();
            AmbisonicRotation back = turnBack ? AmbisonicRotation.of(frame) : null;
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            var stems = new SteamRenderer.Stems();
            double left = 0, right = 0;
            for (int block = 0; block < BLOCKS; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                var prepared = renderer.prepareAveraged(input, muted, new SteamAudio.ReflectionParams[] {outputs.reflections},
                        SOURCE.subtract(LISTENER), false, null, back);
                renderer.spatialize(prepared, SOURCE.subtract(LISTENER), new SteamAudio.Space(), 1f, stems);
                for (int i = 0; i < SteamRenderer.FRAME; i++) {
                    left += stems.echo[0][i] * (double) stems.echo[0][i];
                    right += stems.echo[1][i] * (double) stems.echo[1][i];
                }
            }
            assertTrue(left + right > 1e-9 && Double.isFinite(left + right), "the wall reflects: " + (left + right));
            return (left - right) / (left + right);
        }
    }

    @Test void reflectionsSimulatedInATurnedFrameAreHeardFromTheWorldsSide() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        double world = leftOverRight(new Quaterniond(), false);
        assertTrue(world > 0.2, "a wall on the left is heard on the left: " + world);
        Quaterniond[] frames = {
                new Quaterniond().rotateY(Math.PI / 2),
                // A roll and a pitch move the wall overhead or underfoot in the frame: Y and Z exchange signs.
                new Quaterniond().rotateZ(Math.PI / 2),
                new Quaterniond().rotateZ(-Math.PI / 2),
                new Quaterniond().rotateXYZ(0.4, 1.1, -0.7),
        };
        for (Quaterniond frame : frames) {
            double turned = leftOverRight(frame, true);
            assertTrue(turned > world * 0.5, "turned back from " + frame + ": " + turned + " vs " + world);
        }
        double unturned = leftOverRight(new Quaterniond().rotateY(Math.PI), false);
        assertTrue(unturned < -0.2, "left in the world's axes is right in a half-turned frame's: " + unturned);
    }
}
