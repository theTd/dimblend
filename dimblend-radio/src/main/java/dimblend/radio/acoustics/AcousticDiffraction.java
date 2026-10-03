package dimblend.radio.acoustics;

/**
 * Approximate diffraction for the direct path: Steam Audio's volumetric occlusion instead of a
 * single source-listener ray. Sample points fill a sphere around the radio (and, when it is partly
 * hidden, one around the listener); points hidden from the sphere's centre are dropped, and
 * the share the other end can see becomes the occlusion factor. {@link #bandOcclusion} shades the
 * low band least and the high band most, so a fade also muffles.
 * <p>
 * The direct gain is {@code open + (1 - open) * transmission} per band. Past the shadow edge the
 * transmission is the wall's ({@link AcousticDirectTransmission}, a block-wide bundle around the
 * centre line), and the bundle starts by grazing the corner: short chords that pass nearly
 * everything, so the level falls over a few blocks as the chords lengthen and the visible share
 * shrinks, instead of cutting off. Before the edge nothing is on the line, and full transmission
 * would cancel the partial occlusion and leave a step at the edge in the highs; so the hidden part
 * of the sphere passes no more than {@link #edgeTransmission()}, a grazing chord of stone
 * ({@link #hiddenTransmission}). The highs already dull a little while an obstacle beside the line
 * hides part of the sphere, every band meets the shadow side's value continuously, and lows and
 * mids stay near full level up to the edge.
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
     * the lows at -6 dB, the mids at -9 dB and the highs at -12 dB (before transmission fills in).
     * No exponent is below 1: a concave curve would magnify the last few visible samples, and
     * losing the last of 16 would step the lows by several dB.
     */
    private static final float[] BAND_EXPONENT = {1, 1.5f, 2};

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

    /** The least that hides part of the sphere: a grazing chord of stone. */
    public static float[] edgeTransmission() {
        return AcousticMaterials.transmission(AcousticMaterials.STONE, AcousticMaterials.MIN_THICKNESS);
    }

    /**
     * What the hidden part of a partly occluded sphere passes, per band: the line's transmission, but
     * no more than {@link #edgeTransmission()}. Both vary continuously, and so does their minimum.
     */
    public static float[] hiddenTransmission(float[] line) {
        float[] edge = edgeTransmission();
        for (int band = 0; band < 3; band++) {
            edge[band] = Math.min(edge[band], line[band]);
        }
        return edge;
    }

    private AcousticDiffraction() { }
}
