package dimblend.dragonchase.carriage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.structure.CarKind;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * What a rotating-dimension region purge may do to the car registry: forget exactly the sub-levels
 * that were deleted, keep the rest, hand the record to the tick loop's existing prune instead of
 * dropping it mid-snapshot, and keep hold of the crystals that outlived their car. No level is
 * needed — the registry half is pure bookkeeping.
 */
class CarRegistryPurgeTest {

    @Test
    void purgeFlagsOnlyTheDeletedCars() {
        CarRegistry registry = new CarRegistry();
        UUID brake = UUID.randomUUID();
        UUID crystal = UUID.randomUUID();
        CarRegistry.Car brakeRecord = registry.register(brake, CarKind.BRAKE);
        registry.register(crystal, CarKind.CRYSTAL);

        assertTrue(registry.markPurged(Set.of(brake)));

        assertTrue(brakeRecord.removed, "a purged car is removed as far as every rule is concerned");
        assertFalse(registry.find(crystal).removed, "the other cars of the same chase stay live");
        assertFalse(registry.markPurged(Set.of(brake)), "an already removed record is not news");
        assertFalse(registry.markPurged(Set.of(UUID.randomUUID())),
                "an id this world never built (another dimension's, or a player's train) is ignored");
    }

    @Test
    void theNextPruneDropsThePurgedRecordAndKeepsTheLiveOne() {
        CarRegistry registry = new CarRegistry();
        UUID purged = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        registry.register(purged, CarKind.NEST);
        registry.register(live, CarKind.CRYSTAL);
        registry.markPurged(Set.of(purged));

        // The purge event is posted after the sub-level has left the Sable container, so this is the
        // predicate CarTicker's next pass evaluates.
        assertTrue(registry.pruneRemoved(id -> id.equals(live)));
        assertNull(registry.find(purged));
        assertNotNull(registry.find(live));
        assertTrue(registry.hasCars());
    }

    @Test
    void purgedFlagSurvivesASaveSoAReloadCannotResurrectTheCar() {
        CarRegistry registry = new CarRegistry();
        UUID id = UUID.randomUUID();
        registry.register(id, CarKind.BRAKE);
        registry.markPurged(Set.of(id));

        CarRegistry loaded = CarRegistry.load(registry.save(new CompoundTag(), null));

        assertTrue(loaded.find(id).removed);
    }

    @Test
    void coupledSoundFlagSurvivesASaveSoItPlaysOncePerCar() {
        CarRegistry registry = new CarRegistry();
        UUID played = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        registry.register(played, CarKind.BRAKE).coupledSoundPlayed = true;
        registry.register(fresh, CarKind.BRAKE);

        CarRegistry loaded = CarRegistry.load(registry.save(new CompoundTag(), null));

        assertTrue(loaded.find(played).coupledSoundPlayed, "a played car must not announce its coupling again");
        assertFalse(loaded.find(fresh).coupledSoundPlayed);

        // A save written before the flag existed has no key at all: that means "not played yet".
        CompoundTag legacy = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("id", fresh);
        entry.putString("kind", CarKind.BRAKE.name());
        ListTag cars = new ListTag();
        cars.add(entry);
        legacy.put("cars", cars);
        assertFalse(CarRegistry.load(legacy).find(fresh).coupledSoundPlayed);
    }

    @Test
    void purgedCrystalCarHandsItsCrystalsToTheOrphanSet() {
        CarRegistry registry = new CarRegistry();
        UUID car = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CarRegistry.Car record = registry.register(car, CarKind.CRYSTAL);
        record.crystals.add(new CarRegistry.Crystal(first, 1, 4, 1));
        record.crystals.add(new CarRegistry.Crystal(second, -1, 4, 1));

        registry.markPurged(Set.of(car));

        assertEquals(Set.of(first, second), registry.orphanedCrystals,
                "a crystal whose car was purged is the listener's to discard, not lost with the record");
        assertTrue(record.crystals.isEmpty(), "the pruned record must not claim the ids any more");

        assertTrue(registry.forgetOrphanedCrystal(first));
        assertEquals(Set.of(second), registry.orphanedCrystals);
        assertFalse(registry.forgetOrphanedCrystal(first), "forgetting an id twice is not news");
    }

    @Test
    void orphanedCrystalsSurviveASaveSoALaterChunkLoadCanStillDiscardThem() {
        CarRegistry registry = new CarRegistry();
        UUID crystal = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.orphanedCrystals.add(crystal);
        registry.orphanedCrystals.add(other);
        registry.forgetOrphanedCrystal(other);

        CarRegistry loaded = CarRegistry.load(registry.save(new CompoundTag(), null));

        assertEquals(Set.of(crystal), loaded.orphanedCrystals,
                "a crystal that is still unloaded after the purge must stay orphaned across a save");
    }
}
