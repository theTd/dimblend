package dimblend.experience.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.simurail.SimurailSurvivalBogeyRules;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 生存模式转向架菜单收缩口径（2026-11-25 LOCKED #3 {@code bogey_survival_menu}）。
 */
class SimurailSurvivalBogeyRulesTest {

    @Test
    void onlyTheFourUserApprovedStylesSurvive() {
        assertEquals(4, SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS.size());
        assertTrue(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:invisible")));
        assertTrue(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:wide/standard")));
        assertTrue(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:wide/scotch_yoke/large")));
        assertTrue(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:wide/scotch_yoke/comically_large")));
    }

    @Test
    void everyOtherStyleIsHidden() {
        assertFalse(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:narrow/standard")));
        assertFalse(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("railways:monobogey")));
        assertFalse(SimurailSurvivalBogeyRules.isAllowedEntry(
                ResourceLocation.parse("create:small_bogey")));
        assertFalse(SimurailSurvivalBogeyRules.isAllowedEntry(null));
    }

    @Test
    void sixOptionGroupsAreTheOnesTheUserNamed() {
        assertEquals(6, SimurailSurvivalBogeyRules.HIDDEN_OPTION_GROUPS.size());
        assertTrue(SimurailSurvivalBogeyRules.HIDDEN_OPTION_GROUPS.contains("physics"));
        assertTrue(SimurailSurvivalBogeyRules.HIDDEN_OPTION_GROUPS.contains("connector"));
        assertFalse(SimurailSurvivalBogeyRules.HIDDEN_OPTION_GROUPS.contains("stress"),
                "应力条是生存模式唯一保留的选项");
    }

    @Test
    void typeChangesOnlyLandOnWhitelistedStyles() {
        assertTrue(SimurailSurvivalBogeyRules.keepsType(true, false), "不变更永远放行");
        assertTrue(SimurailSurvivalBogeyRules.keepsType(false, true));
        assertFalse(SimurailSurvivalBogeyRules.keepsType(false, false));
    }

    @Test
    void stressIsClampedToTheSameRangeTheScreenUses() {
        assertEquals(0.0F, SimurailSurvivalBogeyRules.sanitizeStress(-5.0F));
        assertEquals(8.0F, SimurailSurvivalBogeyRules.sanitizeStress(8.0F));
        assertEquals(32.0F, SimurailSurvivalBogeyRules.sanitizeStress(128.0F));
        assertEquals(0.0F, SimurailSurvivalBogeyRules.sanitizeStress(Float.NaN));
    }
}
