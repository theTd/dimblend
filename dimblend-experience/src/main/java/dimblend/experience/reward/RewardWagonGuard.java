package dimblend.experience.reward;

/**
 * Whether one block inside a reward wagon may be changed, and by whom.
 *
 * <p><b>Why this is a pure class.</b> The rule has three inputs — is the block part of the
 * wagon, which brick is it, and where is the change coming from — and every interesting
 * case is a combination of those. Keeping the decision out of the event handler lets the
 * tests cover the combinations without a level, and leaves the handler a thin translation.
 *
 * <p><b>The rule, from the requirement.</b>
 * <ul>
 *   <li>The three brick kinds — slab, double slab and bogey — may not be interacted with
 *       in any way, and "any way" includes creative mode. The player confirmed this
 *       against the existing Simurail guard, which deliberately lets creative players
 *       through; this guard must not reuse it.</li>
 *   <li>Couplers are locked too: the player chose "never uncouple" over "couplers may be
 *       broken".</li>
 *   <li>Nothing may be placed inside the wagon, from any source: a hopper or a machine is
 *       as forbidden as a player's hand.</li>
 *   <li>Content — ore, raw ore, the chest, the sensor, the TNT, the creative motor — may be
 *       removed. That is the whole point of the wagon.</li>
 * </ul>
 *
 * <p><b>The one asymmetry worth naming.</b> Locking the couplers for players does not stop
 * the dissolve from breaking them: the dissolve calls {@code removePartner} and sets air
 * directly on the server thread and never raises a player event, so the guard is not in its
 * path. Without this paragraph a reader would assume the two rules contradict.
 */
public final class RewardWagonGuard {
    /** Why a change was refused, or that it is allowed. */
    public enum Verdict {
        ALLOW,
        /** The block is wagon structure; nobody may touch it. */
        DENY_STRUCTURE,
        /** The block is a coupler; it may never be uncoupled by hand. */
        DENY_COUPLER,
        /** The block's value may not be re-rolled; the requirement pins the motor at 256. */
        DENY_MOTOR_SPEED,
        /** Something is trying to put a block inside the wagon. */
        DENY_PLACE
    }

    /** What kind of brick the target block is. */
    public enum Brick {
        /** {@code type=top} or {@code type=double} clean copycat slab. */
        SLAB,
        /** {@code simurail:physics_bogey}. */
        BOGEY,
        /** {@code simurail:automatic_coupler}. */
        COUPLER,
        /** The motor wagon's creative motor, whose speed the requirement pins at 256. */
        MOTOR,
        /** Anything else: ore, chest, sensor, TNT, motor-wagon clutch and lever, foreign blocks. */
        CONTENT
    }

    /** Where a change comes from. */
    public enum Source {
        PLAYER,
        /** Any non-player path: a machine, an explosion, another mod. */
        OTHER
    }

    private RewardWagonGuard() {
    }

    /**
     * Decides one removal attempt.
     *
     * @param insideWagon whether the block belongs to a reward wagon at all
     * @param brick       which brick it is
     * @param source      where the attempt comes from
     */
    public static Verdict mayRemove(boolean insideWagon, Brick brick, Source source) {
        if (!insideWagon) {
            return Verdict.ALLOW;
        }
        if (brick == Brick.CONTENT || brick == Brick.MOTOR) {
            // Removal of content is the only thing allowed, and is allowed from any source:
            // an explosion clearing the ore is as valid as a pickaxe. The motor is content —
            // only its speed panel is locked.
            return Verdict.ALLOW;
        }
        if (brick == Brick.COUPLER) {
            return Verdict.DENY_COUPLER;
        }
        // Slab or bogey: refused whoever is asking. Creative mode is not a special case.
        return Verdict.DENY_STRUCTURE;
    }

    /**
     * Decides one placement attempt.
     *
     * <p>Every placement inside a wagon is refused, whatever the source. The requirement's
     * "only content may be removed, nothing may be added" is unconditional.
     */
    public static Verdict mayPlace(boolean insideWagon, Source source) {
        return insideWagon ? Verdict.DENY_PLACE : Verdict.ALLOW;
    }

    /**
     * Decides one right-click or left-click on a wagon block.
     *
     * <p>Content stays interactable: a chest must open or the wagon could never be emptied.
     *
     * <p><b>The motor is content but its panel is not.</b> A creative motor's speed is a
     * scroll-behaviour value a wrench re-rolls, and the requirement pins it at 256 and says it
     * cannot be adjusted. So the motor may still be broken like any content, but
     * {@link #mayAdjustMotorSpeed} refuses the wrench.
     */
    public static Verdict mayInteract(boolean insideWagon, Brick brick) {
        if (!insideWagon || brick == Brick.CONTENT || brick == Brick.MOTOR) {
            return Verdict.ALLOW;
        }
        return brick == Brick.COUPLER ? Verdict.DENY_COUPLER : Verdict.DENY_STRUCTURE;
    }

    /**
     * Decides whether the value on a block may be re-rolled — the creative motor's rpm.
     *
     * <p>Always refused inside a wagon, because the requirement fixes that speed at 256 and
     * marks it "不可调整".
     */
    public static Verdict mayAdjustMotorSpeed(boolean insideWagon) {
        return insideWagon ? Verdict.DENY_MOTOR_SPEED : Verdict.ALLOW;
    }

    /** True when the verdict refuses the change. */
    public static boolean denies(Verdict verdict) {
        return verdict != Verdict.ALLOW;
    }
}