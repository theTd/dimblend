package dimblend.weather;

import dimblend.DimBlendRegistries;
import dimblend.band.BandLaneSync;
import dimblend.mixin.LevelWeatherAccessor;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.ServerLevelData;
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
 * <p>Enforced before the weather cycle and after the rotating level's tick
 * ({@link #onLevelTick}), not by
 * player ticks: a rider sitting in a Sable vehicle sublevel has the sublevel
 * as {@code serverLevel}, so no player tick of theirs ever reaches the
 * rotating level, and spectators can skip ticks on unloaded chunks. An empty
 * rotating level is only frozen, not cleared (2026-10-22 revision): the cycle
 * is skipped so timers never advance unobserved, but an ongoing rain keeps its
 * state and resumes when someone returns — respawning or relogging must not
 * stop the rain.
 *
 * <p>Cut rain does not resume when the player walks back to surface; the
 * vanilla cycle restarts from clear. This is accepted for simplicity, but the
 * restart must NOT go through {@code setWeatherParameters(clearTime, 0, ...)}:
 * vanilla pins rainTime/thunderTime to 1 for the whole clear countdown and
 * flips BOTH rain and thunder on the tick it ends, so every forced clear would
 * schedule a guaranteed thunderstorm a few minutes later. {@link #forceClear}
 * therefore arms fresh random delays in the vanilla {@code RAIN_DELAY} /
 * {@code THUNDER_DELAY} range instead.
 */
public final class ServerGlobalWeatherLock {
    private ServerGlobalWeatherLock() {
    }

    /** True when this lane must force the whole dimension clear; only surface keeps weather. */
    public static boolean shouldClear(String laneName) {
        return !"surface".equals(laneName);
    }

    /**
     * Force the dimension clear when the lane is locked, including any fading
     * rain/thunder intensity left over after the authoritative flags were cleared.
     */
    public static void enforce(ServerLevel level, String laneName) {
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL) || !shouldClear(laneName)) {
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

    /** Returns whether the weather cycle must be skipped for this level. */
    public static boolean enforceForLevel(ServerLevel level) {
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return false;
        }
        if (level.players().isEmpty()) {
            // 空 level 只冻结不清空（2026-10-22 修订）：天气循环继续被取消（计时器不走，
            // 雨不会无人累积），但不再销毁雨状态——重生/退存档再回来后雨照常继续。
            return true;
        }
        for (var player : level.players()) {
            if (shouldClear(BandLaneSync.laneAt(level, player.getBlockX()))) {
                forceClear(level);
                return true;
            }
        }
        return false;
    }

    private static void forceClear(ServerLevel level) {
        // getThunderLevel() multiplies by rain intensity, hiding leftover thunder when rain is zero.
        var intensity = (LevelWeatherAccessor) level;
        boolean changed = level.getLevelData().isRaining() || level.getLevelData().isThundering()
                || level.getRainLevel(0.0F) > 0.0F || level.getRainLevel(1.0F) > 0.0F
                || intensity.dimblend$oldThunderLevel() > 0.0F || intensity.dimblend$thunderLevel() > 0.0F;
        // clearTime must stay 0: any positive countdown ends with rain+thunder
        // toggling on together (see class javadoc). Fresh independent delays
        // mirror what vanilla arms after natural rain.
        var data = (ServerLevelData) level.getLevelData();
        data.setClearWeatherTime(0);
        data.setRainTime(freshWeatherDelay(level.getRandom()));
        data.setThunderTime(freshWeatherDelay(level.getRandom()));
        data.setRaining(false);
        data.setThundering(false);
        level.setRainLevel(0.0F);
        level.setThunderLevel(0.0F);
        if (changed) {
            var players = level.getServer().getPlayerList();
            // STOP_RAINING sets client intensity to 1, so the zero levels must follow it.
            players.broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F), level.dimension());
            players.broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0F), level.dimension());
            players.broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F), level.dimension());
        }
    }

    /** Delay range identical to vanilla {@code ServerLevel#RAIN_DELAY}/{@code THUNDER_DELAY}. */
    static int freshWeatherDelay(RandomSource random) {
        return 12000 + random.nextInt(168001);
    }
}
