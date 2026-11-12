package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.simurail.SimurailSurvivalInteraction.Decision;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit tests for Simurail right-click and bogey menu rules ({@code DECISIONS.md} #1).
 */
class SimurailSurvivalInteractionTest {

    @Test
    void bogeyMenuOpensOnlyForEmptyHandsOrWrenchAcrossMainAndOffhandDispatch() {
        // Both hands empty -> opens on MAIN_HAND pass, ignores redundant OFF_HAND pass
        assertTrue(SimurailSurvivalInteraction.bogeyOpensMenu(true, true, true, false),
                "both hands empty opens PhysicsBogeyMenu on MAIN_HAND");
        assertFalse(SimurailSurvivalInteraction.bogeyOpensMenu(false, true, true, false),
                "redundant OFF_HAND pass does not re-open when MAIN_HAND handles empty hands");

        // Wrench in either dispatched hand -> opens PhysicsBogeyMenu
        assertTrue(SimurailSurvivalInteraction.bogeyOpensMenu(true, false, true, true),
                "MAIN_HAND wrench opens PhysicsBogeyMenu");
        assertTrue(SimurailSurvivalInteraction.bogeyOpensMenu(true, false, false, true),
                "MAIN_HAND wrench with offhand block still opens PhysicsBogeyMenu");
        assertTrue(SimurailSurvivalInteraction.bogeyOpensMenu(false, true, false, true),
                "OFF_HAND wrench with empty MAIN_HAND opens PhysicsBogeyMenu");

        // Non-wrench item in MAIN_HAND + empty OFF_HAND -> NEVER opens on either pass!
        assertFalse(SimurailSurvivalInteraction.bogeyOpensMenu(true, false, true, false),
                "MAIN_HAND stick/bricks bypasses PhysicsBogeyMenu");
        assertFalse(SimurailSurvivalInteraction.bogeyOpensMenu(false, false, true, false),
                "OFF_HAND fallthrough with non-empty MAIN_HAND must NOT open PhysicsBogeyMenu");

        // Empty MAIN_HAND + block/item in OFF_HAND -> passes MAIN_HAND so OFF_HAND places standing or sneaking!
        assertFalse(SimurailSurvivalInteraction.bogeyOpensMenu(true, true, false, false),
                "empty MAIN_HAND with non-empty OFF_HAND passes MAIN_HAND so OFF_HAND can place");
        assertFalse(SimurailSurvivalInteraction.bogeyOpensMenu(false, true, false, false),
                "non-wrench OFF_HAND item bypasses PhysicsBogeyMenu so stack.useOn runs");
    }

    @Test
    void bogeyForcesMenuForAnyWrenchInBothSurvivalAndCreative() {
        // Create wrench in Survival & Creative
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(true, false, true, true, false, true, true, false));
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(true, true, false, true, false, true, true, false));
        // Third-party #c:tools/wrench in Survival & Creative
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(true, false, true, true, false, true, false, false));
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(true, true, false, true, false, true, false, false));
        // Even if SIMURAIL_PROTECT is toggled off, wrenches still open menu instead of rotating/dismantling
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(false, false, true, true, false, true, true, false));
        assertEquals(Decision.FORCE_BOGEY_MENU,
                SimurailSurvivalInteraction.decideRightClick(false, true, false, true, false, true, false, false));
    }

    @Test
    void bogeyAllowsNormalFlowForEmptyHandAndNonWrenchItemsInSurvivalAndCreative() {
        // Empty hand / bricks / shafts / spools / sticks -> ALLOW (DEFAULT useBlock/useItem),
        // preserving offhand sneak-placement and normal stack.useOn!
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decide(true, false, false, false, false));
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(true, true, false, true, false, false, false, false));
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(false, false, true, true, false, false, false, false));
    }

    @Test
    void bogeyDeniesAdventureAndSpectatorWhenProtected() {
        assertEquals(Decision.DENY,
                SimurailSurvivalInteraction.decideRightClick(true, false, false, true, false, false, false, false));
        assertEquals(Decision.DENY,
                SimurailSurvivalInteraction.decideRightClick(true, false, false, true, false, true, true, true));
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(false, false, false, true, false, false, false, false));
    }

    @Test
    void aCreateWrenchOnACouplerIsAllowedInSurvival() {
        assertEquals(Decision.ALLOW, SimurailSurvivalInteraction.decide(false, true, true, true, false),
                "Create wrench on automatic coupler toggles charge in survival");
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, true, true, false, false),
                "non-Create wrench does not open coupler interaction when allowSurvivalCouplerInteraction is false");
    }

    @Test
    void aBareHandOnACouplerStaysDeniedUnlessBroadSwitchOpenOrCreative() {
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, true, false, false, false),
                "bare hand cycles coupler length and stays denied in survival by default");
        assertEquals(Decision.ALLOW, SimurailSurvivalInteraction.decide(false, true, false, false, true),
                "allowSurvivalCouplerInteraction opens coupler interactions in survival");
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(true, true, false, false, true, false, false, false),
                "creative coupler interaction remains allowed");
        assertEquals(Decision.DENY,
                SimurailSurvivalInteraction.decideRightClick(true, false, false, false, true, true, true, true),
                "adventure/spectator coupler interaction remains denied when protected");
    }

    @Test
    void everyOtherSimurailBlockStaysDeniedInSurvivalWhenProtected() {
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, false, true, true, false));
        assertEquals(Decision.DENY, SimurailSurvivalInteraction.decide(false, false, false, false, true));
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(true, true, false, false, false, false, false, false));
        assertEquals(Decision.ALLOW,
                SimurailSurvivalInteraction.decideRightClick(false, false, true, false, false, false, false, false));
    }
}
