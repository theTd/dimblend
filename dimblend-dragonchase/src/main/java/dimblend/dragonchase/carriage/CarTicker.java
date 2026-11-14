package dimblend.dragonchase.carriage;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.CarMotion;
import dimblend.dragonchase.structure.CarPlans;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The server-thread half of the car rules: red dust, the brake car's redstone check and eight
 * seconds of black smoke, the crystals riding a car, the chewing rule, the nest's portal hint, and
 * the deadlines of the two removal effects.
 *
 * <p><b>Only the corridor dimension is walked.</b> The cars are built in {@code dimblend:rotating}
 * (the corridor the other DimBlend features live in); every other dimension costs one string
 * comparison per tick and nothing else.
 *
 * <p><b>Loaded chunks only.</b> Rules read blocks at plot positions of sub-levels Sable currently
 * holds, and a sub-level that is not loaded is not in the container — so a missing sub-level means
 * "away", never "gone", and nothing here loads a chunk.
 *
 * <p><b>Broken cars must not break the tick.</b> Each car's rules run inside a try/catch that logs
 * the sub-level id once per failure: a physics-driven world keeps running while one car misbehaves.
 */
public final class CarTicker {

    /** The corridor dimension the three cars belong to. */
    public static final String ROTATING_DIMENSION = "dimblend:rotating";

