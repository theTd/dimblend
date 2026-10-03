package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;

/**
 * The diffracted path from a radio to the listener, ready for the path effect: per-band gains
 * ({@code eq}: low, mid, high) and world-space first-order Ambisonic coefficients ({@code sh}:
 * W, Y, Z, X in Steam Audio's convention) that already carry the session's distance curve and the
 * share of the direct path it stands in for. Arrays are not modified after construction.
 *
 * @param length the path's length in blocks, round corners and through openings
 */
public record PathingField(float[] eq, float[] sh, float length) {
    public PathingField {
        if (eq.length != 3 || sh.length != 4) throw new IllegalArgumentException("Expected 3 bands and 4 coefficients");
    }

    /**
     * The direction the path reaches the listener from (a unit vector from the listener), or null
     * when the coefficients carry none. Steam Audio's first-order terms follow its Ambisonic frame:
     * Y grows with arrival from -x, Z from +y and X from -z.
     */
    public Vec3 arrival() {
        Vec3 direction = new Vec3(-sh[1], sh[2], -sh[3]);
        double length = direction.length();
        return length > 1e-9 ? direction.scale(1 / length) : null;
    }
}
