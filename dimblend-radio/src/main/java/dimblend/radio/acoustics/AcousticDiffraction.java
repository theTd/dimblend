package dimblend.radio.acoustics;

/**
 * Approximate diffraction for the direct path: Steam Audio's volumetric occlusion instead of a
 * single source-listener ray. Sample points fill a sphere around the radio (and, when that finds
 * the path blocked, around the listener); points hidden from the sphere's centre are dropped, and
 * the share the other end can see becomes the occlusion factor. Walking behind a corner or past a
 * doorway within {@link #radius()} of either end then fades the dry sound instead of cutting it,
 * and {@link #bandOcclusion} shades the low band least and the high band most, so the fade also
 * muffles. The sphere is sampled even when the centre line is clear: about half of it is visible
 * on either side of the shadow boundary, so the level has no step there, and an obstacle beside
 * (not on) the line takes a little off, much as one inside the first Fresnel zone does.
 * <p>
 * This is no path search: the sound keeps its true direction, and an opening further than the
 * radius from both ends does not help. {@code -Ddimblend.radio.acoustic.diffraction=0} restores the
 * single occlusion ray.
 */
public final class AcousticDiffraction {
    /** Most volumetric samples the direct simulator is created for. */
    public static final int MAX_SAMPLES = 32;
    private static final AcousticTuningProperty RADIUS =
            new AcousticTuningProperty("dimblend.radio.acoustic.diffraction", 2, 8);
    private static final AcousticTuningProperty SAMPLES =
            new AcousticTuningProperty("dimblend.radio.acoustic.diffraction.samples", 16, MAX_SAMPLES);
    /**
     * Exponent per band (low/mid/high) applied to the visible share: half the sphere in view keeps
     * the lows at -3 dB, the mids at -6 dB and the highs at -12 dB.
     */
    private static final float[] BAND_EXPONENT = {0.5f, 1, 2};

    /** Sample sphere radius in blocks; 0 disables volumetric occlusion. */
    public static float radius() {
        return RADIUS.value();
    }

    /** Samples per sphere, at least 2 (one of them is the centre itself). */
    public static int samples() {
        return Math.max(2, Math.round(SAMPLES.value()));
    }

    /** The unoccluded share of band {@code band} for an occlusion factor in [0, 1]. */
    public static float bandOcclusion(float occlusion, int band) {
        if (occlusion >= 1) return 1;
        if (occlusion <= 0) return 0;
        return (float) Math.pow(occlusion, BAND_EXPONENT[band]);
    }

    private AcousticDiffraction() { }
}
