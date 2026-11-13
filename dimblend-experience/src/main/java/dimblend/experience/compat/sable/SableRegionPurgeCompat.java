package dimblend.experience.compat.sable;

import dimblend.api.purge.SableSubLevelsPurgedEvent;
import dimblend.experience.chassis.ChassisRegistryData;
import java.util.UUID;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Listens for rotating-dimension Sable purge notifications so {@code ChassisRegistryData} drops
 * deleted vehicle identities while {@code RewardWagonSites} keeps its built records.
 */
public final class SableRegionPurgeCompat {

    private SableRegionPurgeCompat() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableRegionPurgeCompat::onSableSubLevelsPurged);
    }

    private static void onSableSubLevelsPurged(SableSubLevelsPurgedEvent event) {
        if (event.subLevelIds().isEmpty()) {
            return;
        }
        ChassisRegistryData registryData = ChassisRegistryData.get(event.level());
        boolean changed = false;
        for (UUID id : event.subLevelIds()) {
            changed |= registryData.registry().remove(id);
        }
        if (changed) {
            registryData.setDirty();
        }
    }
}
