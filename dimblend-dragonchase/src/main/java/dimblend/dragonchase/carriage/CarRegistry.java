package dimblend.dragonchase.carriage;

import dimblend.dragonchase.structure.CarKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Which cars this world has built, what kind each one is, and the state of every rule that has to
 * survive a save: smoke timers, the fall-away phase, the crystal list, the reward flag.
 *
 * <p><b>Keyed by sub-level UUID.</b> A car that has been pushed, coupled and towed keeps its
 * identity, whereas its position changes every tick; the reward wagon learned the same lesson the
 * hard way ("not found" is not "gone").
 *
 * <p><b>Deadlines, not countdowns.</b> Every timer is a game-time deadline, so a pause or a slow
 * tick cannot stretch "8 seconds of smoke" into minutes.
 *
 * <p><b>Nothing about the blocks is stored.</b> A car's blocks are read from its plot on demand;
 * the record only says which sub-level it is and what the rules have decided so far.
 */
public final class CarRegistry extends SavedData {
    private static final String FILE_ID = "dimblend_dragon_chase_cars";
    private static final Factory<CarRegistry> FACTORY =
            new Factory<>(CarRegistry::new, (tag, registries) -> load(tag), null);

    /** One end crystal riding a car: its entity id and the cell it is glued to, as layout offsets. */
    public record Crystal(UUID id, int dx, int dy, int dz) {
    }

    /** One car this world built, and the rule state that must persist with it. */
    public static final class Car {
        public final UUID id;
        public final CarKind kind;
        /** Removed from the world; no rule may look at it again. */
        public boolean removed;
        /** Game time at which the black smoke started, 0 when it has not. */
        public long smokeUntil;
        /** Fall-away phase, one of the {@code CarRules.FALL_*} constants. */
        public int fallPhase;
        /** Which side of the corridor a fall-away throws the car to: +1 or -1. */
        public int fallSide = 1;
        /** Dissolve phase, one of the {@code CarRules.DISSOLVING} constants (0 = none). */
        public int dissolvePhase;
        /** Game time at which the current removal phase ends. */
        public long phaseDeadline;
        /** The kill rewards have been written and the portal hint runs. */
        public boolean rewarded;
        /** Retired when the chase that spawned this car ended; its crystals no longer heal later dragons. */
        public boolean retired;
        /** Live end crystals of a crystal car, in spawn order. */
        public final List<Crystal> crystals = new ArrayList<>();

