package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A renderer with several echo slots hears the mean of their responses: each slot's convolution
 * takes its IR over from its own source, and the slots' outputs are averaged.
 */
class SteamEchoAveragingTest {
    private static final Vec3 NEAR = new Vec3(3, 0, 1);
    private static final int BLOCKS = 60;

    /** A closed 16-block box around the origin, facing in. */
    private static AcousticMesh.Data room() {
        float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] materials = new int[12];
        Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    @Test void slotsAverageTheirResponsesAndAnEmptySlotAddsSilence() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, SteamSimulation.REFLECTIONS, true, 4);
                var single = new SteamRenderer(simulation.context(), 44100);
                var averaged = new SteamRenderer(simulation.context(), 44100, 2, () -> { });
                var halfFilled = new SteamRenderer(simulation.context(), 44100, 2, () -> { })) {
            var sources = List.of(simulation.addSource(), simulation.addSource(), simulation.addSource(), simulation.addSource());
            // One run, one position: four equal responses, each source's own.
            var outputs = simulation.simulateGpu(room(), null, Vec3.ZERO, sources, List.of(NEAR, NEAR, NEAR, NEAR), 128);
            for (var renderer : List.of(single, averaged, halfFilled)) renderer.reflectionsReady();
            float[][] one = impulse(single, new SteamAudio.ReflectionParams[] {outputs.get(0).reflections});
            float[][] mean = impulse(averaged, new SteamAudio.ReflectionParams[] {outputs.get(1).reflections, outputs.get(2).reflections});
            float[][] half = impulse(halfFilled, new SteamAudio.ReflectionParams[] {outputs.get(3).reflections, null});
            double energy = energy(one, 1, null);
            assertTrue(energy > 1e-6 && Double.isFinite(energy), "the room reverberates: " + energy);
            assertTrue(energy(one, 1, mean) < energy * 1e-4, "the mean of equal responses is that response");
            assertTrue(energy(one, 0.5f, half) < energy * 1e-4, "a slot without a response adds silence to the mean");
            assertEquals(2, averaged.echoSlots());
            assertThrows(IllegalArgumentException.class, () -> averaged.prepareAveraged(new float[SteamRenderer.FRAME],
                    new SteamAudio.DirectParams(), new SteamAudio.ReflectionParams[1], NEAR, false, null));
        }
    }

    /** Wet output of an impulse, the direct sound muted, over {@link #BLOCKS} blocks. */
    private static float[][] impulse(SteamRenderer renderer, SteamAudio.ReflectionParams[] impulses) {
        var muted = new SteamAudio.DirectParams();
        muted.distance = 0;
        float[][] heard = new float[2][BLOCKS * SteamRenderer.FRAME];
        for (int block = 0; block < BLOCKS; block++) {
            float[] input = new float[SteamRenderer.FRAME];
            if (block == 0) input[0] = 1;
            var prepared = renderer.prepareAveraged(input, muted, impulses, NEAR, false, null);
            float[][] output = renderer.spatialize(prepared, NEAR, new SteamAudio.Space(), 1f);
            for (int c = 0; c < 2; c++) System.arraycopy(output[c], 0, heard[c], block * SteamRenderer.FRAME, SteamRenderer.FRAME);
        }
        return heard;
    }

    /** Energy of {@code scale * reference - other}, or of {@code scale * reference} when other is null. */
    private static double energy(float[][] reference, float scale, float[][] other) {
        double sum = 0;
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < reference[c].length; i++) {
                double difference = scale * reference[c][i] - (other == null ? 0 : other[c][i]);
                sum += difference * difference;
            }
        }
        return sum;
    }
}
