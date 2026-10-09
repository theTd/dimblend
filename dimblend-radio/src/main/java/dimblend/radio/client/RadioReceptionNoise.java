package dimblend.radio.client;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntFunction;

/**
 * One playback's reception noise, mixed into the radio's 16-bit little-endian PCM in place before
 * spatialization, so the static comes out of the radio and is occluded and echoed with the track.
 * <p>
 * Poor reception is band-limited hiss (350 Hz to 4.5 kHz, a small speaker's band), crackles
 * (noise bursts decaying in about 2 ms) and slow signal fades, in which the track dips while the
 * hiss swells. Voices are clips band-limited the same way, as if overheard on the air. Changing
 * {@link RadioReception} glides over about a third of a second; a stream that is and stays
 * {@link RadioReception#CLEAR} is left untouched.
 */
final class RadioReceptionNoise {
    /** Time constant of reception changes. */
    private static final float GLIDE_SECONDS = 0.35f;
    /** Time constant of the signal strength following its target. */
    private static final float FADE_SECONDS = 0.25f;
    private static final float CRACKLE_SECONDS = 0.002f;
    private static final float VOICE_GAIN = 0.5f;
    private static final float VOICE_DUCK = 0.35f;
    /** At rest: within this of the targets, the glide snaps onto them. */
    private static final float SETTLED = 1.0e-4f;

    private final float rate;
    private final IntFunction<List<float[]>> voiceClips;
    // Per-sample coefficients near 1e-4: the gliding state is double, as float would stall about
    // 1e-3 short of its target (the step falls below float precision) and never settle.
    private final double glide, fadeFollow, duckFollow;
    private final float crackleDecay, highPass, lowPass;
    private long seed;
    private boolean started;
    // Gliding mix, towards the current reception's levels.
    private double music = 1, hiss, crackle, fading, voice;
    // Hiss band-pass state.
    private float hissIn, hissHigh, hissOut;
    private float crackleEnvelope;
    // Signal strength: 1 clear, 0 lost.
    private double strength = 1, strengthTarget = 1;
    private int strengthHold;
    // Voices.
    private float[] clip;
    private int clipPosition, voiceCountdown = -1;
    private double voiceDuck;

    /**
     * @param voiceClips filtered and leveled mono clips at the given sample rate; empty while they load
     */
    RadioReceptionNoise(float sampleRate, long seed, IntFunction<List<float[]>> voiceClips) {
        this.rate = sampleRate;
        this.seed = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.voiceClips = voiceClips;
        glide = follow(GLIDE_SECONDS);
        fadeFollow = follow(FADE_SECONDS);
        duckFollow = follow(0.06f);
        crackleDecay = (float) Math.exp(-1.0 / (CRACKLE_SECONDS * sampleRate));
        highPass = (float) (1.0 / (1.0 + 2 * Math.PI * 350 / sampleRate));
        double lowRc = 1.0 / (2 * Math.PI * 4500);
        lowPass = (float) ((1.0 / sampleRate) / (lowRc + 1.0 / sampleRate));
    }

    private double follow(float seconds) {
        return 1 - Math.exp(-1.0 / (seconds * rate));
    }

    /**
     * Mixes this block's noise into {@code pcm} (positions 0 to its limit, absolute access).
     *
     * @param channels interleaved channels; each gets the same noise
     * @param noiseScale the static, crackle and voice amplitude: 1 at the former always-on volume
     */
    void process(ByteBuffer pcm, int channels, RadioReception reception, float noiseScale) {
        int frames = pcm.limit() / (2 * channels);
        if (frames == 0) return;
        if (!started) {
            // A stream starts in its place's reception, not gliding in from clear.
            started = true;
            music = reception.music;
            hiss = reception.hiss;
            crackle = reception.crackle;
            fading = reception.fading;
            voice = reception.voices ? 1 : 0;
        }
        if (reception == RadioReception.CLEAR && atRest()) return;
        float voiceTarget = reception.voices ? 1 : 0;
        float crackleChance = reception.crackleRate / rate;
        for (int frame = 0; frame < frames; frame++) {
            music += (reception.music - music) * glide;
            hiss += (reception.hiss - hiss) * glide;
            crackle += (reception.crackle - crackle) * glide;
            fading += (reception.fading - fading) * glide;
            voice += (voiceTarget - voice) * glide;

            float lost = (float) (1 - fadeStrength());
            float voiceSample = voiceSample(reception.voices);
            voiceDuck += ((clip != null ? voice : 0) - voiceDuck) * duckFollow;
            float musicGain = (float) (music * (1 - fading * lost) * (1 - VOICE_DUCK * voiceDuck));
            float noise = (float) ((hissSample() * hiss * (1 + fading * (2 * lost - 1))
                    + crackleSample(crackleChance) * crackle + voiceSample * VOICE_GAIN * voice) * noiseScale);

            for (int channel = 0; channel < channels; channel++) {
                int index = (frame * channels + channel) * 2;
                float sample = pcm.getShort(index) / 32768f * musicGain + noise;
                pcm.putShort(index, (short) Math.round(Math.max(-1, Math.min(32767 / 32768f, sample)) * 32768));
            }
        }
        if (reception == RadioReception.CLEAR) settle();
    }