        Car(UUID id, CarKind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    private final Map<UUID, Car> cars = new LinkedHashMap<>();

    /**
     * Crystals that outlived their car. A car is a Sable sub-level and a crystal is a world-space
     * entity, so a car near a 512-block region boundary can be purged while its crystal sits in the
     * neighbouring region. {@code CarPurgeListener} discards such a crystal the moment it is in the
     * level and drops the id here; an id whose entity is not loaded stays until it joins, because
     * "not loaded" and "already gone" cannot be told apart from {@code level.getEntity}.
     */
    public final Set<UUID> orphanedCrystals = new LinkedHashSet<>();

    /** Package-private: the unit tests build an empty registry without a level. */
    CarRegistry() {
    }

    public static CarRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    /** Records a car, or returns the existing record when the identity is already known. */
    public Car register(UUID id, CarKind kind) {
        Car existing = cars.get(id);
        if (existing != null) {
            return existing;
        }
        Car created = new Car(id, kind);
        cars.put(id, created);
        setDirty();
        return created;
    }

    /** The record for one sub-level, or {@code null} when this world did not build it. */
    @Nullable
    public Car find(UUID id) {
        return id == null ? null : cars.get(id);
    }

    /** True when at least one car is registered, so an idle tick can skip the whole pass. */
    public boolean hasCars() {
        return !cars.isEmpty();
    }

    /** A snapshot, so the tick loop can mutate records while iterating. */
    public List<Car> snapshot() {
        return new ArrayList<>(cars.values());
    }

    /**
     * Forgets every removed car that is not in the container any more.
     *
     * <p>A record has to outlive its sub-level for a while — a removal sets {@code removed} in the
     * same tick the sub-level goes, and the tick loop's snapshot may still be walking it — but once
     * the container no longer knows the id nothing can ever read the record again. Pruning keeps
     * this saved data at the chase's three cars instead of three more per chase.
     *
     * @param stillInContainer the ids the Sable container still has; {@code removed} records whose id
     *                         it does not have are dropped
     * @return true when at least one record was dropped, in which case the data is dirty
     */
    public boolean pruneRemoved(Predicate<UUID> stillInContainer) {
        boolean dropped = cars.values().removeIf(car -> car.removed && !stillInContainer.test(car.id));
        if (dropped) {
            setDirty();
        }
        return dropped;
    }

    /**
     * Marks every car whose Sable sub-level a rotating-dimension region purge deleted.
     *
     * <p>{@code dimblend}'s {@code RegionSablePurger} only deletes sub-levels that are unloaded and
     * in a region no player can come back to, so those cars are gone for good: none of them is
     * mid-removal and no later pass can find the id again. The record is flagged {@link Car#removed}
     * — the same flag {@code CarRemoval} sets — so the tick loop stops walking it at once and the
     * next pass's {@link #pruneRemoved} drops it once the container confirms the id is absent.
     *
     * <p>Its crystal slots move to {@link #orphanedCrystals}: the entities are world-space and the
     * car's own region is gone, so they are the caller's to discard (see {@code CarPurgeListener}).
     * Keeping them anywhere else would lose track of them once the pruned record is gone.
     *
     * @param ids the purged sub-level ids, exactly as the purge event reports them
     * @return true when at least one record was flagged, in which case the data is dirty
     */
    public boolean markPurged(Set<UUID> ids) {
        boolean changed = false;
        for (UUID id : ids) {
            Car car = cars.get(id);
            if (car == null || car.removed) {
                continue;
            }
            car.removed = true;
            changed = true;
            for (Crystal crystal : car.crystals) {
                orphanedCrystals.add(crystal.id());
            }
            car.crystals.clear();
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /**
     * Drops one orphaned crystal id once its entity is gone or was discarded.
     *
     * @return true when the id was in the set, in which case the data is dirty
     */
    public boolean forgetOrphanedCrystal(UUID id) {
        boolean removed = orphanedCrystals.remove(id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Marks every live crystal car retired so completed/failed chases cannot heal future dragons. */
    boolean retireCrystalHealing() {
        boolean changed = false;
        for (Car car : cars.values()) {
            if (!car.removed && !car.retired && car.kind == CarKind.CRYSTAL) {
                car.retired = true;
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Car car : cars.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", car.id);
            entry.putString("kind", car.kind.name());
            entry.putBoolean("removed", car.removed);
            entry.putLong("smoke_until", car.smokeUntil);
            entry.putInt("fall_phase", car.fallPhase);
            entry.putInt("fall_side", car.fallSide);
            entry.putInt("dissolve_phase", car.dissolvePhase);
            entry.putLong("phase_deadline", car.phaseDeadline);
            entry.putBoolean("rewarded", car.rewarded);
            entry.putBoolean("retired", car.retired);
            ListTag crystals = new ListTag();
            for (Crystal crystal : car.crystals) {
                CompoundTag slot = new CompoundTag();
                slot.putUUID("id", crystal.id());
                slot.putInt("dx", crystal.dx());
                slot.putInt("dy", crystal.dy());
                slot.putInt("dz", crystal.dz());
                crystals.add(slot);
            }
            entry.put("crystals", crystals);
            list.add(entry);
        }
        tag.put("cars", list);
        // Orphans outlive their car, so they cannot live in a car's entry: a record that has been
        // pruned is gone by the time its crystal is first loaded again.
        ListTag orphans = new ListTag();
        for (UUID id : orphanedCrystals) {
            CompoundTag slot = new CompoundTag();
            slot.putUUID("id", id);
            orphans.add(slot);
        }
        tag.put("orphaned_crystals", orphans);
        return tag;
    }

    /** Package-private so the unit tests can restore a registry from NBT without a level. */
    static CarRegistry load(CompoundTag tag) {
        CarRegistry data = new CarRegistry();
        ListTag list = tag.getList("cars", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id")) {
                continue;
            }
            CarKind kind = kindByName(entry.getString("kind"));
            if (kind == null) {
                continue; // A kind this build does not know cannot be ticked safely.
            }
            Car car = new Car(entry.getUUID("id"), kind);
            car.removed = entry.getBoolean("removed");
            car.smokeUntil = entry.getLong("smoke_until");
            car.fallPhase = entry.getInt("fall_phase");
            car.fallSide = entry.contains("fall_side", Tag.TAG_ANY_NUMERIC) ? entry.getInt("fall_side") : 1;
            car.dissolvePhase = entry.getInt("dissolve_phase");
            car.phaseDeadline = entry.getLong("phase_deadline");
            car.rewarded = entry.getBoolean("rewarded");
            car.retired = entry.getBoolean("retired");
            ListTag crystals = entry.getList("crystals", Tag.TAG_COMPOUND);
            for (int slot = 0; slot < crystals.size(); slot++) {
                CompoundTag crystal = crystals.getCompound(slot);
                if (!crystal.hasUUID("id")) {
                    continue;
                }
                car.crystals.add(new Crystal(crystal.getUUID("id"), crystal.getInt("dx"),
                        crystal.getInt("dy"), crystal.getInt("dz")));
            }
            data.cars.put(car.id, car);
        }
        ListTag orphans = tag.getList("orphaned_crystals", Tag.TAG_COMPOUND);
        for (int i = 0; i < orphans.size(); i++) {
            CompoundTag slot = orphans.getCompound(i);
            if (slot.hasUUID("id")) {
                data.orphanedCrystals.add(slot.getUUID("id"));
            }
        }
        return data;
    }

    @Nullable
    private static CarKind kindByName(String name) {
        for (CarKind kind : CarKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
