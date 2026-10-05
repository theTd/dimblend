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
    private static float[][] run(RadioReceptionNoise noise, RadioReception reception, double seconds, double amplitude) {
        int total = (int) (seconds * RATE) / BLOCK * BLOCK;
        float[] in = new float[total], out = new float[total];
        for (int at = 0; at < total; at += BLOCK) {
            ByteBuffer block = tone(BLOCK, at, amplitude);
            System.arraycopy(samples(block), 0, in, at, BLOCK);
            noise.process(block, 1, reception);
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
        return new RadioReceptionNoise(RATE, 42, rate -> clips);
    }

    @Test
    void clearReceptionLeavesThePcmUntouched() {
        float[][] io = run(noise(List.of()), RadioReception.CLEAR, 2, 0.5);
        assertArrayEquals(io[0], io[1]);
    }

    @Test
    void faintStaticKeepsTheTrackAndAddsQuietNoise() {
        float[][] io = run(noise(List.of()), RadioReception.FAINT_STATIC, 10, 0.5);
        double residual = rms(io[1], io[0]);
        assertTrue(residual > 0.003, "static is audible: " + residual);
        assertTrue(residual < 0.1, "static stays faint against a -9 dBFS tone: " + residual);
        assertTrue(correlation(io[0], io[1]) > 0.9, "the track still comes through");
    }

    @Test
    void staticOnlyDropsTheTrackForNoise() {
        float[][] io = run(noise(List.of()), RadioReception.STATIC_ONLY, 10, 0.5);
        double level = rms(io[1], null);
        assertTrue(level > 0.01 && level < 0.25, "static level: " + level);
        assertTrue(Math.abs(correlation(io[0], io[1])) < 0.05, "no trace of the track");
    }

    @Test
    void voidscapeIsNoisyFromTheFirstAudioBlockEvenForASilentTrack() {
        RadioReceptionNoise noise = noise(List.of());
        ByteBuffer first = tone(BLOCK, 0, 0);
        noise.process(first, 1, RadioReception.STATIC_ONLY);
        assertTrue(rms(samples(first), null) > 0.01, "static must not wait for warmup or a pathing bake");
    }

    @Test
    void leavingStaticGlidesAndThenPassesThroughExactlyAgain() {
        RadioReceptionNoise noise = noise(List.of());
        float[][] before = run(noise, RadioReception.STATIC_ONLY, 2, 0.5);
        float[][] after = run(noise, RadioReception.CLEAR, 6, 0.5);
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
        float[][] io = run(noise(List.of(RadioEnderVoices.prepareClip(voice, RATE))),
                RadioReception.ENDER_VOICES, 40, 0);
        double peak = 0;
        for (float sample : io[1]) peak = Math.max(peak, Math.abs(sample));
        assertTrue(peak > 0.02, "a voice came through within 40 s: " + peak);
        assertTrue(peak < 0.45, "the voice leaves mixing headroom: " + peak);
        // The first one waits at least ten seconds.
        assertEquals(0, rms(Arrays.copyOf(io[1], 10 * RATE), null), 1e-9);
    }

    @Test
    void endReceptionStaysSilentWhileVoicesLoad() {
        float[][] io = run(noise(List.of()), RadioReception.ENDER_VOICES, 40, 0);
        assertEquals(0, rms(io[1], null), 1e-9);
    }

    @Test
    void switchingNoiseOffPlaysEveryRadioClean() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        byte[] silence = new byte[RATE * 2];
        try {
            RadioServerConfig.apply(false, RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
            var feed = RadioPcmFeed.register(format, silence, 0);
            feed.setReception(RadioReception.STATIC_ONLY);
            try (var stream = RadioPcmFeed.open(feed.id())) {
                ByteBuffer out = stream.readAll();
                while (out.hasRemaining()) assertEquals(0, out.get());
            }
            RadioServerConfig.apply(true, RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
            var noisy = RadioPcmFeed.register(format, silence, 0);
            noisy.setReception(RadioReception.STATIC_ONLY);
            try (var stream = RadioPcmFeed.open(noisy.id())) {
                ByteBuffer out = stream.readAll();
                boolean heard = false;
                while (out.hasRemaining()) heard |= out.get() != 0;
                assertTrue(heard);
            }
        } finally {
            RadioServerConfig.apply(RadioLiveSettings.DEFAULT_RECEPTION_NOISE, RadioLiveSettings.DEFAULT_ACOUSTIC_INTENSITY);
        }
    }

    @Test
    void voiceClipsAreResampledToThePlaybackRate() {
        float[] clip = new float[44100];
        assertSame(clip, RadioEnderVoices.resample(clip, 44100, 44100));
        assertEquals(48000, RadioEnderVoices.resample(clip, 44100, 48000).length);
        assertEquals(22050, RadioEnderVoices.resample(clip, 44100, 22050).length);
    }

    @Test
    void quietVoiceIsAudibleOverMusicAndMusicRecoversAfterTheClip() {
        float[] quiet = new float[RATE / 2];
        for (int i = 0; i < quiet.length; i++) {
            quiet[i] = 0.04f * (float) Math.sin(2 * Math.PI * 1000 * i / RATE);
        }
        float[] prepared = RadioEnderVoices.prepareClip(quiet, RATE);
        float[][] io = run(noise(List.of(prepared)), RadioReception.ENDER_VOICES, 35, 0.5);
        double voicePeak = 0;
        int voicedStart = -1;
        for (int i = 10 * RATE; i < io[1].length; i++) {
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
        float[] prepared = RadioEnderVoices.prepareClip(source, RATE);
        assertArrayEquals(original, source);
        for (float sample : prepared) assertTrue(Math.abs(sample) <= 0.850001f);
        assertEquals(0.18, rms(prepared, null), 1.0e-5);
        assertArrayEquals(new float[BLOCK], RadioEnderVoices.prepareClip(new float[BLOCK], RATE));
        assertEquals(0, RadioEnderVoices.prepareClip(new float[0], RATE).length);
    }
}
