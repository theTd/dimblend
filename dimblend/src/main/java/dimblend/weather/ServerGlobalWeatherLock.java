package dimblend.weather;

import dimblend.DimBlendRegistries;
import dimblend.band.BandLaneSync;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Brutal global weather lock for the rotating dimension.
 *
 * <p>Rain and thunder are dimension-wide shared state, so instead of masking
 * reads per column / per player, the server weather itself is forced clear
 * whenever a player stands outside the surface band. Multi-player conflicts
 * (one player on surface wanting rain while another underground clears it)
 * are intentionally ignored.
 *
 * <p>Driven by the rotating level's own tick ({@link #onLevelTick}), not by
 * player ticks: a rider sitting in a Sable vehicle sublevel has the sublevel
 * as {@code serverLevel}, so no player tick of theirs ever reaches the
 * rotating level, and spectators can skip ticks on unloaded chunks. An empty
 * rotating level counts as locked — with nobody around to want rain, rain
 * must never accumulate unobserved.
 *
 * <p>Cut rain does not resume when the player walks back to surface; the
 * vanilla cycle restarts from clear. This is accepted for simplicity.
 */
public final class ServerGlobalWeatherLock {
    private ServerGlobalWeatherLock() {
    }

    /** True when this lane must force the whole dimension clear; only surface keeps weather. */
    public static boolean shouldClear(String laneName) {
        return !"surface".equals(laneName);
    }

    /**
     * Force the dimension clear when the lane is locked. Guarded by the
     * authoritative level-data flags rather than the lerped render levels,
     * so rain is killed the same tick the vanilla cycle starts it and
     * nothing is written while already clear.
     */
    public static void enforce(ServerLevel level, String laneName) {
        if (!shouldClear(laneName)) {
            return;
        }
        forceClear(level);
    }

    /**
     * Level-tick sweep: clears whenever any player in the rotating level
     * stands outside the surface band, or the level is empty. Registered in
     * {@link dimblend.DimBlend} via {@code addListener}.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        enforceForLevel(level);
    }

    private static void enforceForLevel(ServerLevel level) {
        if (level.players().isEmpty()) {
            forceClear(level);
            return;
        }
        for (var player : level.players()) {
            enforce(level, BandLaneSync.laneAt(level, player.getBlockX()));
        }
    }

    private static void forceClear(ServerLevel level) {
        if (level.getLevelData().isRaining() || level.getLevelData().isThundering()) {
            level.setWeatherParameters(6000, 0, false, false);
        }
    }
}
