package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VoidscapeNetherDezombifyTest {
    @Test
    void zombifiedNetherMobsMapToLivingForms() {
        assertEquals("piglin", VoidscapeNetherDezombifyRules.replacementPath("minecraft", "zombified_piglin"));
        assertEquals("hoglin", VoidscapeNetherDezombifyRules.replacementPath("minecraft", "zoglin"));
    }

    @Test
    void otherMobsAreUnchanged() {
        assertNull(VoidscapeNetherDezombifyRules.replacementPath("minecraft", "blaze"));
        assertNull(VoidscapeNetherDezombifyRules.replacementPath("minecraft", "piglin"));
        assertNull(VoidscapeNetherDezombifyRules.replacementPath("minecraft", "hoglin"));
        assertNull(VoidscapeNetherDezombifyRules.replacementPath("voidscape", "voidling"));
        assertNull(VoidscapeNetherDezombifyRules.replacementPath("minecraft", null));
        assertNull(VoidscapeNetherDezombifyRules.replacementPath(null, "zombified_piglin"));
    }

    @Test
    void onlyZoglinNeedsGroundPlacement() {
        assertTrue(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", "zoglin"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", "zombified_piglin"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", "hoglin"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", "ghast"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", "blaze"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("voidscape", "voidling"));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement("minecraft", null));
        assertFalse(VoidscapeNetherDezombifyRules.needsGroundPlacement(null, "zoglin"));
    }

    @Test
    void onlyVoidscapeNetherBiomeMatches() {
        assertTrue(VoidscapeNetherDezombifyRules.isVoidscapeNether("voidscape", "nether"));
        assertFalse(VoidscapeNetherDezombifyRules.isVoidscapeNether("voidscape", "overworld"));
        assertFalse(VoidscapeNetherDezombifyRules.isVoidscapeNether("minecraft", "nether_wastes"));
        assertFalse(VoidscapeNetherDezombifyRules.isVoidscapeNether("minecraft", "crimson_forest"));
    }
}
