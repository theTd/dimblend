package dimblend.dragonchase.carriage;

import dimblend.api.purge.SableSubLevelsPurgedEvent;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Drops car identities — and the crystals that outlived them — when the rotating dimension's region
 * purge deletes a car.
 *
 * <p>{@code dimblend} purges terrain the train will never come back to, and along with it the Sable
 * sub-levels that stood there ({@code RegionSablePurger}); it then fires
 * {@link SableSubLevelsPurgedEvent} with the ids it removed, exactly as
 * {@code dimblend-experience}'s {@code SableRegionPurgeCompat} consumes it for
 * {@code ChassisRegistryData}. A car left far behind by a finished chase is the normal case here,
 * so the registry has to hear about it: nothing else in this mod ever sees such a car again — the
 * tick loop treats "not in the container" as "not loaded", which would keep the record (and its
 * {@code dimblend.api.NonCrewCarriages} claim) alive forever.
 *
 * <p><b>The nest only reaches this path when it may.</b> {@code ChaseDirector} answers
 * {@code NonCrewCarriages.isForceLoadProtected} for a {@code WAITING}/{@code ACTIVE} nest, and
 * {@code RegionSablePurger} refuses to unticket a protected chain, so a chase still waiting for the
 * train or still running can never be purged. A {@code WON} nest is deliberately not protected and
 * a purged one is simply forgotten by the director's existing missing-nest rule, which is the
 * permanent form of "abandoned chase". Nothing extra is owed for
 * {@code DragonChaseSavedData} here.
 *
 * <p><b>Crystals are world-space entities, cars are sub-levels.</b> A crystal car near a
 * 512-block region boundary can be purged while its {@link EndCrystal} sits in the neighbouring
 * region, so the crystals of a purged car are discarded here if they are in the level, and their
 * ids are parked in {@link CarRegistry#orphanedCrystals} until they do join — a {@code null} from
 * {@code level.getEntity} cannot tell "not loaded" from "already gone".
 */
public final class CarPurgeListener {

    private CarPurgeListener() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CarPurgeListener::onSableSubLevelsPurged);
        NeoForge.EVENT_BUS.addListener(CarPurgeListener::onEntityJoin);
    }

    private static void onSableSubLevelsPurged(SableSubLevelsPurgedEvent event) {
        if (event.subLevelIds().isEmpty()) {
            return;
        }
        ServerLevel level = event.level();
        // Cars only ever exist in the corridor, and CarRegistry is per level: skip every other
        // dimension without even loading its saved data.
        if (!CarTicker.isCorridor(level)) {
            return;
        }
        CarRegistry registry = CarRegistry.get(level);
        registry.markPurged(event.subLevelIds());
        discardLoadedOrphans(level, registry);
    }

    /**
     * The second chance for crystals the purge could not reach: an entity whose chunk was not
     * loaded joins the level as soon as that chunk does, and loading is exactly when the id can be
     * resolved again.
     */
    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !CarTicker.isCorridor(level)) {
            return;
        }
        if (!(event.getEntity() instanceof EndCrystal crystal)) {
            return;
        }
        CarRegistry registry = CarRegistry.get(level);
        if (!registry.orphanedCrystals.contains(crystal.getUUID())) {
            return;
        }
        crystal.discard();
        registry.forgetOrphanedCrystal(crystal.getUUID());
    }

    /** Discards every orphan whose entity this level holds right now, and forgets those ids. */
    private static void discardLoadedOrphans(ServerLevel level, CarRegistry registry) {
        if (registry.orphanedCrystals.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(registry.orphanedCrystals)) {
            Entity entity = level.getEntity(id);
            if (entity == null) {
                continue; // Not loaded (or already gone): only the join listener can tell later.
            }
            if (entity instanceof EndCrystal crystal && !crystal.isRemoved()) {
                crystal.discard();
            }
            registry.forgetOrphanedCrystal(id);
        }
    }
}
