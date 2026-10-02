package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The last untested in-game ingredient: iplSimulatorRunReflections on a worker thread while the
 * audio thread applies the current impulse through the same context. Counts wet-silence windows.
 */
class SteamConcurrentApplyTest {
    private static AcousticMesh.Data room() {
        float[] vertices = {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                -8,-8,8, 8,-8,8, 8,8,8, -8,8,8};
        int[] triangles = {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5};
        int[] materials = new int[triangles.length / 3];
        java.util.Arrays.fill(materials, 4);
        return new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO);
    }

    @Test
    void concurrentRunAndApply() throws InterruptedException {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 2, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            var geometry = room();
            var first = simulation.simulateGpu(geometry, Vec3.ZERO, new Vec3(4, 0, 0), 64, 128);
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            java.util.concurrent.atomic.AtomicReference<SteamAudio.SimulationOutputs> current =
                    new java.util.concurrent.atomic.AtomicReference<>(first);
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            var workerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    while (!stop.get()) current.set(simulation.simulateGpu(geometry, Vec3.ZERO, new Vec3(4, 0, 0), 64, 128));
                } catch (Throwable error) { workerFailure.set(error); }
            });
            worker.setDaemon(true);
            worker.start();
            long end = System.nanoTime() + 3_000_000_000L;
            double wetSum = 0;
            int frames = 0, silentFrames = 0;
            try { while (System.nanoTime() < end) {
                var impulse = current.get().reflections;
                float[] input = new float[SteamRenderer.FRAME];
                input[0] = 0.5f;
                float[][] output = renderer.render(input, muted, impulse, new Vec3(4, 0, 0),
                        new SteamAudio.Space(), false, 1f);
                double energy = 0;
                for (float[] channel : output) for (float sample : channel) energy += sample * (double) sample;
                wetSum += energy;
                frames++;
                if (energy < 1e-9) silentFrames++;
            } } finally {
                stop.set(true);
                worker.join(5000);
            }
            assertFalse(worker.isAlive(), "Simulation worker must finish before native resource release");
            assertNull(workerFailure.get(), "Worker failures must fail the test");
            assertTrue(frames > 10);
            assertTrue(Double.isFinite(wetSum) && wetSum > 1e-6);
            assertTrue(silentFrames <= 2, "Concurrent updates must not erase the reflection field");
        }
    }
}
