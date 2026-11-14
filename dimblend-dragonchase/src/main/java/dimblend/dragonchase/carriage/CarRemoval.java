package dimblend.dragonchase.carriage;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.CarMotion;
import dimblend.dragonchase.structure.CarPlans;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

/**
 * The three ways a car ends: {@code dissolve} (the reward wagon's warp-gate exit), {@code fallAway}
 * (the requirement's destruction sequence) and {@code removeNow}. Each one is a small state machine
 * whose steps are deadlines on {@link CarRegistry.Car}, driven by {@code CarTicker}; the two
 * immediate halves (the first coupler write, the first particle burst) happen inside the call so a
 * director can trigger them and forget them.
 *
 * <p><b>Blocks are plot blocks.</b> Every write addresses the car's own sub-level by its plot
 * position ({@code CarMotion.plotPos}), never by world coordinates: the car may be anywhere.
 * Effects are the opposite — they are watched by players, so they are played at the world position
 * the pose projects.
 */
public final class CarRemoval {

    private CarRemoval() {
    }

    /**
     * Starts the dissolve: uncouple, the mob-death burst of radius five at the bogey's y-1, and a
     * ten tick deadline after which the sub-level is gone (the reward wagon's own timings).
     */
    public static void dissolve(ServerLevel level, ServerSubLevel car) {
        CarRegistry.Car record = record(level, car);
        if (record == null) {
            warnUnregistered("dissolve", car);
            remove(level, null, car);
            return;
        }
        if (record.dissolvePhase != CarRules.NO_PHASE || record.fallPhase != CarRules.NO_PHASE) {
            return;
        }
        uncouple(level, car, record.kind);
        CarPlans.Cell bogey = CarPlans.of(record.kind).bogey();
        Vec3 centre = CarMotion.worldOf(car, bogey).subtract(0.0, 1.0, 0.0);
        CarEffects.dissolveBurst(level, centre);
        record.dissolvePhase = CarRules.DISSOLVING;
        record.phaseDeadline = level.getGameTime() + CarRules.DISSOLVE_TICKS;
        CarRegistry.get(level).setDirty();
    }

    /**
     * Starts the fall-away (decision fall-style): couplers gone with no drops, the bogey disabled
     * straight away (so the rail lock has the whole 0.2 s arm phase to release), white smoke, then the
     * car is thrown 45° up toward a random ±Z side with its X velocity kept, and about a second later
     * the explosion effect and the removal.
     */
    public static void fallAway(ServerLevel level, ServerSubLevel car) {
        CarRegistry.Car record = record(level, car);
        if (record == null) {
            warnUnregistered("fall away", car);
            remove(level, null, car);
            return;
        }
        if (record.fallPhase != CarRules.NO_PHASE || record.dissolvePhase != CarRules.NO_PHASE) {
            return;
        }
        uncouple(level, car, record.kind);
        // The bogey goes off the moment the fall starts, not at the launch: dimblend-experience's
        // ChassisRailLock holds LINEAR_Y while an enabled bogey is present and only releases it on
        // its next per-tick verifyActive(), so disabling here gives it all of FALL_ARM_TICKS to let
        // go before the 45° launch — otherwise the lock zeroes the upward vy on the launch step.
        disableBogey(level, car, record.kind);
        CarEffects.whiteSmoke(level, car.boundingBox().toMojang(), level.getRandom());
        record.fallPhase = CarRules.FALL_ARMED;
        record.phaseDeadline = level.getGameTime() + CarRules.FALL_ARM_TICKS;
        // The side is drawn once, now, so a reloaded save cannot switch sides mid-flight.
        record.fallSide = level.getRandom().nextBoolean() ? 1 : -1;
        CarRegistry.get(level).setDirty();
        DimBlendDragonChase.LOGGER.info("Dragon Chase car {} is falling away to {}", car.getUniqueId(),
                record.fallSide > 0 ? "+Z" : "-Z");
    }

    /** Immediate removal, no effect (the nest when the chase fails). */
    public static void removeNow(ServerLevel level, ServerSubLevel car) {
        remove(level, record(level, car), car);
    }

