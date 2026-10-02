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
    void newOcclusionFiltersTheArrivingSoundWithoutAnotherPropagationDelay() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(48000, 1);
                var renderer = new SteamRenderer(simulation.context(), 48000)) {
            var direct = new SteamAudio.DirectParams();
            direct.flags = 8; // isolate occlusion from transmission and EQ smoothing
            Vec3 source = new Vec3(68.6, 0, 0); // 200 ms propagation, much longer than three blocks
            float[] input = new float[SteamRenderer.FRAME];
            java.util.Arrays.fill(input, 0.2f);
            double clear = 0, blocked = 0;
            for (int block = 0; block < 60; block++) {
                var output = renderer.render(input, direct, null, source, new SteamAudio.Space(), false, 0);
                if (block == 59) for (float[] channel : output) for (float sample : channel) clear += sample * (double) sample;
            }
            direct.occlusion = 0;
            for (int block = 0; block < 3; block++) {
                var output = renderer.render(input, direct, null, source, new SteamAudio.Space(), false, 0);
                if (block == 2) for (float[] channel : output) for (float sample : channel) blocked += sample * (double) sample;
            }
            assertTrue(clear > 1e-4);
            assertTrue(blocked < clear * 0.001, "Current occlusion must affect arriving PCM, not wait 200 ms in the propagation line");
        }
    }
    @Test
    void headTurnChangesEarBalanceWithinThreeSmallBlocksWithoutResimulation() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(48000, 1);
                var renderer = new SteamRenderer(simulation.context(), 48000)) {
            var direct = new SteamAudio.DirectParams();
            Vec3 source = new Vec3(4, 0, 0);
            var forward = new SteamAudio.Space();
            var turned = new SteamAudio.Space();
            turned.right = new SteamAudio.Vector(-1, 0, 0);
            turned.ahead = new SteamAudio.Vector(0, 0, 1);
            double[] before = new double[2], after = new double[2];
            for (int block = 0; block < 13; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin((block * input.length + i) * 0.06) * 0.2f;
                var output = renderer.render(input, direct, null, source, block < 10 ? forward : turned, false, 0);
                if (block == 9 || block == 12) {
                    double[] energy = block == 9 ? before : after;
                    for (int c = 0; c < 2; c++) for (float sample : output[c]) energy[c] += sample * (double) sample;
                }
            }
            assertTrue(before[1] > before[0] * 1.2, "Source starts on the right");
            assertTrue(after[0] > after[1] * 1.2, "A head turn must affect the next few audio blocks without waiting for rays");
            assertTrue(3.0 * SteamRenderer.FRAME / 48000 < 0.04);
        }
    }
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

    @Test
    void resumeAfterAnInaudibleGapDoesNotReplayTheOldDelayLine() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(48000, 1);
                var renderer = new SteamRenderer(simulation.context(), 48000)) {
            var direct = new SteamAudio.DirectParams();
            Vec3 source = new Vec3(68.6, 0, 0); // 200 ms of audio inside the propagation line
            float[] loud = new float[SteamRenderer.FRAME];
            java.util.Arrays.fill(loud, 0.2f);
            for (int block = 0; block < 25; block++) renderer.render(loud, direct, null, source, new SteamAudio.Space(), false, 0);
            assertTrue(renderer.resume(false), "rendered effects must be reset");
            double replay = 0;
            for (int block = 0; block < 10; block++) {
                replay += energy(renderer.render(new float[SteamRenderer.FRAME], direct, null, source, new SteamAudio.Space(), false, 0));
            }
            assertTrue(replay < 1e-10, "audio from before the gap must not replay, energy=" + replay);
        }
    }

    @Test
    void bypassedBlocksKeepThePropagationDelayRunning() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        for (boolean keepDelay : new boolean[] {true, false}) {
            try (var simulation = new SteamSimulation(48000, 1);
                    var renderer = new SteamRenderer(simulation.context(), 48000)) {
                var direct = new SteamAudio.DirectParams();
                Vec3 source = new Vec3(68.6, 0, 0);
                for (int block = 0; block < 5; block++) {
                    renderer.render(new float[SteamRenderer.FRAME], direct, null, source, new SteamAudio.Space(), false, 0);
                }
                for (int block = 0; block < 25; block++) {
                    float[] loud = new float[SteamRenderer.FRAME];
                    java.util.Arrays.fill(loud, 0.2f);
                    renderer.bypass(loud, source);
                }
                assertTrue(renderer.resume(keepDelay), "effects idled during the bypass must be reset");
                double delayed = 0;
                for (int block = 0; block < 3; block++) {
                    delayed += energy(renderer.render(new float[SteamRenderer.FRAME], direct, null, source, new SteamAudio.Space(), false, 0));
                }
                if (keepDelay) assertTrue(delayed > 1e-4, "panned audio still in flight must arrive after the switch");
                else assertTrue(delayed < 1e-10, "a cleared line must not replay, energy=" + delayed);
            }
        }
    }

    @Test
    void teleportSizedDelayChangesCrossfadeInsteadOfChirping() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(48000, 1);
                var renderer = new SteamRenderer(simulation.context(), 48000)) {
            var direct = new SteamAudio.DirectParams();
            float[][] output = null;
            for (int block = 0; block < 26; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                for (int i = 0; i < input.length; i++) input[i] = (float) Math.sin((block * input.length + i) * 0.06) * 0.2f;
                // 4 blocks away, then 68.6: the read head would sweep 9000 samples within one block.
                Vec3 source = block < 25 ? new Vec3(4, 0, 0) : new Vec3(68.6, 0, 0);
                output = renderer.render(input, direct, null, source, new SteamAudio.Space(), false, 0);
            }
            double signal = 0, roughness = 0;
            float[] right = output[1];
            for (int i = 2; i < right.length; i++) {
                double curvature = right[i] - 2 * right[i - 1] + right[i - 2];
                signal += right[i] * (double) right[i];
                roughness += curvature * curvature;
            }
            assertTrue(signal > 1e-4);
            assertTrue(roughness < signal * 0.01, "no pitch sweep across the jump, ratio=" + roughness / signal);
        }
    }

    @Test
    void limiterKneeIsTransparentBelowTheCeilingAndNeverExceedsFullScale() {
        assertEquals(0.5f, SteamRenderer.softLimit(0.5f));
        assertEquals(-0.95f, SteamRenderer.softLimit(-0.95f));
        float previous = 0.95f;
        for (float sample = 0.951f; sample < 50; sample *= 1.1f) {
            float limited = SteamRenderer.softLimit(sample);
            assertTrue(limited >= previous && limited <= 1f, sample + " -> " + limited);
            assertEquals(-limited, SteamRenderer.softLimit(-sample));
            previous = limited;
        }
    }

    private static double energy(float[][] stereo) {
        double sum = 0;
        for (float[] channel : stereo) for (float sample : channel) sum += sample * (double) sample;
        return sum;
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
