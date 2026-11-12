package dimblend.experience.compat.simurail;

/**
 * Pure interaction rules for right-clicking Simurail blocks across game modes.
 *
 * <p><b>Bogey rule (DECISIONS.md #1).</b> On {@code simurail:physics_bogey} (normal and inverted):
 * <ul>
 *   <li><b>Only</b> an empty hand or a wrench ({@code create:wrench} or {@code #c:tools/wrench})
 *       opens {@code PhysicsBogeyMenu}, in <b>both</b> Survival and Creative, and <b>both</b>
 *       standing and sneaking.</li>
 *   <li>A wrench never rotates ({@code onWrenched}) and never dismantles ({@code onSneakWrenched})
 *       a physics bogey, even when sneaking in Creative mode.</li>
 *   <li>Every other held item (building blocks, slabs, copycats, axial shafts, steering connectors,
 *       wire spools, sticks, etc. — standing or sneaking, Survival or Creative) bypasses
 *       {@code PhysicsBogeyMenu} and executes normal {@code stack.useOn} item/block placement.</li>
 *   <li>When sneaking with an empty main hand and a non-wrench item in offhand, leaving the
 *       main-hand event at {@link Decision#ALLOW} preserves vanilla's sneak-bypass so the offhand
 *       item places normally.</li>
 * </ul>
 *
 * <p><b>Pure Java.</b> Zero Minecraft imports so {@code SimurailSurvivalInteractionTest} runs in
 * plain JUnit without {@code Bootstrap}.
 */
public final class SimurailSurvivalInteraction {

    /** What {@code SimurailBlockGuard} should do on {@code PlayerInteractEvent.RightClickBlock}. */
    public enum Decision {
        /** Run normal block/item interaction ({@code DEFAULT} useBlock and useItem). */
        ALLOW,
        /** Cancel the interaction outright (and resync server inventory). */
        DENY,
        /**
         * Force block {@code useItemOn} ({@code TriState.TRUE}) and suppress item hooks
         * ({@code TriState.FALSE}) so a wrench opens {@code PhysicsBogeyMenu} both standing and
         * sneaking without rotating or dismantling the bogey.
         */
        FORCE_BOGEY_MENU
    }

    /**
     * Whether {@code PhysicsBogeyBlock#useItemOn} should open {@code PhysicsBogeyMenu} for the
     * hand currently being dispatched by {@code Minecraft#startUseItem} /
     * {@code ServerPlayerGameMode#useItemOn}, or pass through to default block/item placement.
     *
     * <p>Crucially, "empty-handed" requires <b>both</b> hands to be empty and only fires on the
     * {@code MAIN_HAND} pass:
     * <ul>
     *   <li>Holding a non-wrench item (e.g. stick, pickaxe, bricks) in {@code MAIN_HAND} with an
     *       empty {@code OFF_HAND} never opens the menu on vanilla's {@code OFF_HAND} fallthrough.</li>
     *   <li>Standing with an empty {@code MAIN_HAND} and a placeable block/item in {@code OFF_HAND}
     *       passes {@code MAIN_HAND} so {@code OFF_HAND} places cleanly.</li>
     *   <li>Holding a wrench ({@code create:wrench} or {@code #c:tools/wrench}) in the dispatched
     *       hand always opens {@code PhysicsBogeyMenu}.</li>
     * </ul>
     */
    public static boolean bogeyOpensMenu(
            boolean mainHandInteraction,
            boolean mainHandEmpty,
            boolean offHandEmpty,
            boolean holdingBogeyWrench) {
        if (holdingBogeyWrench) {
            return true;
        }
        return mainHandInteraction && mainHandEmpty && offHandEmpty;
    }

    /** Single-hand convenience overload when {@code OFF_HAND} is empty on {@code MAIN_HAND}. */
    public static boolean bogeyOpensMenu(boolean emptyHand, boolean holdingBogeyWrench) {
        return bogeyOpensMenu(true, emptyHand, true, holdingBogeyWrench);
    }

    /**
     * Full right-click decision across game modes and protection settings.
     *
     * @param protectEnabled      {@code Config.SIMURAIL_PROTECT}
     * @param creative            player is in Creative mode
     * @param survival            player is in Survival mode (not Adventure / Spectator)
     * @param bogey               target block is {@code simurail:physics_bogey}
     * @param coupler             target block is {@code simurail:automatic_coupler}
     * @param holdingBogeyWrench  held stack is {@code create:wrench} or {@code #c:tools/wrench}
     * @param holdingCreateWrench held stack is {@code create:wrench}
     * @param survivalCouplerOpen {@code Config.ALLOW_SURVIVAL_COUPLER_INTERACTION}
     */
    public static Decision decideRightClick(
            boolean protectEnabled,
            boolean creative,
            boolean survival,
            boolean bogey,
            boolean coupler,
            boolean holdingBogeyWrench,
            boolean holdingCreateWrench,
            boolean survivalCouplerOpen) {
        if (bogey) {
            if (protectEnabled && !creative && !survival) {
                return Decision.DENY;
            }
            if ((creative || survival) && holdingBogeyWrench) {
                return Decision.FORCE_BOGEY_MENU;
            }
            return Decision.ALLOW;
        }
        if (!protectEnabled || creative) {
            return Decision.ALLOW;
        }
        if (!survival) {
            return Decision.DENY;
        }
        if (coupler && (survivalCouplerOpen || holdingCreateWrench)) {
            return Decision.ALLOW;
        }
        return Decision.DENY;
    }

    /**
     * Survival right-click decision when {@code SIMURAIL_PROTECT} is enabled.
     */
    public static Decision decide(boolean bogey, boolean coupler, boolean holdingBogeyWrench,
            boolean holdingCreateWrench, boolean survivalCouplerOpen) {
        return decideRightClick(true, false, true, bogey, coupler,
                holdingBogeyWrench, holdingCreateWrench, survivalCouplerOpen);
    }

    private SimurailSurvivalInteraction() {
    }
}
