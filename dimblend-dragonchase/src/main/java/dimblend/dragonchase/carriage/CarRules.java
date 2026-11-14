package dimblend.dragonchase.carriage;

import dimblend.dragonchase.structure.CarBlocks;

/**
 * The car rules that are decisions rather than world access: thresholds, phase durations, the launch
 * vector, and whether a block may be chewed. Pure, so the unit tests can pin every number and every
 * exclusion without a level.
 *
 * <p><b>Everything here is in metres per second or ticks.</b> Sable's physics is m/s
 * (dimblend-dev §8.29); a distance in blocks per tick would make the 2 m/s threshold ten times too
 * small.
 */
public final class CarRules {

    /**
     * The speed above which an uncoupled car counts as "being pushed" (requirements.md §3.5: 速度 > 2
     * m/s 且未挂到常加载车体上).
     */
    public static final double PUSH_SPEED_MPS = 2.0;

    /**
     * Ticks between two chewed blocks.
     *
     * <p>The requirement says "逐个破坏" (one at a time) without a rate; four ticks is five blocks a
     * second, slow enough to read as chewing and fast enough that a car pushed at 10 m/s reaches the
     * train before the pusher has been eaten through.
     */
    public static final int CHEW_INTERVAL_TICKS = 4;

    /** Black smoke over a brake car whose redstone block is gone: 8 seconds. */
    public static final int SMOKE_TICKS = 160;

    /** Fall-away: 0.2 s between losing the couplers and the launch. */
    public static final int FALL_ARM_TICKS = 4;

    /** Fall-away: about 1 s of flight before the explosion. */
    public static final int FALL_FLIGHT_TICKS = 20;

    /** Fall-away: the explosion effect stays, then the sub-level is removed. */
    public static final int EXPLOSION_HOLD_TICKS = 5;

    /** Dissolve: the reward wagon's half second between the burst and the removal. */
    public static final int DISSOLVE_TICKS = 10;

    /**
     * The fall-away launch speed, split into 45° components.
     *
     * <p>Eight metres per second total is the same order as a train at speed and lifts the car clear
     * of the deck within the second before it explodes; the requirement asks for 45° upward toward a
     * random ±Z side with the X velocity kept.
     */
    public static final double LAUNCH_SPEED_MPS = 8.0;

    /** No removal in progress. */
    public static final int NO_PHASE = 0;
    /** Dissolve started; the sub-level goes away at the deadline. */
    public static final int DISSOLVING = 1;
    /** Couplers gone, white smoke played; the launch happens at the deadline. */
    public static final int FALL_ARMED = 1;
    /** Flying; the explosion happens at the deadline. */
    public static final int FALL_FLYING = 2;
    /** Exploded; the sub-level goes away at the deadline. */
    public static final int FALL_EXPLODED = 3;

    private CarRules() {
    }

    /** True when the car's speed makes it a pushed car rather than a rolling one. */
    public static boolean isPushed(double speed) {
        return speed > PUSH_SPEED_MPS;
    }

    /**
     * The velocity of a fall-away launch: X kept, Y and Z replaced by the two 45° components.
     *
     * @param side +1 or -1: which side of the corridor the car is thrown toward
     * @return {@code {vx, vy, vz}}
     */
    public static double[] launchVelocity(double vx, int side) {
        double component = LAUNCH_SPEED_MPS * Math.sin(Math.PI / 4.0);
        return new double[] {vx, component, side >= 0 ? component : -component};
    }

    /**
     * True when a block may be chewed off the sub-level that pushes a car.
     *
     * <p>Two tests, both from the requirement:
     * <ul>
     *   <li><b>a survival player could break it</b>: {@code destroySpeed >= 0}. Hardness -1 is
     *       unbreakable, which is exactly how the creative copycats are built
     *       ({@code CreativeCopycats#creativeProps}: {@code strength(-1.0F, ...)}), so the beams,
     *       slabs and the nest itself are never chewed.</li>
     *   <li><b>not a bogey or a coupler</b>: those are the parts that keep a car on its track
     *       (requirements.md §3.5). Simurail's bogey and coupler are ordinary gold-block-hardness
     *       blocks, so the hardness test alone would eat them.</li>
     * </ul>
     */
    public static boolean chewable(float destroySpeed, String blockId) {
        if (!(destroySpeed >= 0.0F)) {
            return false;
        }
        return !CarBlocks.BOGEY.equals(blockId) && !CarBlocks.COUPLER.equals(blockId);
    }
}
