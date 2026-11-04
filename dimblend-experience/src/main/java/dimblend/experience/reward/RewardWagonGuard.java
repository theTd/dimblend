package dimblend.experience.reward;

/**
 * Whether one block inside a reward wagon may be changed, and by whom.
 *
 * <p><b>Why this is a pure class.</b> The rule has four inputs — is the block part of the wagon,
 * which brick is it, where is the change coming from, and is the player in creative mode — and
 * every interesting case is a combination of those. Keeping the decision out of the event
 * handler lets the tests cover the combinations without a level, and leaves the handler a thin
 * translation.
 *
 * <p><b>The rule, from the requirement and the user's 2026-11-03 corrections.</b>
 * <ul>
 *   <li>In <b>survival</b> the three brick kinds — slab, double slab and bogey — may not be
 *       interacted with in any way, couplers may not be uncoupled by hand, nothing may be placed,
 *       and only content may be removed. The player confirmed this against the existing Simurail
 *       guard, which deliberately lets creative players through; this guard is its own policy and
 *       must not reuse it.</li>
 *   <li>In <b>creative</b> the player may break and wrench the structure, may place blocks, and may
 *       interact freely. The user reversed the earlier "anyone, including creative" reading:
 *       "wrenching the camouflage blocks is not possible, but creative mode may" and "nothing may
 *       be added, <i>but creative may</i>".</li>
 *   <li>Content — ore, raw ore, the chest, the sensor, the TNT, the creative motor — may be removed
 *       by anyone, in any mode. That is the whole point of the wagon.</li>
 *   <li>The motor's speed panel stays locked even in creative: the requirement pins it at 256.</li>
 * </ul>
 *
 * <p><b>What creative placement means for the emptiness test.</b> The user chose "a block placed
 * in creative counts as content", so a stray creative block keeps the wagon alive. That is a
 * deliberate choice, recorded in the answers file, not an oversight: this class therefore does
 * <i>not</i> track where a block came from.
 *
 * <p><b>The one asymmetry worth naming.</b> Locking the couplers for players does not stop the
 * dissolve from breaking them: the dissolve calls {@code removePartner} and sets air directly on
 * the server thread and never raises a player event, so the guard is not in its path.
 */
public final class RewardWagonGuard {
    /** Why a change was refused, or that it is allowed. */
    public enum Verdict {
        ALLOW,
        /** The block is wagon structure; a survival player may not touch it. */
        DENY_STRUCTURE,
        /** The block is a coupler; a survival player may never uncouple it by hand. */
        DENY_COUPLER,
        /** The block's value may not be re-rolled; the requirement pins the motor at 256. */
        DENY_MOTOR_SPEED,
        /** A survival player is trying to put a block inside the wagon. */
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
     * @param creative    whether the asking player is in creative mode
     */
    public static Verdict mayRemove(boolean insideWagon, Brick brick, Source source, boolean creative) {
        if (!insideWagon) {
            return Verdict.ALLOW;
        }
        if (brick == Brick.CONTENT || brick == Brick.MOTOR) {
            // Removal of content is the only thing allowed for everyone, and is allowed from any
            // source: an explosion clearing the ore is as valid as a pickaxe. The motor is
            // content — only its speed panel is locked.
            return Verdict.ALLOW;
        }
        if (creative) {
            // The user's correction: creative mode may wrench the camouflage blocks off.
            return Verdict.ALLOW;
        }
        if (brick == Brick.COUPLER) {
            return Verdict.DENY_COUPLER;
        }
        // Slab or bogey in survival: refused.
        return Verdict.DENY_STRUCTURE;
    }

    /** Convenience for the non-player paths, which are never creative. */
    public static Verdict mayRemove(boolean insideWagon, Brick brick, Source source) {
        return mayRemove(insideWagon, brick, source, false);
    }

    /**
     * Decides one placement attempt.
     *
     * <p>Survival may never place inside a wagon; creative may, which is the user's 2026-11-03
     * correction. A block placed this way counts as content, so it keeps the wagon alive until it
     * is broken again — the user chose that explicitly.
     */
    public static Verdict mayPlace(boolean insideWagon, Source source, boolean creative) {
        if (!insideWagon) {
            return Verdict.ALLOW;
        }
        return creative ? Verdict.ALLOW : Verdict.DENY_PLACE;
    }

    /** Convenience for the non-player paths, which are never creative. */
    public static Verdict mayPlace(boolean insideWagon, Source source) {
        return mayPlace(insideWagon, source, false);
    }

    /**
     * Decides one right-click or left-click on a wagon block.
     *
     * <p>Content stays interactable in every mode: a chest must open or the wagon could never be
     * emptied.
     *
     * <p><b>The motor is content but its panel is not.</b> A creative motor's speed is a
     * scroll-behaviour value a wrench re-rolls, and the requirement pins it at 256 and says it
     * cannot be adjusted. So the motor may still be broken like any content, but
     * {@link #mayAdjustMotorSpeed} refuses the wrench.
     */
    public static Verdict mayInteract(boolean insideWagon, Brick brick, boolean creative) {
        if (!insideWagon || brick == Brick.CONTENT || brick == Brick.MOTOR) {
            return Verdict.ALLOW;
        }
        if (creative) {
            return Verdict.ALLOW;
        }
        return brick == Brick.COUPLER ? Verdict.DENY_COUPLER : Verdict.DENY_STRUCTURE;
    }

    /** Convenience for the non-player paths, which are never creative. */
    public static Verdict mayInteract(boolean insideWagon, Brick brick) {
        return mayInteract(insideWagon, brick, false);
    }

    /**
     * Decides whether the value on a block may be re-rolled — the creative motor's rpm.
     *
     * <p>Always refused inside a wagon, in creative too, because the requirement fixes that speed
     * at 256 and marks it "不可调整". This is the one rule the creative exemption does not lift.
     */
    public static Verdict mayAdjustMotorSpeed(boolean insideWagon) {
        return insideWagon ? Verdict.DENY_MOTOR_SPEED : Verdict.ALLOW;
    }

    /** True when the verdict refuses the change. */
    public static boolean denies(Verdict verdict) {
        return verdict != Verdict.ALLOW;
    }
}