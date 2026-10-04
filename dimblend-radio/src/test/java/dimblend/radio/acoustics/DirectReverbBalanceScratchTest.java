package dimblend.radio.acoustics;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scratch: how much louder the echo is than the direct sound in small closed rooms, as a radio
 * plays them (3 echo slots filled by turns, linear distance gain), with the echo's gain as it was
 * (a flat 3) and as RadioSimulationSession#wetScale gives it now (the distance below 3 blocks,
 * times the volume below 100 %). The echo is rendered once at gain 1 and scaled, so every column
 * hears the same simulated response.
 */
class DirectReverbBalanceScratchTest {
    private static final int RATE = 44100, SLOTS = 3;
    private static final double LENGTH = 3.5, EARLY = 0.05, WET_GAIN = 3, AUDIBLE_RANGE = 96, HALF = 0.5;

    @Test void directAgainstEcho() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        int[][] rooms = {{4, 3, 4}, {6, 3, 6}, {10, 4, 10}};
        for (int material : new int[] {AcousticMaterials.STONE, AcousticMaterials.WOOD}) {
            for (int[] interior : rooms) {
                var room = room(interior, material);
                Vec3 listener = new Vec3(1 + interior[0] / 2.0, 2.6, 1 + interior[2] / 2.0);
                for (double distance : new double[] {1, 1.5, 3, 4.5}) {
                    if (distance > interior[0] / 2.0 - 0.3) continue;
                    measure(interior, material, room, listener, listener.add(distance, 0, 0), distance);
                }
            }
        }
    }

    private static void measure(int[] interior, int material, AcousticMesh.Data room, Vec3 listener, Vec3 source, double distance) {
        try (var simulation = new SteamSimulation(RATE, SteamSimulation.REFLECTIONS, true, SLOTS)) {
            List<SteamSimulation.Source> sources = new ArrayList<>();
            for (int slot = 0; slot < SLOTS; slot++) sources.add(simulation.addSource());
            var impulses = new SteamAudio.ReflectionParams[SLOTS];
            for (int slot = 0; slot < SLOTS; slot++) {
                var outputs = simulation.simulateGpu(room, null, listener, List.of(sources.get(slot)), List.of(source), 128);
                impulses[slot] = outputs.get(0).reflections;
            }
            try (var renderer = new SteamRenderer(simulation.context(), RATE, SLOTS, () -> { })) {
                renderer.reflectionsReady();
                var direct = new SteamAudio.DirectParams();
                float gain = (float) Math.max(0, 1 - distance / AUDIBLE_RANGE);
                Vec3 relative = source.subtract(listener);
                var stems = new SteamRenderer.Stems();
                int blocks = (int) Math.ceil(LENGTH * RATE / SteamRenderer.FRAME);
                int total = blocks * SteamRenderer.FRAME;
                double[] dry = new double[total], wet = new double[total];
                for (int block = 0; block < blocks; block++) {
                    float[] input = new float[SteamRenderer.FRAME];
                    if (block == 0) input[0] = 1;
                    direct.distance = gain;
                    var prepared = renderer.prepareAveraged(input, direct, impulses, relative, false, null);
                    renderer.spatialize(prepared, relative, new SteamAudio.Space(), 1f, stems);
                    for (int i = 0; i < SteamRenderer.FRAME; i++) {
                        int at = block * SteamRenderer.FRAME + i;
                        for (int c = 0; c < 2; c++) {
                            dry[at] += stems.direct[c][i] * (double) stems.direct[c][i];
                            wet[at] += stems.echo[c][i] * (double) stems.echo[c][i];
                        }
                    }
                }
                int arrival = 0;
                for (int i = 1; i < total; i++) if (dry[i] > dry[arrival]) arrival = i;
                double dryEnergy = 0, wetEnergy = 0, early = 0;
                int earlyEnd = arrival + (int) (EARLY * RATE);
                for (int i = 0; i < total; i++) {
                    dryEnergy += dry[i];
                    wetEnergy += wet[i];
                    if (i >= arrival && i < earlyEnd) early += wet[i];
                }
                double now = Math.min(WET_GAIN, Math.max(1, distance));
                // Unboosted echo against Steam Audio's own 1/r direct law (1 block minimum): a real room's balance.
                double physical = db(wetEnergy / dryEnergy) - db(Math.pow(1 / Math.max(distance, 1) / gain, 2));
                System.out.printf("[balance] %s %dx%dx%d at %.1f blocks, echo over direct (whole/first 50 ms): "
                                + "was %s dB, now %s dB, now at 50%% volume %s dB; real room %+.1f dB%n",
                        material == AcousticMaterials.STONE ? "stone" : "wood", interior[0], interior[1], interior[2],
                        distance, over(WET_GAIN, dryEnergy, wetEnergy, early), over(now, dryEnergy, wetEnergy, early),
                        over(now * HALF, dryEnergy, wetEnergy, early), physical);
            }
        }
    }

    /** Echo over direct in dB for an echo gain: the whole response / its first 50 ms. */
    private static String over(double wetGain, double dry, double wet, double early) {
        return String.format("%+.1f/%+.1f", db(wetGain * wetGain * wet / dry), db(wetGain * wetGain * early / dry));
    }

    private static double db(double energyRatio) {
        return 10 * Math.log10(energyRatio);
    }

    /** A one-block shell of {@code material} around an air interior of {@code interior} blocks, at the origin. */
    private static AcousticMesh.Data room(int[] interior, int material) {
        int[] size = {interior[0] + 2, interior[1] + 2, interior[2] + 2};
        byte[] cells = new byte[size[0] * size[1] * size[2]];
        for (int z = 0; z < size[2]; z++) for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
            boolean shell = x == 0 || y == 0 || z == 0 || x == size[0] - 1 || y == size[1] - 1 || z == size[2] - 1;
            if (shell) cells[(z * size[1] + y) * size[0] + x] = (byte) (material + 1);
        }
        var mesh = new AcousticMesh(Vec3.ZERO);
        mesh.appendCells(cells, new int[] {0, 0, 0}, size);
        return mesh.data();
    }
}
