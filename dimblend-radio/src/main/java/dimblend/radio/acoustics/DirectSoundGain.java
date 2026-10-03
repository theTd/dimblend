package dimblend.radio.acoustics;

/**
 * Separate direct-path level from spectral coloration so SDK gain smoothing cannot add a long lag.
 * <p>
 * The occlusion and transmission shading is smoothed per band, in decibels, over
 * {@link #SHADING_SECONDS}: walking past pillars, the sampled occlusion moves in whole samples and
 * the path through the stone changes steeply, and a block-to-block jump of several dB is heard as
 * a click. Distance and air absorption are continuous and are not smoothed.
 */
public final class DirectSoundGain {
    /** Time constant of the shading: a jump becomes a fade of about this length. */
    static final double SHADING_SECONDS = 0.06;
    /** Lowest shading tracked, -80 dB: the logarithm needs a floor. */
    private static final float SHADING_FLOOR = 1e-4f;
    private final SteamAudio.DirectParams equalization = new SteamAudio.DirectParams();
    private float current = Float.NaN;
    /** Weight of a block's target shading; 1 takes it as is. */
    private final float shadingWeight;
    /** Natural logarithm of the shading per band; NaN without history. */
    private final float[] shading = {Float.NaN, Float.NaN, Float.NaN};

    /** Takes each block's shading as it comes: what one simulation result implies. */
    public DirectSoundGain() {
        this.shadingWeight = 1;
        equalization.flags = 2; // normalized air/EQ bands only, unity native gain
    }

    /** Smooths the shading across consecutive blocks of {@code blockSeconds}. */
    public DirectSoundGain(double blockSeconds) {
        this.shadingWeight = (float) (1 - Math.exp(-blockSeconds / SHADING_SECONDS));
        equalization.flags = 2;
    }

    public SteamAudio.DirectParams equalization() { return equalization; }

    public float prepare(SteamAudio.DirectParams source) {
        float gain = (source.flags & 1) != 0 ? source.distance : 1;
        if ((source.flags & 4) != 0) gain *= source.directivity;
        for (int band = 0; band < 3; band++) equalization.air[band] = (source.flags & 2) != 0 ? source.air[band] : 1;
        if ((source.flags & 8) != 0) {
            // Partial (volumetric) occlusion shades highs more than lows; 0 and 1 are unchanged.
            float average = (source.transmission[0] + source.transmission[1] + source.transmission[2]) / 3;
            for (int band = 0; band < 3; band++) {
                float open = AcousticDiffraction.bandOcclusion(source.occlusion, band);
                float through = (source.flags & 16) == 0 ? 0
                        : source.transmissionType == 0 ? average : source.transmission[band];
                equalization.air[band] *= shade(band, open + (1 - open) * through);
            }
        } else {
            java.util.Arrays.fill(shading, Float.NaN);
        }
        float peak = Math.max(equalization.air[0], Math.max(equalization.air[1], equalization.air[2]));
        if (!Float.isFinite(gain) || !Float.isFinite(peak)) throw new IllegalArgumentException("Invalid direct acoustic gain");
        if (peak < Float.MIN_NORMAL) {
            gain = 0;
            java.util.Arrays.fill(equalization.air, 1);
        } else {
            gain *= peak;
            // Same normalization as Steam Audio EQEffect; it retains the three-band coloration.
            for (int band = 0; band < 3; band++) equalization.air[band] = Math.max(0.0625f, equalization.air[band] / peak);
        }
        return gain;
    }

    private float shade(int band, float target) {
        if (shadingWeight >= 1) return target;
        float level = (float) Math.log(Math.max(SHADING_FLOOR, target));
        shading[band] = Float.isNaN(shading[band]) ? level : shading[band] + shadingWeight * (level - shading[band]);
        return (float) Math.exp(shading[band]);
    }

    /** The next block starts at its own target instead of ramping from a stale level. */
    public void reset() {
        current = Float.NaN;
        java.util.Arrays.fill(shading, Float.NaN);
    }

    /** Settle in at most 5 ms, independent of sample rate or block size. */
    public void apply(float[] samples, float target, int rate) {
        if (Float.isNaN(current)) current = target;
        int ramp = Math.min(samples.length, Math.max(1, Math.round(rate * 0.005f)));
        float start = current;
        for (int i = 0; i < samples.length; i++) {
            float gain = i < ramp ? start + (target - start) * (i + 1f) / ramp : target;
            samples[i] *= gain;
        }
        current = target;
    }
}
