package dimblend.radio.acoustics;

/** Separate direct-path level from spectral coloration so SDK gain smoothing cannot add a long lag. */
public final class DirectSoundGain {
    private final SteamAudio.DirectParams equalization = new SteamAudio.DirectParams();
    private float current = Float.NaN;

    public DirectSoundGain() { equalization.flags = 2; } // normalized air/EQ bands only, unity native gain

    public SteamAudio.DirectParams equalization() { return equalization; }

    public float prepare(SteamAudio.DirectParams source) {
        float gain = (source.flags & 1) != 0 ? source.distance : 1;
        if ((source.flags & 4) != 0) gain *= source.directivity;
        for (int band = 0; band < 3; band++) equalization.air[band] = (source.flags & 2) != 0 ? source.air[band] : 1;
        if ((source.flags & 8) != 0) {
            if ((source.flags & 16) == 0) gain *= source.occlusion;
            else if (source.transmissionType == 0) {
                float transmission = (source.transmission[0] + source.transmission[1] + source.transmission[2]) / 3;
                gain *= source.occlusion + (1 - source.occlusion) * transmission;
            } else {
                for (int band = 0; band < 3; band++)
                    equalization.air[band] *= source.occlusion + (1 - source.occlusion) * source.transmission[band];
            }
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

    /** The next block starts at its own target instead of ramping from a stale level. */
    public void reset() { current = Float.NaN; }

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
