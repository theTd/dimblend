package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ChassisRegistryTest {
    private static final UUID POWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CARRIAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void registrationStoresExplicitNominalHeight() {
        ChassisRegistry registry = new ChassisRegistry();
        assertTrue(registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0));
        ChassisRegistry.Entry entry = registry.get(POWER_ID);
        assertNotNull(entry);
        assertEquals(ChassisBlueprint.POWER, entry.blueprint());
        assertEquals(66.0, entry.nominalHeight());
        assertEquals(ChassisRegistry.Lifecycle.REGISTERED, entry.lifecycle());
        assertEquals(1, registry.size());
    }

    @Test
    void calibrationChangesOnlyTheNominalHeight() {
        ChassisRegistry registry = new ChassisRegistry();
        registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0);
        assertTrue(registry.calibrate(POWER_ID, 66.25));
        ChassisRegistry.Entry entry = registry.get(POWER_ID);
        assertEquals(66.25, entry.nominalHeight());
        assertEquals(ChassisBlueprint.POWER, entry.blueprint());
        assertEquals(ChassisRegistry.Lifecycle.REGISTERED, entry.lifecycle());
    }

    @Test
    void derailAndUnloadKeepIdentityAndHeight() {
        ChassisRegistry registry = new ChassisRegistry();
        registry.register(CARRIAGE_ID, ChassisBlueprint.CARRIAGE, 65.5);
        assertTrue(registry.mark(CARRIAGE_ID, ChassisRegistry.Lifecycle.DERAILED));
        assertEquals(ChassisRegistry.Lifecycle.DERAILED, registry.get(CARRIAGE_ID).lifecycle());
        assertEquals(65.5, registry.get(CARRIAGE_ID).nominalHeight());

        assertTrue(registry.mark(CARRIAGE_ID, ChassisRegistry.Lifecycle.UNLOADED));
        assertEquals(ChassisRegistry.Lifecycle.UNLOADED, registry.get(CARRIAGE_ID).lifecycle());
        assertEquals(ChassisBlueprint.CARRIAGE, registry.get(CARRIAGE_ID).blueprint());
    }

    @Test
    void onlyExplicitRemovalDeletesTheIdentity() {
        ChassisRegistry registry = new ChassisRegistry();
        registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0);
        assertTrue(registry.mark(POWER_ID, ChassisRegistry.Lifecycle.ACTIVE));
        assertNotNull(registry.get(POWER_ID));
        assertTrue(registry.remove(POWER_ID));
        assertNull(registry.get(POWER_ID));
        assertFalse(registry.remove(POWER_ID));
    }

    @Test
    void nbtRoundTripKeepsEverything() {
        ChassisRegistry registry = new ChassisRegistry();
        registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0);
        registry.mark(POWER_ID, ChassisRegistry.Lifecycle.DERAILED);
        registry.register(CARRIAGE_ID, ChassisBlueprint.CARRIAGE, 65.5);

        ChassisRegistry restored = ChassisRegistry.load(registry.save(new CompoundTag()));
        assertEquals(2, restored.size());
        assertEquals(ChassisBlueprint.POWER, restored.get(POWER_ID).blueprint());
        assertEquals(66.0, restored.get(POWER_ID).nominalHeight());
        assertEquals(ChassisRegistry.Lifecycle.DERAILED, restored.get(POWER_ID).lifecycle());
        assertEquals(ChassisBlueprint.CARRIAGE, restored.get(CARRIAGE_ID).blueprint());
        assertEquals(65.5, restored.get(CARRIAGE_ID).nominalHeight());
    }

    @Test
    void invalidNominalHeightsAreRejected() {
        ChassisRegistry registry = new ChassisRegistry();
        assertFalse(registry.register(POWER_ID, ChassisBlueprint.POWER, Double.NaN));
        assertFalse(registry.register(POWER_ID, ChassisBlueprint.POWER, Double.POSITIVE_INFINITY));
        assertFalse(registry.register(POWER_ID, ChassisBlueprint.POWER, -65.0));
        assertFalse(registry.register(POWER_ID, ChassisBlueprint.POWER, 321.0));
        registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0);
        assertFalse(registry.calibrate(POWER_ID, Double.NEGATIVE_INFINITY));
        assertEquals(66.0, registry.get(POWER_ID).nominalHeight());
    }

    @Test
    void duplicateRegistrationReplacesBlueprintAndHeight() {
        ChassisRegistry registry = new ChassisRegistry();
        registry.register(POWER_ID, ChassisBlueprint.POWER, 66.0);
        registry.register(POWER_ID, ChassisBlueprint.CARRIAGE, 65.0);
        assertEquals(1, registry.size());
        assertEquals(ChassisBlueprint.CARRIAGE, registry.get(POWER_ID).blueprint());
        assertEquals(65.0, registry.get(POWER_ID).nominalHeight());
        assertEquals(ChassisRegistry.Lifecycle.REGISTERED, registry.get(POWER_ID).lifecycle());
    }

    @Test
    void corruptNbtEntryIsSkippedWithoutPoisoningTheRest() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag bad = new CompoundTag();
        bad.putString("id", "not-a-uuid");
        bad.putString("blueprint", "POWER");
        bad.putDouble("nominalHeight", 66.0);
        bad.putString("lifecycle", "REGISTERED");
        list.add(bad);
        CompoundTag good = new CompoundTag();
        good.putString("id", POWER_ID.toString());
        good.putString("blueprint", "POWER");
        good.putDouble("nominalHeight", 66.0);
        good.putString("lifecycle", "ACTIVE");
        list.add(good);
        tag.put("entries", list);

        ChassisRegistry restored = ChassisRegistry.load(tag);
        assertEquals(1, restored.size());
        assertEquals(ChassisRegistry.Lifecycle.ACTIVE, restored.get(POWER_ID).lifecycle());
    }
}