    /**
     * Advances one car's removal state machine by one tick.
     *
     * @return true when the registry changed and must be saved
     */
    public static boolean advance(ServerLevel level, CarRegistry.Car record, ServerSubLevel car) {
        long now = level.getGameTime();
        if (record.dissolvePhase == CarRules.DISSOLVING) {
            if (now < record.phaseDeadline) {
                return false;
            }
            remove(level, record, car);
            return true;
        }
        if (record.fallPhase == CarRules.FALL_ARMED) {
            if (now < record.phaseDeadline) {
                return false;
            }
            disableBogey(level, car, record.kind);
            launch(car, record);
            record.fallPhase = CarRules.FALL_FLYING;
            record.phaseDeadline = now + CarRules.FALL_FLIGHT_TICKS;
            return true;
        }
        if (record.fallPhase == CarRules.FALL_FLYING) {
            if (now < record.phaseDeadline) {
                // The first two ticks are the window in which the LINEAR_Y lock could still be in
                // effect (a reload or a tick-order delay can resurrect it after the armed phase let
                // it go). With a positive vy already in place this does nothing.
                if (now <= record.phaseDeadline - CarRules.FALL_FLIGHT_TICKS + 2
                        && CarMotion.velocity(car).y <= 0.1D) {
                    disableBogey(level, car, record.kind);
                    launch(car, record);
                }
                return false;
            }
            CarEffects.explosion(level, car.boundingBox().toMojang().getCenter());
            record.fallPhase = CarRules.FALL_EXPLODED;
            record.phaseDeadline = now + CarRules.EXPLOSION_HOLD_TICKS;
            return true;
        }
        if (record.fallPhase == CarRules.FALL_EXPLODED) {
            if (now < record.phaseDeadline) {
                return false;
            }
            remove(level, record, car);
            return true;
        }
        return false;
    }

    /**
     * Breaks the car's own couplers with no drops.
     *
     * <p>{@code removePartner} first, so the partner's side of the link is cleared and Simurail
     * removes the joint; then the block goes, exactly as the reward wagon's dissolve does it.
     */
    private static void uncouple(ServerLevel level, ServerSubLevel car, CarKind kind) {
        uncoupleAllActors(car);
        for (CarPlans.Cell cell : CarPlans.of(kind).couplers()) {
            BlockPos pos = CarMotion.plotPos(car, cell);
            if (level.getBlockEntity(pos) instanceof AutomaticCouplerBlockEntity coupler) {
                coupler.removePartner();
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * Detaches every coupler actor currently in the plot, including player-added couplers on an
     * unlocked nest car and couplers on cars removed directly via {@link #removeNow}.
     */
    private static void uncoupleAllActors(ServerSubLevel car) {
        List<BlockEntitySubLevelActor> actors = new ArrayList<>();
        for (BlockEntitySubLevelActor actor : car.getPlot().getBlockEntityActors()) {
            actors.add(actor);
        }
        for (BlockEntitySubLevelActor actor : actors) {
            if (actor instanceof AutomaticCouplerBlockEntity coupler) {
                coupler.removePartner();
            }
        }
    }

    /** Turns the bogey off so the flying car is no longer held to the rails. */
    private static void disableBogey(ServerLevel level, ServerSubLevel car, CarKind kind) {
        BlockPos pos = CarMotion.plotPos(car, CarPlans.of(kind).bogey());
        if (level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey) {
            var options = bogey.getOptions();
            options.enabled = false;
            // setOptions copies the fields and sends the data packet the client renders from.
            bogey.setOptions(options);
        }
    }

    /** Replaces Y and Z with the 45° launch components and keeps X. */
    private static void launch(ServerSubLevel car, CarRegistry.Car record) {
        Vector3d velocity = CarMotion.velocity(car);
        double[] target = CarRules.launchVelocity(velocity.x, record.fallSide);
        if (!CarMotion.setVelocity(car, target[0], target[1], target[2])) {
            CarMotion.warnMissingHandle(car);
        }
    }

    /** Removes the sub-level, and the crystals that were riding it. */
    private static void remove(ServerLevel level, @Nullable CarRegistry.Car record, ServerSubLevel car) {
        if (record != null) {
            discardCrystals(level, record);
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (record != null) {
            record.removed = true;
            CarRegistry.get(level).setDirty();
        }
        if (container == null || car.isRemoved()) {
            return;
        }
        uncoupleAllActors(car);
        container.removeSubLevel(car, SubLevelRemovalReason.REMOVED);
    }

    /**
     * Drops the crystals without an explosion.
     *
     * <p>{@code discard} removes the entity without running {@code EndCrystal#kill}, so a car that
     * falls away does not detonate its own crystals on top of the explosion effect; a crystal a
     * player destroys still explodes as vanilla does (decision crystal-explosion).
     */
    private static void discardCrystals(ServerLevel level, CarRegistry.Car record) {
        List<CarRegistry.Crystal> crystals = List.copyOf(record.crystals);
        record.crystals.clear();
        for (CarRegistry.Crystal slot : crystals) {
            Entity entity = level.getEntity(slot.id());
            if (entity instanceof EndCrystal crystal && !crystal.isRemoved()) {
                crystal.discard();
            }
        }
    }

    @Nullable
    private static CarRegistry.Car record(ServerLevel level, ServerSubLevel car) {
        return CarRegistry.get(level).find(car.getUniqueId());
    }

    private static void warnUnregistered(String action, ServerSubLevel car) {
        DimBlendDragonChase.LOGGER.warn("Dragon Chase {} asked for unregistered sub-level {}; removing it directly",
                action, car.getUniqueId());
    }
}
