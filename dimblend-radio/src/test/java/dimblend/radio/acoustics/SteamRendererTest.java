package dimblend.radio.acoustics;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BiFunction;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SteamRendererTest {
    @Test
    void simulatedWallsProduceDelayedEchoesAndAnOpenSceneDoesNot() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 3)) {
            Vec3 source = new Vec3(4, 0, 0);
            var clear = simulation.simulate((from, to) -> AcousticRay.miss(to), Vec3.ZERO, source, 32, 64);
            float[] open = render(simulation, clear, source);
            var closed = simulation.simulate(room(), Vec3.ZERO, source, 32, 128);
            float[] cave = render(simulation, closed, source);
            assertTrue(energy(open, 44100 / 5) < 1e-10, "open air must have no late reverberation");
            assertTrue(energy(cave, 44100 / 5) > 1e-8, "traced walls must generate a sustained convolution tail");
            int afterDirectHrtf = SteamRenderer.FRAME + 1000;
            assertTrue(energy(cave, afterDirectHrtf) > energy(open, afterDirectHrtf) * 10,
                    "reflections must arrive after the delayed direct pulse");
            writeWave("steam-audio-open.wav", open);
            writeWave("steam-audio-cave.wav", cave);
        }
    }

    @Test
    void freshSolversGiveARepeatableReflectionField() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        Vec3 source = new Vec3(4, 0, 0);
        float[] a;
        float[] b;
        try (var simulation = new SteamSimulation(44100, 3)) {
            a = render(simulation, simulation.simulate(room(), Vec3.ZERO, source, 128, 128), source);
        }
        try (var simulation = new SteamSimulation(44100, 3)) {
            b = render(simulation, simulation.simulate(room(), Vec3.ZERO, source, 128, 128), source);
        }
        double difference = 0, reference = 0;
        for (int i = 0; i < a.length; i++) {
            difference += (a[i] - b[i]) * (a[i] - b[i]);
            reference += a[i] * a[i];
        }
        double relativeError = Math.sqrt(difference / reference);
        assertTrue(relativeError < 0.01, "repeatable room response, relative error=" + relativeError);
    }

    @Test
    void binauralDirectSoundFollowsTheSourceSide() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            double[] left = earEnergy(simulation, new Vec3(-4, 0, 0));
            double[] right = earEnergy(simulation, new Vec3(4, 0, 0));
            assertTrue(left[0] > left[1] * 1.2, "left source must favor the left ear");
            assertTrue(right[1] > right[0] * 1.2, "right source must favor the right ear");
        }
    }

    private static double[] earEnergy(SteamSimulation simulation, Vec3 source) {
        var outputs = simulation.simulate((from, to) -> AcousticRay.miss(to), Vec3.ZERO, source, 1, 0);
        double[] energy = new double[2];
        try (var renderer = new SteamRenderer(simulation.context(), 44100)) {
            for (int block = 0; block < 5; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 0.5f;
                float[][] output = renderer.render(input, outputs.direct, null, source, new SteamAudio.Space(), false, 1f);
                for (int c = 0; c < 2; c++) for (float sample : output[c]) energy[c] += sample * sample;
            }
        }
        return energy;
    }

    private static BiFunction<Vec3, Vec3, AcousticRay> room() {
        AABB room = new AABB(-8, -8, -8, 8, 8, 8);
        return (from, to) -> {
            double[] range = AcousticRaycaster.clipRange(from, to, room);
            if (range == null) return AcousticRay.miss(to);
            double t = room.contains(from) ? range[1] : range[0];
            if (t >= 1) return AcousticRay.miss(to);
            Vec3 point = from.add(to.subtract(from).scale(t));
            Vec3 normal = Math.abs(Math.abs(point.x) - 8) < 1e-4 ? new Vec3(-Math.signum(point.x), 0, 0)
                    : Math.abs(Math.abs(point.y) - 8) < 1e-4 ? new Vec3(0, -Math.signum(point.y), 0)
                    : new Vec3(0, 0, -Math.signum(point.z));
            return new AcousticRay(AcousticRay.Kind.HIT, point, normal, 0.9f);
        };
    }

    private static float[] render(SteamSimulation simulation, SteamAudio.SimulationOutputs outputs, Vec3 source) {
        float[] result = new float[44100 * 2];
        try (var renderer = new SteamRenderer(simulation.context(), 44100)) {
            for (int offset = 0; offset < result.length; offset += SteamRenderer.FRAME) {
                float[] input = new float[SteamRenderer.FRAME];
                if (offset == 0) input[0] = 0.5f;
                float[][] block = renderer.render(input, outputs.direct, outputs.reflections, source,
                        new SteamAudio.Space(), false, 1f);
                for (int i = 0; i < SteamRenderer.FRAME && offset + i < result.length; i++) {
                    result[offset + i] = block[0][i] + block[1][i];
                }
            }
        }
        return result;
    }

    private static double energy(float[] samples, int start) {
        double sum = 0;
        for (int i = start; i < samples.length; i++) sum += samples[i] * samples[i];
        return sum;
    }

    private static void writeWave(String name, float[] samples) throws Exception {
        ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) pcm.putShort((short) Math.max(-32768, Math.min(32767, sample * 32767)));
        var format = new AudioFormat(44100, 16, 1, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm.array()), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, new File(name));
        }
    }
}
