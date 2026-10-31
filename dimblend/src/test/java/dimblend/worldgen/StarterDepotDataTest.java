package dimblend.worldgen;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarterDepotDataTest {
    @Test void absentLegacyMarkerNeverEnablesDepot() {
        assertFalse(new StarterDepot.DepotData().save(new CompoundTag(), null).getBoolean("enabled"));
        assertFalse(new StarterDepot.DepotData(new CompoundTag()).save(new CompoundTag(), null).getBoolean("enabled"));
    }

    @Test void NewWorldAndSingleInventoryGrantSurviveSaveLoad() {
        CompoundTag saved = new CompoundTag();
        saved.putBoolean("enabled", true);
        saved.putBoolean("supplied", true);
        CompoundTag restored = new StarterDepot.DepotData(saved).save(new CompoundTag(), null);
        assertTrue(restored.getBoolean("enabled"));
        assertTrue(restored.getBoolean("supplied"));
    }
}
