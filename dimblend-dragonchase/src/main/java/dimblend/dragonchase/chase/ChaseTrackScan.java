package dimblend.dragonchase.chase;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.api.DepotCarQuery;
import dimblend.api.TrainBrakeService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/**
 * One scan per tick of the Sable sub-levels the chase cares about.
 *
 * <p>What counts as "the train" is exactly {@code ServerSubLevelContainer#collectForceLoadedSubLevels()}
 * — the same source {@code BandTrainLocator} uses for the white line — narrowed to the corridor
 * band and the positive side (requirements.md §2.1, §2.2).
 *
 * <p>Speeds are the rigid body's linear velocity in m/s, read the way
 * {@code WarpGateSableCrossing} reads them, and topped up by adding a velocity delta, never by
 * teleporting a body (§8.29 of the dimblend-dev skill; the 2026-11-04 gate accident).
 */
final class ChaseTrackScan {
    /** One force-loaded sub-level, measured once. */
    record Car(ServerSubLevel sub, UUID id, double centerX, double centerY, double centerZ, double frontX, double vx,
            AABB box) {
    }

    /** Every force-loaded sub-level in the level, as measured this tick. */
    static List<Car> forceLoaded(ServerLevel level) {
        ServerSubLevelContainer container = container(level);
        if (container == null) {
            return List.of();
        }
        List<Car> cars = new ArrayList<>();
        for (ServerSubLevel sub : container.collectForceLoadedSubLevels()) {
            if (sub.isRemoved()) {
                continue;
            }
            cars.add(of(sub));
        }
        return cars;
    }

    /**
     * The corridor subset: positive side, |z| <= 16 (requirements.md §2.1, §2.2), and not an idle
     * station-depot spare car parked on its siding (§6 Q1 — depot sidings sit at z = +-10, inside
     * the +-16 corridor band, and must never trigger prologue/nest/chase or count as a chaser).
     */
    static List<Car> corridor(ServerLevel level, List<Car> all) {
        List<Car> cars = new ArrayList<>();
        for (Car car : all) {
            if (ChaseMath.onCorridor(car.centerX(), car.centerZ()) && !isIdleDepotCar(level, car.id())) {
                cars.add(car);
            }
        }
        return cars;
    }

    static Car of(ServerSubLevel sub) {
        Vector3d center = sub.boundingBox().center(new Vector3d());
        AABB box = sub.boundingBox().toMojang();
        return new Car(sub, sub.getUniqueId(), center.x, center.y, center.z, box.maxX, speedX(sub), box);
    }

    /** The live sub-level with this id, or null when it has been removed or never loaded. */
    static ServerSubLevel byId(ServerLevel level, UUID id) {
        if (id == null) {
            return null;
        }
        ServerSubLevelContainer container = container(level);
        if (container == null) {
            return null;
        }
        SubLevel sub = container.getSubLevel(id);
        return sub instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
    }

    /** +X linear velocity in m/s (0 when the body has no physics handle yet). */
    static double speedX(ServerSubLevel sub) {
        RigidBodyHandle handle = RigidBodyHandle.of(sub);
        if (handle == null || !handle.isValid()) {
            return 0.0D;
        }
        return handle.getLinearVelocity(new Vector3d()).x;
    }

    /** Adds exactly {@code deltaVx} m/s along +X, leaving every other axis untouched. */
    static void topUp(ServerSubLevel sub, double deltaVx) {
        if (deltaVx <= 0.0D) {
            return;
        }
        RigidBodyHandle handle = RigidBodyHandle.of(sub);
        if (handle == null || !handle.isValid()) {
            return;
        }
        handle.addLinearAndAngularVelocity(new Vector3d(deltaVx, 0.0D, 0.0D), new Vector3d());
    }

    /** §6 item 1 (assumption Q1): an idle station-depot spare car is not pushed by the speed floor. */
    static boolean isIdleDepotCar(ServerLevel level, UUID id) {
        return DepotCarQuery.get().isIdleDepotCar(level, id);
    }

    /** §2.3: a consist held by the ender brake keeps whatever speed it has. */
    static boolean isBrakeLatched(ServerLevel level, UUID id) {
        return TrainBrakeService.get().isLatched(level, id);
    }

    private static ServerSubLevelContainer container(ServerLevel level) {
        return SubLevelContainer.getContainer(level);
    }

    private ChaseTrackScan() {
    }
}
