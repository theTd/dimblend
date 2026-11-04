package dimblend.api;

/**
 * The warp gate's speed rules for a Sable sub-level passing through it.
 *
 * <p>User requirement, 2026-11-03:
 * <ul>
 *   <li>a sub-level crossing the gate is given at least 2 m/s toward +X; if it already moves faster
 *       than 2 m/s no extra speed is added;</li>
 *   <li>a sub-level must not cross the gate from +X toward -X: that direction is refused.</li>
 * </ul>
 *
 * <p><b>Units.</b> Sable's {@code RigidBodyHandle#getLinearVelocity} and {@code applyLinearImpulse}
 * work in metres per second, so the requirement's "2 m/s" is used directly and no tick conversion is
 * needed. This class is pure so the boundaries — exactly 2, just under, already faster, wrong
 * direction — are pinned by tests rather than discovered in game.
 */
public final class WarpGateMotion {

    /** The minimum +X speed the requirement asks for, in metres per second. */
    public static final double MIN_PLUS_X_SPEED = 2.0;

    /** What the gate wants done to one sub-level this tick. */
    public enum Action {
        /** Already fast enough toward +X: add nothing. */
        NONE,
        /** Below the minimum toward +X: push up to the minimum. */
        PUSH_PLUS_X,
        /** Moving toward -X through the gate: refuse the crossing. */
        BLOCK_NEGATIVE_X
    }

    /**
     * Decides what to do with one sub-level at a gate.
     *
     * <p><b>"Speed" means speed toward +X, not total speed.</b> The requirement's target is "a
     * velocity of at least 2 m/s <i>toward the +X direction</i>", and the exemption is phrased
     * against that same quantity. A sub-level drifting at 5 m/s sideways still has zero speed
     * toward +X, so it still needs the push; measuring the vector length instead would let a
     * sideways drift suppress the gate's whole purpose. The X component is therefore the measure
     * everywhere in this class, which also keeps {@link #plusXDelta} in agreement with
     * {@link #decide} at the boundary.
     *
     * @param velocityX       the sub-level's current world X velocity, m/s
     * @param velocityY       its world Y velocity, m/s
     * @param velocityZ       its world Z velocity, m/s
     * @param crossingFromPosX true when the sub-level is on the +X side of the gate and heading
     *                          into it, i.e. the move that would take it from +X toward -X
     */
    public static Action decide(double velocityX, double velocityY, double velocityZ, boolean crossingFromPosX) {
        if (crossingFromPosX && velocityX < 0.0) {
            // The user wrote "x+ 移动向 x-1": approaching from the far side and moving backward.
            return Action.BLOCK_NEGATIVE_X;
        }
        if (velocityX >= MIN_PLUS_X_SPEED) {
            // Already at or above the minimum toward +X: nothing to add.
            return Action.NONE;
        }
        return Action.PUSH_PLUS_X;
    }

    /**
     * The velocity change that brings a sub-level up to the minimum +X speed.
     *
     * <p>Only the X component is added, so the sub-level keeps its lateral and vertical motion: the
     * requirement asks for a speed <i>toward x+</i>, not for a total speed of 2 in some direction.
     * The result is the impulse to apply, in the same m/s unit as the read velocity — an impulse of
     * exactly the missing delta assumes unit mass through Sable's own mass handling, which is why
     * the caller applies it through {@code applyLinearImpulse} on the real body.
     *
     * @return the X delta to add, or 0 when no push is needed
     */
    public static double plusXDelta(double velocityX) {
        if (velocityX >= MIN_PLUS_X_SPEED) {
            return 0.0;
        }
        return MIN_PLUS_X_SPEED - velocityX;
    }

    /**
     * Whether a sub-level's bounding box at {@code minX..maxX} overlaps a gate slab at {@code slabX}.
     *
     * <p>The gate is one block thick in X and the sub-level is a box, so simple overlap is the test
     * the requirement's "穿过" implies. Kept here so the wagon's contact check and the gate's own
     * trigger share one definition.
     */
    public static boolean overlapsSlab(int slabX, double minX, double maxX) {
        return maxX > slabX && minX < slabX + 1.0;
    }

    private WarpGateMotion() {
    }
}