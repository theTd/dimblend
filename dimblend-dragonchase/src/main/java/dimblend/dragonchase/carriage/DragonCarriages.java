package dimblend.dragonchase.carriage;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.structure.CarKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime behaviour of the cars this mod built: the persisted registry of car kinds, the
 * brake/crystal car rules of §3.5 (particles, locked bogie, chewing while pushed uncoupled,
 * fall-away triggers) and the two removal effects.
 *
 * <p>The rules themselves run from {@link CarTicker}, which {@link CarriageBootstrap} registers; this
 * class is the entry point the director (W5) and the dragon (W3) call, so neither has to know how a
 * rule or an effect is stored. Server-thread only.
 */
public final class DragonCarriages {

    /** Records {@code car} as {@code kind} (persisted). Called by the builders. */
    public static void register(ServerLevel level, ServerSubLevel car, CarKind kind) {
        CarRegistry.get(level).register(car.getUniqueId(), kind);
    }

    /** The kind of a registered car, or null. */
    @Nullable
    public static CarKind kindOf(ServerLevel level, UUID subLevel) {
        CarRegistry.Car record = CarRegistry.get(level).find(subLevel);
        return record == null ? null : record.kind;
    }

    /**
     * Every loaded end crystal on registered crystal cars in this level.
     *
     * <p>Resolved from the crystals this mod spawned and tracks, so a crystal a player placed beside
     * a car is never returned. It answers with entities, so it can only name crystals that are
     * loaded; {@link #healingCrystalCount} is the distance-free count the healing rule uses.
     */
    public static List<EndCrystal> aliveCrystals(ServerLevel level) {
        List<EndCrystal> out = new ArrayList<>();
        for (CarRegistry.Car record : CarRegistry.get(level).snapshot()) {
            if (record.removed || record.retired || record.kind != CarKind.CRYSTAL) {
                continue;
            }
            for (CarRegistry.Crystal slot : record.crystals) {
                Entity entity = level.getEntity(slot.id());
                if (entity instanceof EndCrystal crystal && !crystal.isRemoved()) {
                    out.add(crystal);
                }
            }
        }
        return out;
    }

    /**
     * Every crystal on a registered crystal car of the active chase that still counts for healing.
     *
     * <p>Unlimited range (requirements.md §1 {@code crystal-heal}: "不限距离，每颗水晶每秒回 1 点"),
     * so a car behind the train heals as well. A slot whose entity is not loaded counts: the crystal
     * exists, its chunk is simply away. Loaded cars already have genuinely dead slots pruned by
     * {@code CarTicker#glueCrystals}, so "unloaded" here really means "out of sight, still alive".
     * Cars from earlier finished chases have {@code record.retired == true} and never heal later
     * generations across one-way warp gates.
     */
    public static int healingCrystalCount(ServerLevel level) {
        int count = 0;
        for (CarRegistry.Car record : CarRegistry.get(level).snapshot()) {
            if (record.removed || record.retired || record.kind != CarKind.CRYSTAL) {
                continue;
            }
            for (CarRegistry.Crystal slot : record.crystals) {
                Entity entity = level.getEntity(slot.id());
                if (entity == null) {
                    count++;
                    continue;
                }
                if (entity instanceof EndCrystal crystal && (!crystal.isRemoved()
                        || (crystal.getRemovalReason() != null && crystal.getRemovalReason().shouldSave()))) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Retires every existing crystal car from healing future dragons once a chase ends (or before a
     * new chase begins), while keeping local crystal gluing and fall-away intact on loaded cars.
     */
    public static void retireHealing(ServerLevel level) {
        CarRegistry.get(level).retireCrystalHealing();
    }

    /**
     * Removes every tracked Dragon Chase car in {@code level} (loaded sub-levels via
     * {@link #removeNow}, unloaded records marked removed with cleared crystal slots) for
     * {@code /dragonchase reset}.
     */
    public static void clearAll(ServerLevel level) {
        CarRegistry registry = CarRegistry.get(level);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        boolean dirty = false;
        for (CarRegistry.Car record : registry.snapshot()) {
            if (record.removed) {
                continue;
            }
            if (container != null
                    && container.getSubLevel(record.id) instanceof ServerSubLevel sub
                    && !sub.isRemoved()) {
                CarRemoval.removeNow(level, sub);
            } else {
                for (CarRegistry.Crystal slot : record.crystals) {
                    Entity entity = level.getEntity(slot.id());
                    if (entity instanceof EndCrystal crystal && !crystal.isRemoved()) {
                        crystal.discard();
                    }
                }
                record.crystals.clear();
                record.removed = true;
                dirty = true;
            }
        }
        if (dirty) {
            registry.setDirty();
        }
    }

    /**
     * The ids of every car this world still runs (not removed), for {@code dimblend.api.NonCrewCarriages}.
     *
     * <p>The cross-mod question is "does this sub-level belong to something other than a player's
     * train?"; the cars are registered here, so this set is the answer for the corridor.
     */
    public static Set<UUID> activeCarIds(ServerLevel level) {
        Set<UUID> ids = new HashSet<>();
        for (CarRegistry.Car record : CarRegistry.get(level).snapshot()) {
            if (!record.removed) {
                ids.add(record.id);
            }
        }
        return ids;
    }

    /**
     * Reward-wagon style dissolve (§3.3 warp gate rule): uncouple, mob-death particle burst of
     * radius 5 at the bogie's y-1 with the dissolve sound, then remove the sub-level 10 ticks later.
     */
    public static void dissolve(ServerLevel level, ServerSubLevel car) {
        CarRemoval.dissolve(level, car);
    }

    /**
     * Fall-away (§3.5, decision fall-style): break the car's own couplers without drops, disable the
     * bogie, launch 45° upward toward a random ±Z side, then explosion particles and sound (no
     * damage, no block damage) and remove the sub-level.
     */
    public static void fallAway(ServerLevel level, ServerSubLevel car) {
        CarRemoval.fallAway(level, car);
    }

    /** Immediate removal with no effect (nest deleted on chase failure). */
    public static void removeNow(ServerLevel level, ServerSubLevel car) {
        CarRemoval.removeNow(level, car);
    }

    private DragonCarriages() {
    }
}
