package dimblend.weather;

import net.minecraft.server.level.ServerLevel;

/**
 * Brutal global weather lock for the rotating dimension.
 *
 * <p>Rain and thunder are dimension-wide shared state, so instead of masking
 * reads per column / per player, the server weather itself is forced clear
 * whenever a player stands outside the surface band. Multi-player conflicts
 * (one player on surface wanting rain while another underground clears it)
 * are intentionally ignored.
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
     * current weather so no packets are sent while already clear; must be
     * called every tick because the vanilla cycle can restart rain while the
     * player stays underground.
     */
    public static void enforce(ServerLevel level, String laneName) {
        if (!shouldClear(laneName)) {
            return;
        }
        if (level.isRaining() || level.isThundering()) {
            level.setWeatherParameters(6000, 0, false, false);
        }
    }
}
