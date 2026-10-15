package dimblend.radio.client;

import dimblend.radio.RadioLiveSettings;
import dimblend.radio.RadioServerConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioReceptionNoiseTest {
    private static final int RATE = 44100;
    private static final int BLOCK = 1024;

    private static ByteBuffer tone(int frames, int offset, double amplitude) {
        ByteBuffer pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            pcm.putShort((short) Math.round(amplitude * 32767 * Math.sin(2 * Math.PI * 440 * (offset + i) / RATE)));
        }
        return pcm.flip();
    }

    private static float[] samples(ByteBuffer pcm) {
        float[] out = new float[pcm.limit() / 2];
        for (int i = 0; i < out.length; i++) out[i] = pcm.getShort(i * 2) / 32768f;
        return out;
    }

    /** Feeds {@code seconds} of a 440 Hz tone through {@code noise}; returns input and output. */
    private static float[][] run(RadioReceptionNoise noise, RadioReception reception,
            double seconds, double amplitude, float noiseScale) {
        int total = (int) (seconds * RATE) / BLOCK * BLOCK;
        float[] in = new float[total], out = new float[total];
        for (int at = 0; at < total; at += BLOCK) {
            ByteBuffer block = tone(BLOCK, at, amplitude);
            System.arraycopy(samples(block), 0, in, at, BLOCK);
            noise.process(block, 1, reception, noiseScale);
            System.arraycopy(samples(block), 0, out, at, BLOCK);
        }
        return new float[][] {in, out};
    }

    private static double rms(float[] a, float[] minus) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - (minus == null ? 0 : minus[i]);
            sum += d * d;
        }
        return Math.sqrt(sum / a.length);
    }

    private static double correlation(float[] a, float[] b) {
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return ab / Math.sqrt(aa * bb);
    }

    private static RadioReceptionNoise noise(List<float[]> clips) {
        return new RadioReceptionNoise(RATE, 42, rate -> clips.isEmpty()
                ? List.of() : List.of(new RadioEndVoices.WeightedClips(1, clips)));
    }

    private static int firstNonZero(float[] samples) {
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] != 0) return i;
        }
        return -1;
    }

    @Test
    void clearReceptionLeavesThePcmUntouched() {
        float[][] io = run(noise(List.of()), RadioReception.CLEAR, 2, 0.5, 1.0f);
        assertArrayEquals(io[0], io[1]);
    }

    @Test
    void faintStaticKeepsTheTrackAndAddsQuietNoise() {
        float[][] io = run(noise(List.of()), RadioReception.FAINT_STATIC, 10, 0.5, 1.0f);
        double residual = rms(io[1], io[0]);
        assertTrue(residual > 0.003, "static is audible: " + residual);
        assertTrue(residual < 0.1, "static stays faint against a -9 dBFS tone: " + residual);
        assertTrue(correlation(io[0], io[1]) > 0.9, "the track still comes through");
    }

    @Test
    void staticOnlyDropsTheTrackForNoise() {
        float[][] io = run(noise(List.of()), RadioReception.STATIC_ONLY, 10, 0.5, 1.0f);
        double level = rms(io[1], null);
        assertTrue(level > 0.01 && level < 0.25, "static level: " + level);
        assertTrue(Math.abs(correlation(io[0], io[1])) < 0.05, "no trace of the track");
    }

    @Test
    void voidscapeIsNoisyFromTheFirstAudioBlockEvenForASilentTrack() {
        RadioReceptionNoise noise = noise(List.of());
        ByteBuffer first = tone(BLOCK, 0, 0);
        noise.process(first, 1, RadioReception.STATIC_ONLY, 1.0f);
        assertTrue(rms(samples(first), null) > 0.01, "static must not wait for warmup or a pathing bake");
    }

    @Test
    void leavingStaticGlidesAndThenPassesThroughExactlyAgain() {
        RadioReceptionNoise noise = noise(List.of());
        float[][] before = run(noise, RadioReception.STATIC_ONLY, 2, 0.5, 1.0f);
        float[][] after = run(noise, RadioReception.CLEAR, 6, 0.5, 1.0f);
        // The first blocks back still carry the static they leave, and little of the track.
        float[] firstIn = Arrays.copyOf(after[0], BLOCK), firstOut = Arrays.copyOf(after[1], BLOCK);
        assertTrue(rms(firstOut, firstIn) > 0.1, "no step back to the clean track");
        assertTrue(Math.abs(after[1][0] - before[1][before[1].length - 1]) < 0.5, "no jump at the change");
        float[] tailIn = Arrays.copyOfRange(after[0], after[0].length - RATE, after[0].length);
        float[] tailOut = Arrays.copyOfRange(after[1], after[1].length - RATE, after[1].length);
        assertArrayEquals(tailIn, tailOut, "settled back to bit-exact pass-through");
    }

    @Test
    void endReceptionLetsFaintVoicesThroughNowAndThen() {
        float[] voice = new float[RATE];
        for (int i = 0; i < voice.length; i++) voice[i] = (float) Math.sin(2 * Math.PI * 1000 * i / RATE);
        float[][] io = run(noise(List.of(RadioEndVoices.prepareClip(voice, RATE))),
                RadioReception.ENDER_VOICES, 40, 0, 1.0f);
        double peak = 0;
        for (float sample : io[1]) peak = Math.max(peak, Math.abs(sample));
        assertTrue(peak > 0.02, "a voice came through within 40 s: " + peak);
        assertTrue(peak < 0.45, "the voice leaves mixing headroom: " + peak);
        // The default frequency is twice the original cadence, so the first wait is at least five seconds.
        assertEquals(0, rms(Arrays.copyOf(io[1], 5 * RATE), null), 1e-9);
    }

    @Test
    void endVoiceFrequencyTwoHalvesTheOriginalFirstWaitAndZeroDisablesVoices() {
        float[] voice = new float[RATE];
        Arrays.fill(voice, 0.1f);
        try {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY, 1);
            int original = firstNonZero(run(noise(List.of(voice)), RadioReception.ENDER_VOICES,
                    31, 0, 1.0f)[1]);
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY, 2);
            int doubled = firstNonZero(run(noise(List.of(voice)), RadioReception.ENDER_VOICES,
                    31, 0, 1.0f)[1]);
            assertTrue(original >= 0, "the original cadence plays a voice within 31 seconds");
            assertEquals(original / 2, doubled, "frequency 2 halves the same random first wait");
            assertEquals(2.0f, RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY);

            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY, 0);
            assertEquals(-1, firstNonZero(run(noise(List.of(voice)), RadioReception.ENDER_VOICES,
                    31, 0, 1.0f)[1]), "frequency 0 disables new voices");
        } finally {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY, RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY);
        }
    }

    @Test
    void endVoiceSelectionUsesSevenToOneEndermanShulkerWeight() {
        assertEquals(7, RadioEndVoices.ENDERMAN_WEIGHT);
        assertEquals(1, RadioEndVoices.SHULKER_WEIGHT);
        float[] enderman1 = {0.1f};
        float[] enderman2 = {0.2f};
        float[] enderman3 = {0.3f};
        float[] shulker = {0.4f};
        var groups = List.of(
                new RadioEndVoices.WeightedClips(RadioEndVoices.ENDERMAN_WEIGHT,
                        List.of(enderman1, enderman2, enderman3)),
                new RadioEndVoices.WeightedClips(RadioEndVoices.SHULKER_WEIGHT, List.of(shulker)));
        assertSame(enderman1, RadioReceptionNoise.selectClip(groups, 0, 0));
        assertSame(enderman3, RadioReceptionNoise.selectClip(groups, 0.874999, 0.999999));
        assertSame(shulker, RadioReceptionNoise.selectClip(groups, 0.875, 0),
                "three 7/3-weighted enderman variants must not leak across the group boundary");
        assertSame(shulker, RadioReceptionNoise.selectClip(groups, 0.999999, 0));

        var endermanOnly = groups.subList(0, 1);
        assertSame(enderman2, RadioReceptionNoise.selectClip(endermanOnly, 0.999999, 0.5),
                "a missing shulker group leaves enderman variants playable");
    }

    @Test
    void endVoiceVolumeZeroSchedulesNoVoicesAndDoesNotDuckTheMusic() {
        float[] voice = new float[RATE / 2];
        Arrays.fill(voice, 0.2f);
        try {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY, 0);
            // ENDER_VOICES 无 hiss/crackle/fading：音量 0 不排 clip → 无人声也不 duck，输出逐位还原
            float[][] io = run(noise(List.of(voice)), RadioReception.ENDER_VOICES, 35, 0.5, 1.0f);
            assertArrayEquals(io[0], io[1],
                    "voice volume 0 schedules no clip, so the music is not ducked either");
        } finally {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY, RadioLiveSettings.DEFAULT_END_VOICE_VOLUME);
        }
    }

    @Test
    void endVoiceVolumeDefaultIsOnePointFiveTimesTheFormerHardCodedGain() {
        assertEquals(0.75f, RadioLiveSettings.DEFAULT_END_VOICE_VOLUME);
        float[] voice = new float[RATE];
        Arrays.fill(voice, 0.1f);
        try {
            // 同 seed → 同倒计时同 clip，voice glide 确定性：峰值比即增益比
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY, 0.5);
            double former = peak(run(noise(List.of(voice)), RadioReception.ENDER_VOICES, 40, 0, 1.0f)[1]);
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY, RadioLiveSettings.DEFAULT_END_VOICE_VOLUME);
            double raised = peak(run(noise(List.of(voice)), RadioReception.ENDER_VOICES, 40, 0, 1.0f)[1]);
            assertTrue(former > 0.02, "a voice came through at the former 0.5 gain: " + former);
            assertEquals(former * 1.5, raised, former * 0.05,
                    "the default 0.75 is 1.5 times the former hard-coded 0.5");
        } finally {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY,
                    RadioLiveSettings.DEFAULT_END_VOICE_FREQUENCY, RadioLiveSettings.DEFAULT_END_VOICE_VOLUME);
        }
    }

    private static double peak(float[] samples) {
        double peak = 0;
        for (float sample : samples) peak = Math.max(peak, Math.abs(sample));
        return peak;
    }

    @Test
    void endReceptionStaysSilentWhileVoicesLoad() {
        float[][] io = run(noise(List.of()), RadioReception.ENDER_VOICES, 40, 0, 1.0f);
        assertEquals(0, rms(io[1], null), 1e-9);
    }

    @Test
    void zeroNoiseVolumePlaysEveryRadioClean() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        byte[] silence = new byte[RATE * 2];
        try {
            RadioServerConfig.apply(0.0, RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
            assertEquals(0.0f, RadioLiveSettings.receptionNoiseVolume(), 1e-9);
            var feed = RadioPcmFeed.register(format, silence, 0);
            feed.setReception(RadioReception.STATIC_ONLY);
            try (var stream = RadioPcmFeed.open(feed.id())) {
                ByteBuffer out = stream.readAll();
                while (out.hasRemaining()) assertEquals(0, out.get());
            }
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
            assertEquals(1.0f, RadioLiveSettings.receptionNoiseScale(), 1e-9,
                    "the default volume is the former always-on level");
            var noisy = RadioPcmFeed.register(format, silence, 0);
            noisy.setReception(RadioReception.STATIC_ONLY);
            try (var stream = RadioPcmFeed.open(noisy.id())) {
                ByteBuffer out = stream.readAll();
                boolean heard = false;
                while (out.hasRemaining()) heard |= out.get() != 0;
                assertTrue(heard);
            }
        } finally {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE_VOLUME,
                    RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
        }
    }

    @Test
    void fullVolumeDoublesTheStaticAroundTheFormerLevel() {
        float[][] former = run(noise(List.of()), RadioReception.STATIC_ONLY, 10, 0.5, 1.0f);
        float[][] full = run(noise(List.of()), RadioReception.STATIC_ONLY, 10, 0.5, 2.0f);
        double formerLevel = rms(former[1], null);
        assertEquals(2 * formerLevel, rms(full[1], null), formerLevel * 0.05,
                "volume 1.00 is twice the static of the former on level");
    }

    @Test
    void maxVolumeNeverExceedsTheSampleBounds() {
        float[][] io = run(noise(List.of()), RadioReception.STATIC_ONLY, 10, 0.5, 4.0f);
        for (float sample : io[1]) {
            assertTrue(sample >= -1.0f && sample <= 32767 / 32768f,
                    "volume 2.00 (scale 4) output stays within the clamped range: " + sample);
        }
    }

    @Test
    void voiceClipsAreResampledToThePlaybackRate() {
        float[] clip = new float[44100];
        assertSame(clip, RadioEndVoices.resample(clip, 44100, 44100));
        assertEquals(48000, RadioEndVoices.resample(clip, 44100, 48000).length);
        assertEquals(22050, RadioEndVoices.resample(clip, 44100, 22050).length);
    }

    @Test
    void quietVoiceIsAudibleOverMusicAndMusicRecoversAfterTheClip() {
        float[] quiet = new float[RATE / 2];
        for (int i = 0; i < quiet.length; i++) {
            quiet[i] = 0.04f * (float) Math.sin(2 * Math.PI * 1000 * i / RATE);
        }
        float[] prepared = RadioEndVoices.prepareClip(quiet, RATE);
        float[][] io = run(noise(List.of(prepared)), RadioReception.ENDER_VOICES, 35, 0.5, 1.0f);
        double voicePeak = 0;
        int voicedStart = -1;
        for (int i = 5 * RATE; i < io[1].length; i++) {
            if (Math.abs(io[1][i] - io[0][i]) > 0.001) { voicedStart = i; break; }
        }
        assertTrue(voicedStart >= 0, "a voice should play");
        double musicProjection = 0, inputEnergy = 0;
        for (int i = voicedStart + RATE / 5; i < voicedStart + RATE / 3; i++) {
            double voice = io[1][i] - 0.65 * io[0][i];
            voicePeak = Math.max(voicePeak, Math.abs(voice));
            musicProjection += io[1][i] * io[0][i];
            inputEnergy += io[0][i] * io[0][i];
        }
        assertTrue(voicePeak > 0.08, "quiet source clips need a perceptible mix level: " + voicePeak);
        assertEquals(0.65, musicProjection / inputEnergy, 0.04, "music yields briefly to the voice");
        assertArrayEquals(Arrays.copyOfRange(io[0], 34 * RATE, 35 * RATE - BLOCK),
                Arrays.copyOfRange(io[1], 34 * RATE, 35 * RATE - BLOCK), 1.0e-4f,
                "the music returns to its original level");
    }

    @Test
    void voicePreparationBoundsPeaksAndDoesNotBoostSilenceOrMutateTheSource() {
        float[] source = new float[RATE];
        for (int i = 0; i < source.length; i++) {
            source[i] = (float) (0.9 * Math.sin(2 * Math.PI * 1000 * i / RATE));
        }
        float[] original = source.clone();
        float[] prepared = RadioEndVoices.prepareClip(source, RATE);
        assertArrayEquals(original, source);
        for (float sample : prepared) assertTrue(Math.abs(sample) <= 0.850001f);
        assertEquals(0.18, rms(prepared, null), 1.0e-5);
        assertArrayEquals(new float[BLOCK], RadioEndVoices.prepareClip(new float[BLOCK], RATE));
        assertEquals(0, RadioEndVoices.prepareClip(new float[0], RATE).length);
    }
}
