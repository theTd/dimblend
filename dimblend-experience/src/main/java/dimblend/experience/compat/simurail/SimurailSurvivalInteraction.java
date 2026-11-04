package dimblend.experience.compat.simurail;

/**
 * Which survival right-click one Simurail block may receive.
 *
 * <p><b>Why this is a pure rule.</b> The guard used to answer with a single switch
 * ({@code allowSurvivalCouplerInteraction}), so opening "wrench charging in survival" would have
 * opened every coupler interaction — including the type change the mod deliberately locks. The
 * user asked for one action only: "扳手充能 需要生存模式也可使用".
 *
 * <p><b>The three answers.</b>
 * <ul>
 *   <li>{@link Decision#ALLOW} — the vanilla path runs; for a coupler plus a Create wrench that
 *       path reaches {@code AutomaticCouplerWrenchMixin}, which toggles the charge and returns
 *       before any type change.</li>
 *   <li>{@link Decision#DENY} — cancel the interaction outright.</li>
 *   <li>{@link Decision#PASS_BLOCK_ONLY} — let the block open its own menu but refuse item hooks
 *       (the bogey case, where a sneak-wrench must not pick the block up).</li>
 * </ul>
 *
 * <p>The braziers/ploughs and any other Simurail block keep the old {@link Decision#DENY}: this
 * change is about the coupler's wrench, not about opening the mod's blocks.
 */
public final class SimurailSurvivalInteraction {

    /** What the guard should do with one survival right-click. */
    public enum Decision {
        /** Run the vanilla interaction. */
        ALLOW,
        /** Cancel it. */
        DENY,
        /** Let the block act, but suppress the held item's hook. */
        PASS_BLOCK_ONLY
    }

    /**
     * @param bogey                the block is a physics bogey
     * @param coupler              the block is an automatic coupler
     * @param holdingCreateWrench  the held stack is Create's wrench
     * @param survivalCouplerOpen  {@code allowSurvivalCouplerInteraction}: the broad switch that
     *                             opens every coupler interaction
     */
    public static Decision decide(boolean bogey, boolean coupler, boolean holdingCreateWrench,
            boolean survivalCouplerOpen) {
        if (bogey) {
            // Unchanged: the bogey's own menu opens, item hooks stay off.
            return Decision.PASS_BLOCK_ONLY;
        }
        if (coupler && (survivalCouplerOpen || holdingCreateWrench)) {
            // The wrench charge is the one action the user wanted in survival. Reaching the
            // vanilla path is what lets AutomaticCouplerWrenchMixin take over and toggle the
            // charge instead of cycling the type.
            return Decision.ALLOW;
        }
        return Decision.DENY;
    }

    private SimurailSurvivalInteraction() {
    }
}