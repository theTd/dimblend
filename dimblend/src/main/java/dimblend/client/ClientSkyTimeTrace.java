package dimblend.client;

import com.mojang.logging.LogUtils;
import dimblend.DimBlendRegistries;
import dimblend.api.SkyTimeTraceTuning;
import dimblend.time.TimeLockTarget;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

/** Opt-in evidence for intermittent sky/time jumps. Never changes the world clock or renderer. */
public final class ClientSkyTimeTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientLevel frameLevel;
    private static long daylightAdvance;
    private static long previousAdvance;
    private static long previousDayTime;
    private static long previousFrameNanos;
    private static long nextHeartbeat;
    private static SkyTimeTraceTuning.State observedState;

    private ClientSkyTimeTrace() { }

    public static boolean enabled() {
        return SkyTimeTraceTuning.enabled();
    }

    public static void naturalAdvance(ClientLevel level, long before, long after) {
        if (enabled() && Minecraft.getInstance().level == level) daylightAdvance += after - before;
    }

    static void onFrame(RenderFrameEvent.Pre event) {
        var current = SkyTimeTraceTuning.state();
        if (current != observedState) {
            boolean wasEnabled = observedState != null && observedState.enabled();
            observedState = current;
            frameLevel = null;
            if (current.enabled()) LOGGER.info("[sky-time] enabled revision={}", current.revision());
            else if (wasEnabled) LOGGER.info("[sky-time] disabled");
        }
        if (!current.enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            if (frameLevel != null) LOGGER.info("[sky-time] level-unloaded");
            frameLevel = null;
            return;
        }
        long now = System.nanoTime();
        long dayTime = level.getDayTime();
        if (frameLevel != level) {
            frameLevel = level;
            previousAdvance = daylightAdvance;
            previousDayTime = dayTime;
            previousFrameNanos = now;
            nextHeartbeat = now;
            LOGGER.info("[sky-time] level-loaded {}", context(level));
        }
        long advance = daylightAdvance - previousAdvance;
        if (SkyTimeJump.unexpected(previousDayTime, dayTime, advance)) {
            LOGGER.info("[sky-time] clock-step before={} after={} daylightAdvance={} frameGapMs={} {}",
                    previousDayTime, dayTime, advance, (now - previousFrameNanos) / 1_000_000L, context(level));
        }
        previousAdvance = daylightAdvance;
        previousDayTime = dayTime;
        previousFrameNanos = now;
        if (now >= nextHeartbeat) {
            nextHeartbeat = now + 5_000_000_000L;
            LOGGER.info("[sky-time] sample {}", context(level));
        }
    }

    public static void dayTimeWrite(ClientLevel level, long newDayTime) {
        if (!enabled() || pinned(level)) return;
        long before = level.getDayTime();
        if (!SkyTimeJump.unexpected(before, newDayTime, 0L)) return;
        String caller = Arrays.stream(new Throwable().getStackTrace()).skip(1).limit(8)
                .map(StackTraceElement::toString).collect(Collectors.joining(" <- "));
        LOGGER.info("[sky-time] day-time-write before={} requested={} caller={} {}",
                before, newDayTime, caller, context(level));
    }

    public static void timePacket(ClientLevel level, long packetTime, long before) {
        if (!enabled()) return;
        LOGGER.info("[sky-time] time-packet raw={} before={} after={} {}",
                packetTime, before, level.getDayTime(), context(level));
    }

    /** Payload construction/decoding may run off-thread; do not access Minecraft state here. */
    public static void neoForgePayload(long gameTime, long dayTime, boolean gameRule, float fraction, float perTick) {
        if (!enabled()) return;
        LOGGER.info("[sky-time] neoforge-time-payload game={} day={} daylight={} fraction={} perTick={}",
                gameTime, dayTime, gameRule, fraction, perTick);
    }

    static void timeLockChanged(TimeLockTarget previous, TimeLockTarget current) {
        if (!enabled()) return;
        ClientLevel level = Minecraft.getInstance().level;
        LOGGER.info("[sky-time] time-lock before={} after={} {}", previous, current,
                level == null ? "level=none" : context(level));
    }

    static void overlayChanged(RotatingSkyOverlay previous, RotatingSkyOverlay current,
            BlockPos camera, boolean loaded) {
        if (!enabled()) return;
        ClientLevel level = Minecraft.getInstance().level;
        LOGGER.info("[sky-time] sky-overlay before={} after={} camera={} loaded={} {}",
                previous, current, camera, loaded, level == null ? "level=none" : context(level));
    }

    private static String context(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos camera = mc.gameRenderer.getMainCamera().getBlockPosition();
        return "dimension=" + level.dimension().location() + " day=" + level.getDayTime()
                + " game=" + level.getGameTime() + " lock=" + ClientTimeLock.target() + " lockApplies=" + pinned(level)
                + " lane=" + ClientBandLane.lane() + " overlay="
                + (level.dimension() == DimBlendRegistries.ROTATING_LEVEL
                        ? RotatingDimensionEffects.lastOverlay() : RotatingSkyOverlay.NONE)
                + " player=" + (mc.player == null ? "none" : mc.player.position())
                + " camera=" + camera + " loaded=" + level.isLoaded(camera)
                + " paused=" + mc.isPaused() + " fps=" + mc.getFps()
                + " perTick=" + level.getDayTimePerTick() + " fraction=" + level.getDayTimeFraction()
                + " rain=" + level.getRainLevel(1.0F) + " thunder=" + level.getThunderLevel(1.0F);
    }

    private static boolean pinned(ClientLevel level) {
        return level.dimension() == DimBlendRegistries.ROTATING_LEVEL && ClientTimeLock.active();
    }
}
