package dimblend.experience.curse;

/**
 * The one rule behind letting cursed armour come off.
 *
 * <p>Vanilla's {@code ArmorSlot#mayPickup} refuses to release a piece carrying
 * {@code PREVENT_ARMOR_CHANGE} (the curse of binding) unless the player is creative. The user
 * asked on 2026-11-03 that Survival be able to take it off.
 *
 * <p><b>Only the curse is overridden.</b> The answer is "allow" for exactly one combination —
 * a non-empty slot whose piece carries the curse — so the caller must leave every other verdict
 * to vanilla rather than turning every slot into an open one. An empty slot, or a piece without
 * the curse, keeps whatever {@code super.mayPickup} says.
 *
 * <p>Kept as a pure function so the unit test can pin the combinations without a level, and so
 * the mixin stays a thin translation.
 */
public final class BindingCurseRules {

    /**
     * Whether this is the curse case the mixin should overrule.
     *
     * @param slotEmpty       whether the slot holds anything
     * @param hasBindingCurse whether the held piece carries the curse of binding
     * @return true when the curse is the only thing standing in the way
     */
    public static boolean overrulesVanillaRefusal(boolean slotEmpty, boolean hasBindingCurse) {
        return !slotEmpty && hasBindingCurse;
    }

    /**
     * The resulting verdict for the curse case, stated once so the mixin cannot drift from it.
     *
     * @param creative the player's game mode; it no longer changes the answer, and is accepted so
     *                 a reader can see that Survival and Creative now agree
     */
    public static boolean mayRemoveCursedArmour(boolean creative, boolean slotEmpty, boolean hasBindingCurse) {
        return overrulesVanillaRefusal(slotEmpty, hasBindingCurse);
    }

    private BindingCurseRules() {
    }
}