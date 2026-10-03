package dimblend.radio.acoustics;

/**
 * The diffracted path from a radio to the listener, ready for the path effect: per-band gains
 * ({@code eq}: low, mid, high) and world-space first-order Ambisonic coefficients ({@code sh}:
 * W, Y, Z, X in Steam Audio's convention) that already carry the session's distance curve and the
 * share of the direct path it stands in for. Arrays are not modified after construction.
 */
public record PathingField(float[] eq, float[] sh) {
    public PathingField {
        if (eq.length != 3 || sh.length != 4) throw new IllegalArgumentException("Expected 3 bands and 4 coefficients");
    }
}
