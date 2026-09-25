package dimblend.client;

import dimblend.time.TimeLockTarget;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.Mth;

/**
 * Client-side holder for the time lock pushed by {@link dimblend.time.TimeLockSync}.
 * While active, ClientLevelMixin freezes the daylight cycle (as if the
 * doDaylightCycle gamerule were false) and ClientLevelDataMixin shadows
 * day-time reads, so the celestial sphere stays pinned no matter what writes
 * the real time into the level data (e.g. the server's periodic time sync
 * packets). The day-time field itself is not overwritten with the pin;
 * unlock writes the world clock carried on the NONE packet via
 * {@link ClientDayTimeWrite}.
 *
 * <p>Twilight dusk is a clamped random walk, not a per-tick full-range roll:
 * the latter visibly shook the sun. The walk shuffles within
 * {@link TimeLockTarget#TWILIGHT_MIN} to {@link TimeLockTarget#TWILIGHT_MAX_EXCLUSIVE}
 * (centered on Twilight Forest's {@code fixed_time: 13000}) at a slow shimmer rate.
 */
public final class ClientTimeLock {
    private static final double TWILIGHT_STEP = 0.6;

    private static volatile TimeLockTarget target = TimeLockTarget.NONE;
    private static double twilightWander = twilightMid();

    private ClientTimeLock() {
    }

    public static synchronized void apply(TimeLockTarget.Mode mode, long time) {
        if (mode == TimeLockTarget.Mode.TWILIGHT_JITTER && target.mode() != mode) {
            twilightWander = twilightMid();
        }
        target = new TimeLockTarget(mode, time);
    }

    public static boolean active() {
        return target.mode() != TimeLockTarget.Mode.NONE;
    }

    /** Advances the twilight wander once per client tick. */
    public static synchronized void advanceTick() {
        if (target.mode() == TimeLockTarget.Mode.TWILIGHT_JITTER) {
            double step = (ThreadLocalRandom.current().nextDouble() - 0.5) * TWILIGHT_STEP;
            twilightWander = Mth.clamp(
                    twilightWander + step,
                    TimeLockTarget.TWILIGHT_MIN,
                    TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - 1);
        }
    }

    /** Day time to force for the current tick. Pure read; safe to call many times per frame. */
    public static synchronized long currentDayTime() {
        TimeLockTarget current = target;
        if (current.mode() == TimeLockTarget.Mode.TWILIGHT_JITTER) {
            return Math.round(twilightWander);
        }
        return current.time();
    }

    private static double twilightMid() {
        return (TimeLockTarget.TWILIGHT_MIN + TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - 1) / 2.0;
    }
}
