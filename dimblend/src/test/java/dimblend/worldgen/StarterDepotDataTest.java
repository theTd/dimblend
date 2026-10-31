package dimblend.worldgen;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarterDepotDataTest {
    @Test void absentLegacyMarkerNeverEnablesDepot() {
        assertFalse(new StarterDepot.DepotData().save(new CompoundTag(), null).getBoolean("enabled"));
        assertFalse(new StarterDepot.DepotData(new CompoundTag()).save(new CompoundTag(), null).getBoolean("enabled"));
    }

    @Test void enabledAndSuppliedMarkersSurviveSaveLoad() {
        CompoundTag saved = new CompoundTag();
        saved.putBoolean("enabled", true);
        saved.putBoolean("supplied", true);
        CompoundTag restored = new StarterDepot.DepotData(saved).save(new CompoundTag(), null);
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("supplied"));
    }

    /**
     * R7: the spawn chest at (-16, 65, 0) is gone and its cell is left empty. The lamp
     * below it and the bedrock wall above stay where they were, so the column at z=0 is
     * lamp / air / air / bedrock... This is the only place the column layout is pinned
     * without a running server.
     */
    @Test void spawnChestCellIsLeftEmpty() {
        assertEquals(StarterDepot.DepotCell.LAMP, StarterDepot.cellAt(0), "dy 0 is the lamp");
        for (int dy = 1; dy <= 2; dy++) {
            assertEquals(StarterDepot.DepotCell.AIR, StarterDepot.cellAt(dy), "dy " + dy + " is empty");
        }
        for (int dy = 3; dy <= 6; dy++) {
            assertEquals(StarterDepot.DepotCell.BEDROCK, StarterDepot.cellAt(dy), "dy " + dy + " stays bedrock");
        }
    }
}
