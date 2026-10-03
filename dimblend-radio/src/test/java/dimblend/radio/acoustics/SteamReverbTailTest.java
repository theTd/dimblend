package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The GPU reverb rings as long as the room does, up to the 2.56 s Steam Audio keeps ({@link RadeonRaysHistogramFix}). */
class SteamReverbTailTest {
    private static final int RATE = 44100;

    /** An 8x5x8 stone room rings for about 3 s (Eyring, mid band); unfixed, it fell silent at 1.28 s. */
    @Test void aStoneRoomKeepsRingingPastTheOldCutOff() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        int[] interior = {8, 5, 8};
        Vec3 listener = new Vec3(5, 2.6, 5), source = listener.add(3, 0, 1);
        double[] windows = new double[25];
        try (var simulation = new SteamSimulation(RATE, SteamSimulation.REFLECTIONS, true);
                var renderer = new SteamRenderer(simulation.context(), RATE)) {
            var outputs = simulation.simulateGpu(stoneRoom(interior), listener, source, 64, 128);
            renderer.reflectionsReady();
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            int blocks = (int) Math.ceil(windows.length * 0.1 * RATE / SteamRenderer.FRAME);
            for (int block = 0; block < blocks; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                float[][] out = renderer.render(input, muted, outputs.reflections, source.subtract(listener), new SteamAudio.Space(), false, 1f);
                for (int i = 0; i < SteamRenderer.FRAME; i++) {
                    int window = (int) (((long) block * SteamRenderer.FRAME + i) / (0.1 * RATE));
                    if (window < windows.length) for (float[] channel : out) windows[window] += channel[i] * (double) channel[i];
                }
            }
        }
        double first = windows[0];
        assertTrue(first > 0, "the room reverberates");
        for (int window = 14; window < 24; window++) {
            double db = 10 * Math.log10(windows[window] / first);
            assertTrue(db > -50, String.format("still ringing at %.1f s: %.0f dB", window * 0.1, db));
        }
    }

    /** A one-block stone shell around an air interior, at the origin. */
    private static AcousticMesh.Data stoneRoom(int[] interior) {
        int[] size = {interior[0] + 2, interior[1] + 2, interior[2] + 2};
        byte[] cells = new byte[size[0] * size[1] * size[2]];
        for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
            boolean shell = x == 0 || y == 0 || z == 0 || x == size[0] - 1 || y == size[1] - 1 || z == size[2] - 1;
            if (shell) cells[(z * size[1] + y) * size[0] + x] = (byte) (AcousticMaterials.STONE + 1);
        }
        var mesh = new AcousticMesh(Vec3.ZERO);
        mesh.appendCells(cells, new int[] {0, 0, 0}, size);
        return mesh.data();
    }
}
