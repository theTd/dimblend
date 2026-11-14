package dimblend.dragonchase.chase;

import dimblend.api.TrainBrakeService;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

/**
 * §3.4: after a breath animation the track carries a lingering breath cloud about 40 blocks long.
 * It is a plain world AABB plus particles, not an entity: a Sable sub-level never calls block or
 * entity callbacks while it moves, so the only reliable crossing test is the sub-level's world box
 * against the zone each tick (the same reason {@code WarpGateSableCrossing} is a sweep).
 *
 * <p>Zones accumulate: every breath adds one, and each survives until its own car crosses it. The
 * first force-loaded sub-level whose box enters a zone latches the ender brake on its consist and
 * that zone disappears, whether or not the service is installed (the latch call returns false
 * without an implementation; the requirement's outcome is "the zone is consumed"). The nest is
 * skipped: it flies ahead of the train and must never eat its own dragon's cloud.
 *
 * <p>Particles are sent per player with {@code overrideLimiter = true}, so the cloud stays visible
 * out to 512 blocks instead of being culled at vanilla's 32-block particle range.
 */
final class ChaseBreathZones {
    /** Particles per tick per watching player: enough to read as a cloud, small enough to be free. */
    private static final int PARTICLES_PER_TICK = 6;
    /** Horizontal reach of the cloud's own particles (the client's far-particle limit is 512). */
    private static final double PARTICLE_RADIUS = 256.0D;

    static void tick(ServerLevel level, DragonChaseSavedData data, List<ChaseTrackScan.Car> all) {
        if (data.breathZones.isEmpty()) {
            return;
        }
        Iterator<DragonChaseSavedData.BreathZone> iterator = data.breathZones.iterator();
        while (iterator.hasNext()) {
            DragonChaseSavedData.BreathZone breath = iterator.next();
            AABB zone = new AABB(breath.minX(), ChaseMath.BREATH_MIN_Y, -ChaseMath.BREATH_HALF_WIDTH,
                    breath.maxX(), ChaseMath.BREATH_MAX_Y, ChaseMath.BREATH_HALF_WIDTH);
            emit(level, zone);
            for (ChaseTrackScan.Car car : all) {
                if (car.id().equals(data.nestId) || ChaseTrackScan.isIdleDepotCar(level, car.id())) {
                    continue;
                }
                if (!zone.intersects(car.box())) {
                    continue;
                }
                TrainBrakeService.get().latch(level, car.id());
                iterator.remove();
                data.setDirty();
                break;
            }
        }
    }

    /** Lays one more zone from the breath callback's segment. */
    static void place(DragonChaseSavedData data, double fromX, double toX) {
        data.breathZones.add(new DragonChaseSavedData.BreathZone(fromX, toX));
        data.setDirty();
    }

    private static void emit(ServerLevel level, AABB zone) {
        double centerX = (zone.minX + zone.maxX) * 0.5D;
        double centerZ = (zone.minZ + zone.maxZ) * 0.5D;
        double width = zone.maxX - zone.minX;
        double height = zone.maxY - zone.minY;
        double depth = zone.maxZ - zone.minZ;
        RandomSource random = level.random;
        for (ServerPlayer player : level.players()) {
            if (!ChaseMath.withinRadius(player.getX(), player.getZ(), centerX, centerZ, PARTICLE_RADIUS)) {
                continue;
            }
            for (int i = 0; i < PARTICLES_PER_TICK; i++) {
                double x = zone.minX + random.nextDouble() * width;
                double y = zone.minY + random.nextDouble() * height;
                double z = zone.minZ + random.nextDouble() * depth;
                level.sendParticles(player, ParticleTypes.DRAGON_BREATH, true, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    private ChaseBreathZones() {
    }
}