    private boolean atRest() {
        return music == 1 && hiss == 0 && crackle == 0 && fading == 0 && voice == 0 && clip == null
                && crackleEnvelope == 0 && voiceDuck == 0;
    }

    /** Once a glide back to clear has (all but) finished, the stream is left alone again. */
    private void settle() {
        if (1 - music < SETTLED && hiss < SETTLED && crackle < SETTLED && fading < SETTLED && voice < SETTLED
                && clip == null && crackleEnvelope < SETTLED && voiceDuck < SETTLED) {
            music = 1;
            hiss = crackle = fading = voice = crackleEnvelope = 0;
            voiceDuck = 0;
        }
    }

    /** Strength holds a target for 0.4 to 2.5 s; targets favour a good signal with occasional deep dips. */
    private double fadeStrength() {
        if (--strengthHold <= 0) {
            float dip = uniform();
            strengthTarget = 1 - dip * dip * dip;
            strengthHold = Math.round((0.4f + 2.1f * uniform()) * rate);
        }
        strength += (strengthTarget - strength) * fadeFollow;
        return strength;
    }

    private float hissSample() {
        float white = (uniform() * 2 - 1) * 1.7320508f;
        return bandPass(white);
    }

    private float crackleSample(float chance) {
        if (chance > 0 && uniform() < chance) {
            crackleEnvelope = Math.max(crackleEnvelope, 0.25f + 0.75f * uniform());
        }
        if (crackleEnvelope == 0) return 0;
        float sample = crackleEnvelope * (uniform() * 2 - 1);
        crackleEnvelope *= crackleDecay;
        if (crackleEnvelope < 1.0e-5f) crackleEnvelope = 0;
        return sample;
    }

    /** The playing voice clip's next sample; between clips, counts down to the next one. */
    private float voiceSample(boolean wanted) {
        if (clip == null) {
            if (!wanted) {
                voiceCountdown = -1;
                return 0;
            }
            if (voiceCountdown < 0) voiceCountdown = seconds(10, 30);
            if (voiceCountdown-- > 0) return 0;
            List<float[]> clips = voiceClips.apply(Math.round(rate));
            if (clips.isEmpty()) {
                voiceCountdown = seconds(1, 1);
                return 0;
            }
            clip = clips.get((int) (uniform() * clips.size()));
            clipPosition = 0;
            voiceCountdown = seconds(25, 70);
        }
        float sample = clip[clipPosition++];
        if (clipPosition >= clip.length) clip = null;
        return sample;
    }

    private int seconds(float from, float to) {
        return Math.round((from + (to - from) * uniform()) * rate);
    }

    /** First-order high pass then low pass for generated hiss. */
    private float bandPass(float input) {
        hissHigh = highPass * (hissHigh + input - hissIn);
        hissIn = input;
        hissOut += (hissHigh - hissOut) * lowPass;
        return hissOut;
    }

    /** Uniform in [0, 1), from xorshift64*. */
    private float uniform() {
        seed ^= seed >>> 12;
        seed ^= seed << 25;
        seed ^= seed >>> 27;
        return ((seed * 0x2545F4914F6CDD1DL) >>> 40) * 0x1.0p-24f;
    }
}
