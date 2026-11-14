package dimblend.dragonchase.chase;

import dimblend.DimBlendRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * §4: while the chase is ACTIVE, natural and chunk-generation mob spawns inside 256 blocks of the
 * nest are denied.
 *
 * <p>NeoForge fires {@link FinalizeSpawnEvent} for every vanilla path that finalizes a mob,
 * including the two this rule covers: {@code MobSpawnType.NATURAL} and
 * {@code MobSpawnType.CHUNK_GENERATION} ({@code net/neoforged/neoforge/event/entity/living/
 * FinalizeSpawnEvent.java:27-40} in neoforge-21.1.249-sources; {@code MobSpawnType.java:5-6} for
 * the names). It is the same hook {@code MonsterDensitySpawnGuard} uses.
 *
 * <p>It is registered at {@code EventPriority.LOWEST} so a later listener cannot re-enable a spawn
 * after this decision, and both {@code setCanceled} (skip finalize side effects) and
 * {@code setSpawnCancelled} (block the entity from entering the level, including from
 * {@code WorldGenRegion#addEntity}) are set.
 *
 * <p><b>Why a volatile snapshot instead of the saved data.</b> Chunk-generation spawns arrive with
 * a {@link WorldGenRegion} and run on worldgen worker threads, where {@code SavedData} access is
 * not thread-safe. The director publishes the nest position once per tick; this handler only reads
 * those two doubles.
 */
final class ChaseSpawnRule {
    private static volatile boolean pauseActive;
    private static volatile double nestX;
    private static volatile double nestZ;

    /** Called by the director every tick: {@code true} only while the phase is ACTIVE and a nest exists. */
    static void publish(boolean active, double centerX, double centerZ) {
        pauseActive = active;
        nestX = centerX;
        nestZ = centerZ;
    }

    static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!pauseActive) {
            return;
        }
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) {
            return;
        }
        if (!ChaseMath.withinRadius(event.getX(), event.getZ(), nestX, nestZ, ChaseMath.SPAWN_PAUSE_RADIUS)) {
            return;
        }
        ServerLevel level = levelOf(event.getLevel());
        if (level == null || !level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        event.setCanceled(true);
        event.setSpawnCancelled(true);
    }

    /** Unwraps the worldgen region worldgen spawns arrive with; unknown accessors fail closed. */
    private static ServerLevel levelOf(ServerLevelAccessor accessor) {
        if (accessor instanceof ServerLevel server) {
            return server;
        }
        if (accessor instanceof WorldGenRegion region) {
            return region.getLevel();
        }
        return null;
    }

    private ChaseSpawnRule() {
    }
}
