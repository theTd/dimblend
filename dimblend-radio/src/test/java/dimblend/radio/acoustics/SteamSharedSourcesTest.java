package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Several radios' sources in one GPU reflection simulator: one trace, a response each. As in a
 * session, each source is heard through one renderer of its own: the convolution effect takes a
 * new IR over from the source when it first plays it, so the response lives in that renderer.
 */
class SteamSharedSourcesTest {
    private static final Vec3 NEAR = new Vec3(3, 0, 1), CORNER = new Vec3(-4, 2, -3), OUTSIDE = new Vec3(20, 0, 0);
    /** Blocks an impulse is measured over (about 0.7 s), and the silence that lets its tail die before the next. */
    private static final int MEASURED = 60, DECAY = 300;

    /** A closed 16-block box around the origin, facing in. */
    private static AcousticMesh.Data room() {
        float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] materials = new int[12];
        Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    @Test void sourcesShareOneRunAndEachKeepsItsOwnResponse() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var room = room();
        try (var simulation = new SteamSimulation(44100, SteamSimulation.REFLECTIONS, true, 3);
                var nearEar = renderer(simulation);
                var cornerEar = renderer(simulation);
                var walledOffEar = renderer(simulation)) {
            var near = simulation.addSource();
            var corner = simulation.addSource();
            var walledOff = simulation.addSource();
            assertEquals(3, simulation.sourceCount());
            var outputs = simulation.simulateGpu(room, null, Vec3.ZERO, List.of(near, corner, walledOff),
                    List.of(NEAR, CORNER, OUTSIDE), 128);
            assertEquals(3, outputs.size());
            for (var output : outputs) assertNotNull(output.reflections.ir);
            assertNotEquals(outputs.get(0).reflections.ir, outputs.get(1).reflections.ir, "each source has its own IR");
            assertNotEquals(outputs.get(1).reflections.ir, outputs.get(2).reflections.ir);
            double nearEnergy = impulse(nearEar, outputs.get(0), NEAR);
            double cornerEnergy = impulse(cornerEar, outputs.get(1), CORNER);
            double walledOffEnergy = impulse(walledOffEar, outputs.get(2), OUTSIDE);
            assertTrue(nearEnergy > 1e-6 && Double.isFinite(nearEnergy), "a source in the listener's room reverberates: " + nearEnergy);
            assertTrue(cornerEnergy > 1e-6 && Double.isFinite(cornerEnergy), "so does every other one traced with it: " + cornerEnergy);
            assertTrue(walledOffEnergy < nearEnergy * 0.01, "one behind the walls gathers nothing: " + walledOffEnergy + " vs " + nearEnergy);

            // A source left out of a run keeps the response it had.
            silence(nearEar, outputs.get(0), NEAR);
            assertEquals(1, simulation.simulateGpu(room, null, Vec3.ZERO, List.of(walledOff), List.of(OUTSIDE), 128).size());
            double again = impulse(nearEar, outputs.get(0), NEAR);
            assertEquals(nearEnergy, again, nearEnergy * 0.1, "the near source sat the run out");

            walledOff.close();
            assertTrue(walledOff.closed());
            assertEquals(2, simulation.sourceCount());
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.simulateGpu(room, null, Vec3.ZERO, List.of(walledOff), List.of(OUTSIDE), 128));
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.simulateGpu(room, null, Vec3.ZERO, List.of(near, near), List.of(NEAR, NEAR), 128));
            assertNotNull(simulation.simulateGpu(room, null, Vec3.ZERO, List.of(near), List.of(NEAR), 128).get(0).reflections.ir);
        }
    }

    /** Radeon Rays ignores sources past the simulator's maximum; those are traced in another batch. */
    @Test void moreSourcesThanOneRunTracesAreRunInBatches() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var room = room();
        try (var simulation = new SteamSimulation(44100, SteamSimulation.REFLECTIONS, true, 1);
                var nearEar = renderer(simulation);
                var cornerEar = renderer(simulation)) {
            var near = simulation.addSource();
            var corner = simulation.addSource();
            var outputs = simulation.simulateGpu(room, null, Vec3.ZERO, List.of(near, corner), List.of(NEAR, CORNER), 128);
            double nearEnergy = impulse(nearEar, outputs.get(0), NEAR), cornerEnergy = impulse(cornerEar, outputs.get(1), CORNER);
            assertTrue(nearEnergy > 1e-6, "first batch: " + nearEnergy);
            assertTrue(cornerEnergy > 1e-6, "second batch: " + cornerEnergy);
        }
    }

    /** The constructor's own source and added ones do not leak into each other's runs. */
    @Test void theSingleSourceApiLeavesAddedSourcesOut() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var room = room();
        try (var simulation = new SteamSimulation(44100, SteamSimulation.REFLECTIONS, true, 2);
                var ear = renderer(simulation)) {
            var added = simulation.addSource();
            var shared = simulation.simulateGpu(room, null, Vec3.ZERO, List.of(added), List.of(NEAR), 128).get(0);
            double before = impulse(ear, shared, NEAR);
            assertTrue(before > 1e-6, "added source: " + before);
            silence(ear, shared, NEAR);
            assertNotNull(simulation.simulateGpu(room, Vec3.ZERO, CORNER, 64, 128).reflections.ir);
            assertEquals(before, impulse(ear, shared, NEAR), before * 0.1, "an added source sits out a single-source run");
        }
    }

    private static SteamRenderer renderer(SteamSimulation simulation) {
        var renderer = new SteamRenderer(simulation.context(), 44100);
        renderer.reflectionsReady();
        return renderer;
    }

    /** Wet energy of an impulse through {@code outputs}' IR, over {@link #MEASURED} blocks. */
    private static double impulse(SteamRenderer renderer, SteamAudio.SimulationOutputs outputs, Vec3 source) {
        return render(renderer, outputs, source, true, MEASURED);
    }

    /** Lets the previous impulse's tail ring out. */
    private static void silence(SteamRenderer renderer, SteamAudio.SimulationOutputs outputs, Vec3 source) {
        render(renderer, outputs, source, false, DECAY);
    }

    private static double render(SteamRenderer renderer, SteamAudio.SimulationOutputs outputs, Vec3 source,
            boolean impulse, int blocks) {
        var muted = new SteamAudio.DirectParams();
        muted.distance = 0;
        double energy = 0;
        for (int block = 0; block < blocks; block++) {
            float[] input = new float[SteamRenderer.FRAME];
            if (impulse && block == 0) input[0] = 1;
            for (float[] channel : renderer.render(input, muted, outputs.reflections, source, new SteamAudio.Space(), false, 1f)) {
                for (float sample : channel) energy += sample * (double) sample;
            }
        }
        return energy;
    }
}
