package dimblend.api;

/**
 * The warp gate's speed and passage rules for a Sable sub-level passing through it.
 *
 * <p>User requirements 2026-11-03, corrected 2026-11-04 and updated 2026-11-12.
 *
 * <h2>The rules as they now stand</h2>
 * <ul>
 *   <li>whenever <b>any</b> player (any game mode) is on <b>any</b> Sable sub-level, every
 *       sub-level going forward through the gate is given at least <b>1 m/s</b> toward +X
 *       ("需要有任意一个玩家在任意sable上就生效 无论是谁 在哪个sable"), including uncrewed coupled cars;</li>
 *   <li>a sub-level must not cross back from +X toward -X: <b>force its X velocity to +1 m/s</b>;</li>
 *   <li>when <b>no</b> player is on any Sable sub-level, the gate holds a forward crossing at
 *       <b>zero velocity</b> so unattended trains do not drift through alone.</li>
 * </ul>
 *
 * <p><b>Why the first correction was replaced.</b> The first version refused a backward crossing
 * by putting the body back outside the gate ({@code RigidBodyHandle#teleport}) every tick. In game
 * that produced collision-box trouble and derailed the train — a position correction fights the
 * physics solver instead of talking to it. The user's supplement is explicit: do not impose an
 * absolute constraint, use velocity. So nothing here moves a body; every action is an X velocity
 * delta.
 *
 * <p><b>Units.</b> Sable's {@code RigidBodyHandle#getLinearVelocity} and
 * {@code addLinearAndAngularVelocity} work in metres per second, so the requirement's "1 m/s" is
 * used directly and no tick conversion is needed. This class is pure so the boundaries — exactly
 * the minimum, just under, already faster, wrong side, crew outside, nobody riding — are pinned by
 * tests rather than discovered in game.
 *
 * <p><b>"Speed" means speed toward +X, not total speed.</b> The requirement's target is "a velocity
 * of at least 1 m/s <i>toward the +X direction</i>", and the exemption is phrased against that same
 * quantity. A sub-level drifting sideways still has zero speed toward +X, so it still needs the
 * push; measuring the vector length instead would let a sideways drift suppress the gate's whole
 * purpose.
 */
public final class WarpGateMotion {

    /** The minimum +X speed the requirement asks for, in metres per second. */
    public static final double MIN_PLUS_X_SPEED = 1.0;

    /** Which side of the gate slab a sub-level was last clear of. */
    public enum Side {
        /** Clear on the -X side: this is the direction the gate lets through. */
        WEST,
        /** Clear on the +X side: the gate turns this one back around. */
        EAST
    }

    /** What the gate wants done to one sub-level this tick. */
    public enum Action {
        /** Nothing to change: already fast enough, or a structure the gate has no business with. */
        NONE,
        /** Below the minimum toward +X, while at least one player is on any Sable: raise to minimum. */
        PUSH_PLUS_X,
        /** Coming from the +X side toward -X: force the X velocity to the minimum forward speed. */
        TURN_PLUS_X,
        /** A forward crossing while no player is on any Sable sub-level: hold the velocity at zero. */
        HOLD_STILL
    }

    /**
     * The side a box was clear of, keeping the caller's memory when the box straddles the gate.
     *
     * <p>A box that is entirely on one side says its own side; one that straddles keeps whatever
     * the caller already knew, because "which way is this thing going" is a fact about the past,
     * not about the current overlap.
     */
    public static Side sideOf(double minX, double maxX, int slabX, Side remembered) {
        if (minX >= slabX + 1.0) {
            return Side.EAST;
        }
        if (maxX <= slabX) {
            return Side.WEST;
        }
        return remembered;
    }

    /** The side to assume for a box met for the first time: the side its centre is on. */
    public static Side sideOfCentre(double centreX, int slabX) {
        return centreX >= slabX + 1.0 ? Side.EAST : Side.WEST;
    }

    /**
     * Decides what to do with one sub-level at a gate.
     *
     * <p>The order carries meaning. A structure that is already clear on the +X side is turned
     * around first so a released structure is never trapped inside. Then a forward crossing is held
     * when nobody at all is on any Sable sub-level. Otherwise, whenever at least one player (in any
     * game mode) is on any Sable sub-level, every forward-crossing sub-level (including uncrewed
     * coupled carriages) is raised to at least {@link #MIN_PLUS_X_SPEED} toward +X.
     *
     * @param velocityX                the sub-level's current world X velocity, m/s
     * @param side                     the side the sub-level was last clear of
     * @param anyPlayerOnAnySubLevel   at least one player (any game mode) is riding or tracking any Sable sub-level
     */
    public static Action decide(double velocityX, Side side, boolean anyPlayerOnAnySubLevel) {
        if (side == Side.EAST) {
            return velocityX < 0.0 ? Action.TURN_PLUS_X : Action.NONE;
        }
        if (!anyPlayerOnAnySubLevel) {
            return Action.HOLD_STILL;
        }
        return velocityX >= MIN_PLUS_X_SPEED ? Action.NONE : Action.PUSH_PLUS_X;
    }

    /**
     * The X velocity delta one action asks for, in m/s.
     *
     * <p>{@link Action#TURN_PLUS_X} is a <b>force</b>, not a clamp: the requirement says "速度强制为
     * +1m/s", so a structure backing into the gate at -0.5 m/s gets +1.5 and leaves at exactly
     * +1 m/s. {@link Action#HOLD_STILL} is the opposite extreme and simply cancels whatever the X
     * velocity currently is.
     *
     * @return the delta to add, or 0 when the action changes nothing
     */
    public static double deltaX(Action action, double velocityX) {
        return switch (action) {
            case NONE -> 0.0;
            case PUSH_PLUS_X, TURN_PLUS_X -> MIN_PLUS_X_SPEED - velocityX;
            case HOLD_STILL -> -velocityX;
        };
    }

    /**
     * Whether a sub-level's bounding box at {@code minX..maxX} overlaps a gate slab at {@code slabX}.
     *
     * <p>The gate is one block thick in X and the sub-level is a box, so simple overlap is the test
     * the requirement's "穿过" implies.
     */
    public static boolean overlapsSlab(int slabX, double minX, double maxX) {
        return maxX > slabX && minX < slabX + 1.0;
    }

    private WarpGateMotion() {
    }
}
