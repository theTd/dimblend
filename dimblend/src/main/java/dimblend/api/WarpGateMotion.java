package dimblend.api;

/**
 * The warp gate's speed and passage rules for a Sable sub-level passing through it.
 *
 * <p>User requirements, 2026-11-03 and the 2026-11-04 corrections:
 * <ul>
 *   <li>a sub-level crossing the gate is given at least <b>1 m/s</b> toward +X; if it already moves
 *       faster no extra speed is added;</li>
 *   <li>a sub-level must not cross the gate from +X toward -X. The 2026-11-04 correction is
 *       explicit: "目前阻止力度不足 推力足够大依然可以反向通过 需要完全阻止反向运动" — so the
 *       refusal is a <b>position</b> constraint, not only a velocity one;</li>
 *   <li>no sub-level may cross at all unless every player is standing on a Sable sub-level.</li>
 * </ul>
 *
 * <p><b>Units.</b> Sable's {@code RigidBodyHandle#getLinearVelocity} and
 * {@code addLinearAndAngularVelocity} work in metres per second, so the requirement's "1 m/s" is
 * used directly and no tick conversion is needed. This class is pure so the boundaries — exactly
 * the minimum, just under, already faster, wrong side, crew outside — are pinned by tests rather
 * than discovered in game.
 *
 * <p><b>Why a velocity clamp alone could never satisfy the requirement.</b> Clearing the -X
 * velocity once per tick leaves the physics step free to push the body a little further west every
 * tick, and the old code additionally stopped firing as soon as the box centre crossed the slab
 * plane. Enough thrust therefore walked a structure straight through. The refusal is expressed here
 * as how far a box has overstepped a <i>face</i> of the gate, so the caller can put the body back
 * (see {@link #reverseCorrection} and {@link #crewCorrection}).
 */
public final class WarpGateMotion {

    /** The minimum +X speed the requirement asks for, in metres per second. */
    public static final double MIN_PLUS_X_SPEED = 1.0;

    /** Which side of the gate slab a sub-level was last clear of. */
    public enum Side {
        /** Clear on the -X side: this is the direction the gate lets through. */
        WEST,
        /** Clear on the +X side: the gate refuses to let it back through. */
        EAST
    }

    /** What the gate wants done to one sub-level this tick. */
    public enum Action {
        /** Already fast enough toward +X: add nothing. */
        NONE,
        /** Below the minimum toward +X: push up to the minimum. */
        PUSH_PLUS_X,
        /** Coming from the +X side toward -X: refuse, and hold it at the gate's east face. */
        BLOCK_REVERSE,
        /** A forward crossing while not every player is aboard: refuse, hold it at the west face. */
        BLOCK_NO_CREW
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
     * <p><b>"Speed" means speed toward +X, not total speed.</b> The requirement's target is "a
     * velocity of at least 1 m/s <i>toward the +X direction</i>", and the exemption is phrased
     * against that same quantity. A sub-level drifting at 5 m/s sideways still has zero speed
     * toward +X, so it still needs the push; measuring the vector length instead would let a
     * sideways drift suppress the gate's whole purpose. The X component is therefore the measure
     * everywhere in this class, which also keeps {@link #plusXDelta} in agreement with
     * {@link #decide} at the boundary.
     *
     * @param velocityX         the sub-level's current world X velocity, m/s
     * @param side              the side the sub-level was last clear of
     * @param everyPlayerAboard whether every player is standing on a Sable sub-level
     */
    public static Action decide(double velocityX, Side side, boolean everyPlayerAboard) {
        if (side == Side.EAST) {
            // Approaching from the far side and moving backward, or already leaving toward +X:
            // the gate never lets a structure it has already released come back through.
            return velocityX < 0.0 ? Action.BLOCK_REVERSE : Action.NONE;
        }
        if (!everyPlayerAboard) {
            return Action.BLOCK_NO_CREW;
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
     * requirement asks for a speed <i>toward x+</i>, not for a total speed of 1 in some direction.
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
     * How far a box has overstepped the gate's <b>east</b> face, i.e. how far east the body has to
     * go before the reverse crossing is undone.
     *
     * <p>{@code >= 0}, and zero when the box's west face is still at or east of that face.
     */
    public static double reverseCorrection(double boxMinX, int slabX) {
        return Math.max(0.0, (slabX + 1.0) - boxMinX);
    }

    /**
     * How far a box has overstepped the gate's <b>west</b> face, i.e. how far west the body has to
     * go before a refused forward crossing is undone.
     *
     * <p>{@code >= 0}, and zero when the box's east face is still at or west of that face.
     */
    public static double crewCorrection(double boxMaxX, int slabX) {
        return Math.max(0.0, boxMaxX - slabX);
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