    private CarTicker() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (isCorridor(level)) {
                tick(level);
            }
        }
    }

    /** True when this level is the rotating corridor. */
    public static boolean isCorridor(ServerLevel level) {
        return level.dimension().location().toString().equals(ROTATING_DIMENSION);
    }

    /** One server tick of every registered car in this level. */
    public static void tick(ServerLevel level) {
        CarRegistry registry = CarRegistry.get(level);
        if (!registry.hasCars()) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Set<UUID> forceLoaded = forceLoadedIds(container);
        boolean dirty = false;
        for (CarRegistry.Car record : registry.snapshot()) {
            if (record.removed) {
                continue;
            }
            ServerSubLevel car = resolve(container, record.id);
            if (car == null) {
                // Not loaded right now: the same answer as "too far away to matter".
                continue;
            }
            try {
                dirty |= tickCar(level, container, forceLoaded, record, car);
            } catch (RuntimeException failure) {
                DimBlendDragonChase.LOGGER.warn("Dragon Chase car {} failed: {}", record.id, failure.toString());
            }
        }
        if (dirty) {
            registry.setDirty();
        }
        // A removed car whose sub-level no longer exists cannot come back; forgetting it keeps the
        // registry (and its NBT) from growing by three records per chase, forever.
        registry.pruneRemoved(id -> resolve(container, id) != null);
    }

    private static boolean tickCar(ServerLevel level, ServerSubLevelContainer container, Set<UUID> forceLoaded,
            CarRegistry.Car record, ServerSubLevel car) {
        long now = level.getGameTime();
        if (record.dissolvePhase != CarRules.NO_PHASE || record.fallPhase != CarRules.NO_PHASE) {
            // A removal in progress owns the car; no rule may fight it.
            return CarRemoval.advance(level, record, car);
        }
        return switch (record.kind) {
            case NEST -> tickNest(level, record, car);
            case BRAKE -> tickBrake(level, container, forceLoaded, record, car, now);
            case CRYSTAL -> tickCrystal(level, container, forceLoaded, record, car, now);
        };
    }

    // ------------------------------------------------------------------ nest

    /** The nest only runs the reward hint; the portal particles stay while the nest exists. */
    private static boolean tickNest(ServerLevel level, CarRegistry.Car record, ServerSubLevel car) {
        if (!record.rewarded) {
            return false;
        }
        CarPlans.Cell chest = CarPlans.of(CarKind.NEST).chestCell();
        if (chest == null) {
            return false;
        }
        // requirements.md §3.6: the portal particles sit at the chest cell's y-1.
        Vec3 at = CarMotion.worldOf(car, chest).subtract(0.0, 1.0, 0.0);
        CarEffects.portalHint(level, at, level.getRandom());
        return false;
    }

    // ------------------------------------------------------------------ brake car

    private static boolean tickBrake(ServerLevel level, ServerSubLevelContainer container, Set<UUID> forceLoaded,
            CarRegistry.Car record, ServerSubLevel car, long now) {
        AABB box = car.boundingBox().toMojang();
        boolean dirty = false;

        CarEffects.redDust(level, box, level.getRandom());

        CarPlans.Cell bogeyCell = CarPlans.of(CarKind.BRAKE).bogey();
        BlockPos redstonePos = CarMotion.plotPos(car, bogeyCell).above();
        boolean powered = level.getBlockState(redstonePos).is(Blocks.REDSTONE_BLOCK);
        if (!powered && record.smokeUntil == 0L) {
            // Irreversible on purpose: the requirement destroys the car once its circuit is gone, and a
            // player who puts the block back inside the eight seconds has still seen the TNT fuse.
            record.smokeUntil = now + CarRules.SMOKE_TICKS;
            dirty = true;
            playPrimed(level, box);
            DimBlendDragonChase.LOGGER.info("Brake car {} lost its redstone block; smoking for {} ticks",
                    record.id, CarRules.SMOKE_TICKS);
        }
        if (record.smokeUntil != 0L) {
            CarEffects.blackSmoke(level, box, level.getRandom());
            if (now >= record.smokeUntil) {
                record.smokeUntil = 0L;
                DragonCarriages.fallAway(level, car);
                return true;
            }
        }
        return dirty || chewIfPushed(level, container, forceLoaded, record, car, now);
    }

    /** The requirement's single TNT-primed sound when the brake car's circuit dies. */
    private static void playPrimed(ServerLevel level, AABB box) {
        Vec3 centre = box.getCenter();
        level.playSound(null, centre.x, centre.y, centre.z,
                SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------ crystal car

    private static boolean tickCrystal(ServerLevel level, ServerSubLevelContainer container,
            Set<UUID> forceLoaded, CarRegistry.Car record, ServerSubLevel car, long now) {
        boolean dirty = glueCrystals(level, record, car);
        if (record.crystals.isEmpty()) {
            // requirements.md §3.5: a crystal car with no crystal left falls away.
            DragonCarriages.fallAway(level, car);
            return true;
        }
        return dirty || chewIfPushed(level, container, forceLoaded, record, car, now);
    }

    /**
     * Re-glues every tracked crystal to its cell.
     *
     * <p>Sable does not carry an ordinary entity with a sub-level on the server (see
     * {@code CarAssembler#plantCrystals}), so the crystal is moved to the world position its plot cell
     * projects to. The step is a fraction of a block per tick at train speeds, which is what the
     * client's own interpolation needs: a big jump would be sent as a teleport and read as a snap.
     *
     * <p><b>A missing entity is not a dead crystal.</b> {@code level.getEntity} answers for loaded
     * entities only, so a car whose chunk is mid-load, or an entity that was unloaded to disk
     * ({@code RemovalReason.UNLOADED_TO_CHUNK}, {@code shouldSave()}), looks exactly like a crystal a
     * player destroyed. Dropping the slot on either would make the crystal car fall away for no
     * reason, so a slot is only dropped when its chunk is loaded and the entity really is gone, or
     * when the entity is gone in a way that will not come back.
     */
    private static boolean glueCrystals(ServerLevel level, CarRegistry.Car record, ServerSubLevel car) {
        List<CarRegistry.Crystal> alive = new ArrayList<>(record.crystals.size());
        for (CarRegistry.Crystal slot : record.crystals) {
            Vec3 target = CarMotion.worldOf(car, CarMotion.plotPos(car, slot.dx(), slot.dy(), slot.dz()));
            Entity entity = level.getEntity(slot.id());
            if (entity == null) {
                long chunkKey = ChunkPos.asLong(BlockPos.containing(target));
                if (!level.areEntitiesLoaded(chunkKey)) {
                    alive.add(slot);
                }
                continue;
            }
            if (entity.isRemoved()) {
                Entity.RemovalReason reason = entity.getRemovalReason();
                if (reason != null && reason.shouldSave()) {
                    // Unloaded to disk (or with its owner): the crystal still exists, it is just away.
                    alive.add(slot);
                }
                continue;
            }
            if (entity instanceof EndCrystal crystal) {
                if (crystal.position().distanceToSqr(target) > 1.0E-4) {
                    crystal.setPos(target.x, target.y, target.z);
                }
                alive.add(slot);
            }
        }
        if (alive.size() == record.crystals.size()) {
            return false;
        }
        record.crystals.clear();
        record.crystals.addAll(alive);
        return true;
    }

    // ------------------------------------------------------------------ chewing

    /**
     * The pushed-uncoupled rule, at its own cadence.
     *
     * <p>Assumption Q2 lives here: with no breakable block in the contact zone the car is only
     * pushed, which is what {@code CarChew#chew} returning false means — nothing happens.
     */
    private static boolean chewIfPushed(ServerLevel level, ServerSubLevelContainer container,
            Set<UUID> forceLoaded, CarRegistry.Car record, ServerSubLevel car, long now) {
        if (now % CarRules.CHEW_INTERVAL_TICKS != 0L) {
            return false;
        }
        if (!CarRules.isPushed(CarMotion.speed(car))) {
            return false;
        }
        if (CarChew.coupledToForceLoaded(level, car, record.kind, forceLoaded)) {
            return false;
        }
        ServerSubLevel pusher = CarChew.pusher(container, car);
        if (pusher == null) {
            return false;
        }
        return CarChew.chew(level, car, pusher);
    }

    // ------------------------------------------------------------------ helpers

    private static Set<UUID> forceLoadedIds(ServerSubLevelContainer container) {
        Set<UUID> ids = new HashSet<>();
        for (ServerSubLevel sub : container.collectForceLoadedSubLevels()) {
            ids.add(sub.getUniqueId());
        }
        return ids;
    }

    private static ServerSubLevel resolve(ServerSubLevelContainer container, UUID id) {
        if (container.getSubLevel(id) instanceof ServerSubLevel car && !car.isRemoved()) {
            return car;
        }
        return null;
    }
}
