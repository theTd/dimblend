package dimblend.radio.acoustics;

import java.util.function.DoubleUnaryOperator;

/**
 * Baked pathing (sound bending around corners and through openings, found on a probe graph): the
 * settings bake and lookup share, and the shaping that turns Steam Audio's raw path into a
 * {@link PathingField} that adds to the direct sound instead of doubling it.
 * <p>
 * Steam Audio reports the straight line as a path whenever the listener sees the source, and its
 * coefficients fall off as 1 / path length. The direct path already covers the visible share
 * (with its own volumetric occlusion and the session's distance curve), so the path is weighted by
 * the hidden share per band and moved onto the same distance curve.
 */
public final class AcousticPathing {
    /** Points sampled around a probe (and the source and listener); the square is traced between two. */
    public static final int VISIBILITY_SAMPLES = 4;
    /** Radius in blocks of the sphere those points fill. */
    public static final float SAMPLE_RADIUS = 1;
    /** Share of the sample rays that must be clear for two points to see each other. */
    public static final float VISIBILITY_THRESHOLD = 0.1f;
    /** Probes further apart than this are not linked directly, even in open air. */
    public static final float VISIBILITY_RANGE = 48;
    /** A radio's probes are placed within this many blocks of it. */
    public static final int REGION_RADIUS = 64;
    /** The path fades out over the last this many blocks before the listener leaves the probes. */
    private static final double EDGE_FADE = 8;
    /** The W coefficient of a unit plane wave: Steam Audio's SH are scaled by 1 / path length times this. */
    static final double W_UNIT = 0.28209479177387814;
    /** Paths quieter than this (amplitude) are dropped. */
    private static final double SILENT = 1e-4;

    /**
     * How much of the path to keep with the listener this far from the radio: all of it inside the
     * probe region, fading to none at its edge, so walking out of it does not cut the path off.
     */
    public static float coverage(double listenerToRadio) {
        return (float) Math.max(0, Math.min(1, (REGION_RADIUS - listenerToRadio) / EDGE_FADE));
    }

    /**
     * @param eq Steam Audio's per-band path gains
     * @param sh Steam Audio's world-space coefficients (W, Y, Z, X)
     * @param occlusion the visible share of the direct path, 0 hidden to 1 in view
     * @param coverage a further gain, 0 to 1 (see {@link #coverage})
     * @param distanceGain the session's loudness curve over path length in blocks
     * @return the path to render, or null when nothing of it is left to hear
     */
    public static PathingField shape(float[] eq, float[] sh, float occlusion, float coverage, DoubleUnaryOperator distanceGain) {
        double w = sh[0];
        if (!(w > 1e-9) || !Float.isFinite(sh[1]) || !Float.isFinite(sh[2]) || !Float.isFinite(sh[3])) return null;
        double length = W_UNIT / w;
        double scale = distanceGain.applyAsDouble(length) * length;
        float[] gains = new float[3];
        double loudest = 0;
        for (int band = 0; band < 3; band++) {
            float hidden = 1 - AcousticDiffraction.bandOcclusion(occlusion, band);
            // UTD overshoots a little at the shadow edge; the path never boosts.
            float gain = Float.isFinite(eq[band]) ? Math.max(0, Math.min(1, eq[band])) : 0;
            gains[band] = gain * hidden * Math.max(0, Math.min(1, coverage));
            loudest = Math.max(loudest, gains[band]);
        }
        if (!(scale > 0) || loudest * scale * W_UNIT / length < SILENT) return null;
        float[] coefficients = new float[4];
        for (int i = 0; i < 4; i++) coefficients[i] = (float) (sh[i] * scale);
        return new PathingField(gains, coefficients, (float) length);
    }

    private AcousticPathing() { }
}
