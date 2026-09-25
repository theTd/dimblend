package dimblend.experience.compat.copycat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CopycatObsidianHardnessTest {

    @Test
    void matchesKnownCopycatIds() {
        assertTrue(CopycatObsidianHardness.isCopycatId("copycats", "copycat_block"));
        assertTrue(CopycatObsidianHardness.isCopycatId("copycats", "copycat_slab"));
        assertTrue(CopycatObsidianHardness.isCopycatId("copycats", "wrapped_copycat"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create", "copycat_panel"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create", "copycat_step"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create", "copycat_bars"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create", "copycat_base"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create_connected", "copycat_slab"));
        assertTrue(CopycatObsidianHardness.isCopycatId("create_connected", "wrapped_copycat_stairs"));
    }

    @Test
    void rejectsUnrelatedIds() {
        assertFalse(CopycatObsidianHardness.isCopycatId("minecraft", "obsidian"));
        assertFalse(CopycatObsidianHardness.isCopycatId("minecraft", "stone"));
        assertFalse(CopycatObsidianHardness.isCopycatId("create", "andesite_casing"));
        assertFalse(CopycatObsidianHardness.isCopycatId("copycats", "cogwheel"));
        assertFalse(CopycatObsidianHardness.isCopycatId("railways", "track"));
    }
}
