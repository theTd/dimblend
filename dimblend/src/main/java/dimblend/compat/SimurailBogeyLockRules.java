package dimblend.compat;

/**
 * Minecraft-free limit math for {@code PhysicsBogeyAxleLockMixin}: the Linear Bearing
 * style captive rail lock for Simurail axles, always on in the rotating dimension.
 *
 * <p>Linear Bearing (1.2.6, the version shipped in the pack) holds its slider purely by
 * geometry: the T-profile {@code linear_moving} slider sits in the lipped
 * {@code linear_casing} channel with zero clearance, so rigid contacts pin it laterally
 * and vertically in both directions while it stays free to slide along the rail. There
 * are no springs and no release conditions — the only way off is the end of the casing.
 *
 * <p>The axle equivalent is a symmetric hard limit on the rail joint's {@code LINEAR_Y}
 * and {@code LINEAR_Z} axes. Vanilla Simurail already squeezes those limits to zero once
 * an axle settles; the lock only removes vanilla's three exits: the lateral overspeed
 * release, the vertical (crest) overspeed release, and the one-sided free lift used when
 * {@code allowVerticalMovement} is on. Vanilla's squeeze-in after (re)railing is kept, so
 * engaging the lock never snaps an unsettled axle.
 */
public final class SimurailBogeyLockRules {
    /**
     * Vanilla marks a released side with {@code (double) Float.MAX_VALUE}; any bound this
     * large is treated as "no limit".
     */
    public static final double RELEASED_BOUND = 1.0E30;

    private SimurailBogeyLockRules() {
    }

    /**
     * Captive half-width for one rail axis while the lock is engaged; the axle is then
     * limited to {@code [-halfWidth, +halfWidth]}.
     *
     * @param axisSettled   vanilla {@code yFixed}/{@code zFixed}: the axle has settled on the rail
     * @param vanillaLower  lower bound vanilla passed to {@code setLimit} ({@code -limit}, or
     *                      {@code -MAX} when released)
     * @param currentOffset signed distance of the axle from the rail along this axis
     */
    public static double captiveHalfWidth(boolean axisSettled, double vanillaLower, double currentOffset) {
        if (axisSettled) {
            return 0.0;
        }
        double distance = Math.abs(currentOffset);
        // Released this step: hold the axle where it is instead of letting it leave;
        // vanilla's squeeze keeps tightening from here on the following steps.
        double squeeze = vanillaLower > -RELEASED_BOUND ? -vanillaLower : distance;
        double halfWidth = Math.min(squeeze, distance);
        return halfWidth > 0.0 ? halfWidth : 0.0;
    }
}
