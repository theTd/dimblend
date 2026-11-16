package dimblend.experience.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import dimblend.experience.compat.starlight.CrystalbornCatalystRules;
import dimblend.experience.compat.starlight.CrystalbornCatalystTooltips;
import dimblend.experience.compat.tavern.TavernBrewingRules;
import dimblend.experience.compat.tavern.TavernBrewingRules.EconomyMode;
import dimblend.experience.compat.yat.VoidicWellPurityRules;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

/** Pure rule tests for batch 1116 unit 2 (tavern, crystalborn catalyst, accessories, YAT). */
class Batch1116Unit2RulesTest {

    @Test
    void tavernLockedModeKeepsSixteenInputAndCapsOutputAtFour() {
        assertEquals(EconomyMode.KEEP_16_YIELD_QUARTER, TavernBrewingRules.ACTIVE_MODE);
        assertEquals(4, TavernBrewingRules.TARGET_OUTPUT_COUNT);
        assertEquals(16, TavernBrewingRules.slotLimit());
        assertEquals(16, TavernBrewingRules.viewerInputCount());
        assertEquals(4, TavernBrewingRules.outputCount());
    }

    @Test
    void tavernCapModeKeepsOneToOneRatio() {
        EconomyMode mode = EconomyMode.CAP_4_KEEP_1TO1;
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[0], new int[0], mode),
                "empty ingredients keep the vanilla default");
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 1 }, new int[] { 64 }, mode));
        assertEquals(2, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 2 }, new int[] { 64 }, mode));
        assertEquals(3, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 3 }, new int[] { 64 }, mode));
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 4 }, new int[] { 64 }, mode));
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 64 }, new int[] { 64 }, mode));
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 1, 9 }, new int[] { 64, 64 }, mode),
                "the scarcest ingredient decides");
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 0, 9 }, new int[] { 64, 64 }, mode),
                "empty slots are ignored");
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 1 }, new int[] { 1 }, mode),
                "unstackable ingredients still produce one");
    }

    @Test
    void tavernQuarterModeKeepsSixteenSlotsAndQuartersYield() {
        EconomyMode mode = EconomyMode.KEEP_16_YIELD_QUARTER;
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 16 }, new int[] { 64 }, mode));
        assertEquals(3, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 12 }, new int[] { 16 }, mode));
        assertEquals(2, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 8 }, new int[] { 64 }, mode));
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 4 }, new int[] { 64 }, mode));
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 3 }, new int[] { 64 }, mode),
                "a sub-quarter stack still yields one");
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 1 }, new int[] { 1 }, mode),
                "unstackable ingredients are not divided");
        assertEquals(4, TavernBrewingRules.computeAssembledCountFromCounts(new int[0], new int[0], mode));
        assertEquals(1, TavernBrewingRules.computeAssembledCountFromCounts(new int[] { 4, 16 }, new int[] { 64, 64 }, mode),
                "the scarcest quarter decides");
    }

    @Test
    void tavernEconomyModeSwitchesSlotLimitAndOutputStaysCapped() {
        EconomyMode previous = TavernBrewingRules.ACTIVE_MODE;
        try {
            TavernBrewingRules.ACTIVE_MODE = EconomyMode.CAP_4_KEEP_1TO1;
            assertEquals(4, TavernBrewingRules.slotLimit());
            assertEquals(4, TavernBrewingRules.viewerInputCount());
            assertEquals(4, TavernBrewingRules.outputCount());

            TavernBrewingRules.ACTIVE_MODE = EconomyMode.KEEP_16_YIELD_QUARTER;
            assertEquals(16, TavernBrewingRules.slotLimit());
            assertEquals(16, TavernBrewingRules.viewerInputCount());
            assertEquals(4, TavernBrewingRules.outputCount());
        } finally {
            TavernBrewingRules.ACTIVE_MODE = previous;
        }
    }

    @Test
    void crystalbornLockedToReplaceWithCrystalSandAndHasTooltipKey() {
        assertEquals(CrystalbornCatalystRules.ReplaceMode.REPLACE_WITH_CRYSTAL_SAND,
                CrystalbornCatalystRules.ACTIVE_REPLACE_MODE);
        assertEquals("tooltip.dimblend_experience.crystalborn_catalyst.mode",
                CrystalbornCatalystTooltips.TOOLTIP_KEY);
    }

    @Test
    void crystalbornNeverMinesTemplateAnchorOrCatalyst() {
        assertFalse(CrystalbornCatalystRules.isMineCandidate(true, false, false, false, 3.0F, true),
                "the block directly below is the template anchor");
        assertFalse(CrystalbornCatalystRules.isMineCandidate(false, true, false, false, 3.0F, true),
                "the catalyst itself is never mined");
        assertFalse(CrystalbornCatalystRules.isMineCandidate(false, false, true, false, 0.0F, true),
                "air is never a target");
        assertFalse(CrystalbornCatalystRules.isMineCandidate(false, false, false, true, 3.0F, true),
                "fluid blocks are never a target");
        assertFalse(CrystalbornCatalystRules.isMineCandidate(false, false, false, false, -1.0F, true),
                "unbreakable blocks (bedrock) are never a target");
        assertFalse(CrystalbornCatalystRules.isMineCandidate(false, false, false, false, 3.0F, false),
                "only the template block type matches");
        assertTrue(CrystalbornCatalystRules.isMineCandidate(false, false, false, false, 3.0F, true));
    }

    @Test
    void accessoriesButtonOnlyLockedInSurvival() {
        assertTrue(AccessoriesSurvivalRules.isSurvivalMode(GameType.SURVIVAL));
        assertFalse(AccessoriesSurvivalRules.isSurvivalMode(GameType.CREATIVE));
        assertFalse(AccessoriesSurvivalRules.isSurvivalMode(GameType.ADVENTURE));
        assertFalse(AccessoriesSurvivalRules.isSurvivalMode(GameType.SPECTATOR));
        assertFalse(AccessoriesSurvivalRules.isSurvivalMode(null));
    }

    @Test
    void voidicWellUsesDirtyPurity() {
        assertEquals(0, VoidicWellPurityRules.DIRTY_PURITY);
        assertEquals(1, VoidicWellPurityRules.DIRTY_CAULDRON_BLOCK_PURITY);
        assertEquals(3, VoidicWellPurityRules.FULL_CAULDRON_LEVEL);
    }
}
