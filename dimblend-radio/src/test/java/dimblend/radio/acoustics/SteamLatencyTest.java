package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native movement and audio deadline benchmark; prints timing without machine-specific timing assertions. */
class SteamLatencyTest {
    @Test void movingListenerKeepsFiniteReflectionsAtBothCommonRates() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
        int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
        int[] materials = new int[12]; Arrays.fill(materials,4);
        var mesh = new AcousticMesh.Data(vertices,triangles,materials,Vec3.ZERO);
        for (int rate : new int[]{44100,48000}) {
            AtomicInteger resets = new AtomicInteger();
            try (var simulation = new SteamSimulation(rate,2,true);
                    var renderer = new SteamRenderer(simulation.context(),rate,resets::incrementAndGet)) {
                long[] simulations = new long[40], renders = new long[400];
                double energy = 0;
                var muted = new SteamAudio.DirectParams(); muted.distance=0;
                for (int step=0;step<40;step++) {
                    Vec3 listener = new Vec3(Math.sin(step*0.3)*5,0,Math.cos(step*0.3)*5);
                    long start = System.nanoTime();
                    var outputs = simulation.simulateGpu(mesh,listener,new Vec3(4,0,0),64,128);
                    simulations[step]=System.nanoTime()-start;
                    renderer.reflectionsReady();
                    for(int block=0;block<10;block++) {
                        float[] input = new float[SteamRenderer.FRAME];
                        for(int sample=0;sample<input.length;sample++) input[sample]=(float)Math.sin(sample*.1)*.1f;
                        start=System.nanoTime();
                        var rendered=renderer.render(input,muted,outputs.reflections,new Vec3(4,0,0).subtract(listener),new SteamAudio.Space(),false,1);
                        renders[step*10+block]=System.nanoTime()-start;
                        for(float[] channel:rendered) for(float sample:channel) {
                            assertTrue(Float.isFinite(sample)); energy+=sample*(double)sample;
                        }
                    }
                }
                Arrays.sort(simulations); Arrays.sort(renders);
                System.out.printf("[latency] rate=%d frame=%d sim_p50_ms=%.3f sim_p95_ms=%.3f render_p50_ms=%.3f render_p95_ms=%.3f deadline_ms=%.3f resets=%d energy=%.3f%n",
                    rate,SteamRenderer.FRAME,simulations[20]/1e6,simulations[38]/1e6,renders[200]/1e6,renders[380]/1e6,
                    1000.0*SteamRenderer.FRAME/rate,resets.get(),energy);
                assertEquals(0,resets.get(),"Moving-listener GPU simulation must not poison its IR");
                assertTrue(energy>1e-6);
            }
        }
    }
}
