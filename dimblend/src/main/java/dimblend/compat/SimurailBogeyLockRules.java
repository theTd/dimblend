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
 *
 * <p>The joint limit alone is not enough: every physics step vanilla also drops the track
 * (and with it the joint) once the axle leaves a narrow window around its rail line — half
 * a block sideways, 0.375 above / 1.5 below (inverted bogeys: 0.4375 below / 1.5 above),
 * tilted under 60 degrees — and its next-segment search and periodic re-search use the
 * same window. A hard limit is still a solver constraint, so a large step or a heavy hit
 * can stretch it past that for a moment. While locked, an axle instead counts as on its
 * rail up to {@link #CAPTIVE_REACH} away, and its zero limit pulls it straight back.
 */
public final class SimurailBogeyLockRules {
    /**
     * Vanilla marks a released side with {@code (double) Float.MAX_VALUE}; any bound this
     * large is treated as "no limit".
     */
    public static final double RELEASED_BOUND = 1.0E30;

    /**
     * Farthest an axle may be from its rail line, sideways or vertically [blocks], and still
     * be held by the lock. Real joint stretch stays well below a block; anything beyond this
     * is a teleported or broken body, which is left to derail rather than be yanked back.
     */
    public static final double CAPTIVE_REACH = 3.0;

    /**
     * How far past either end a curve piece still counts as the current piece, in piece
     * lengths. A curve is a chain of short chords; on the outer side of the bend the end of
     * one chord's span and the start of the next one's leave a wedge-shaped gap, of width
     * {@code d * L / R} at distance {@code d} from the rail (chord length {@code L}, curve radius
     * {@code R}). Half a piece on each side closes it for {@code d <= R}, so for the whole
     * {@link #CAPTIVE_REACH} on any curve of radius 3 or more. Vanilla's own span margin is
     * 1/32 of a piece.
     */
    public static final double CURVE_PIECE_OVERLAP = 0.5;

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

    /**
     * Whether a locked axle still counts as on its rail.
     *
     * @param lateralOffset  signed sideways distance of the axle from the rail line
     * @param verticalOffset signed vertical distance of the axle from the rail line
     */
    public static boolean withinCaptiveReach(double lateralOffset, double verticalOffset) {
        // Written so that NaN offsets (a broken body) fall outside the reach.
        return Math.abs(lateralOffset) <= CAPTIVE_REACH && Math.abs(verticalOffset) <= CAPTIVE_REACH;
    }

    /**
     * Whether a position along a curve piece (0 at its start, 1 at its end) is still on that
     * piece for the lock, see {@link #CURVE_PIECE_OVERLAP}.
     */
    public static boolean withinCurvePieceSpan(double pieceT) {
        return pieceT >= -CURVE_PIECE_OVERLAP && pieceT <= 1.0 + CURVE_PIECE_OVERLAP;
    }
}
