package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scratch: how long the production GPU reverb rings in closed stone rooms, against Eyring's
 * prediction from the same absorption. Prints the decay per 100 ms window and where it stops.
 */
class ReverbTailScratchTest {
    private static final int RATE = 44100, RUNS = 4;
    private static final double WINDOW = 0.1, LENGTH = 4;

    @Test void stoneRoomDecay() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        for (int[] interior : new int[][] {{8, 5, 8}, {24, 12, 24}}) {
            var room = stoneRoom(interior);
            Vec3 listener = new Vec3(1 + interior[0] / 2.0, 2.6, 1 + interior[2] / 2.0);
            Vec3 source = listener.add(3, 0, 1);
            int windows = (int) Math.round(LENGTH / WINDOW);
            double[] energy = new double[windows];
            try (var simulation = new SteamSimulation(RATE, SteamSimulation.REFLECTIONS, true)) {
                for (int run = 0; run < RUNS; run++) {
                    var outputs = simulation.simulateGpu(room, listener, source, 64, 128);
                    try (var renderer = new SteamRenderer(simulation.context(), RATE)) {
                        renderer.reflectionsReady();
                        var muted = new SteamAudio.DirectParams();
                        muted.distance = 0;
                        int blocks = (int) Math.ceil(LENGTH * RATE / SteamRenderer.FRAME);
                        for (int block = 0; block < blocks; block++) {
                            float[] input = new float[SteamRenderer.FRAME];
                            if (block == 0) input[0] = 1;
                            float[][] out = renderer.render(input, muted, outputs.reflections, source.subtract(listener),
                                    new SteamAudio.Space(), false, 1f);
                            for (int i = 0; i < SteamRenderer.FRAME; i++) {
                                int w = (int) (((long) block * SteamRenderer.FRAME + i) / (WINDOW * RATE));
                                if (w < windows) for (float[] channel : out) energy[w] += channel[i] * (double) channel[i];
                            }
                        }
                    }
                }
            }
            double peak = 0;
            for (double e : energy) peak = Math.max(peak, e);
            StringBuilder decay = new StringBuilder();
            double last = 0;
            for (int w = 0; w < windows; w++) {
                double db = 10 * Math.log10(Math.max(energy[w], 1e-30) / peak);
                decay.append(String.format("%.1f:%.0f ", w * WINDOW, db));
                if (db > -90) last = (w + 1) * WINDOW;
            }
            System.out.printf("[tail] room %dx%dx%d eyring RT60 low/mid/high = %s s; audible until %.1f s%n[tail]   dB per window: %s%n",
                    interior[0], interior[1], interior[2], eyring(interior), last, decay);
        }
    }

    /** A one-block stone shell around an air interior of {@code interior} blocks, at the origin. */
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

    private static String eyring(int[] interior) {
        double volume = (double) interior[0] * interior[1] * interior[2];
        double surface = 2.0 * (interior[0] * interior[1] + interior[1] * interior[2] + interior[0] * interior[2]);
        float[] absorption = AcousticMaterials.absorption(AcousticMaterials.STONE);
        StringBuilder bands = new StringBuilder();
        for (float a : absorption) bands.append(String.format("%.1f/", 0.161 * volume / (-surface * Math.log(1 - a))));
        return bands.substring(0, bands.length() - 1);
    }
}
