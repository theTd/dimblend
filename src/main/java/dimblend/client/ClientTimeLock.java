package dimblend.client;

import dimblend.time.TimeLockTarget;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side holder for the time lock pushed by {@link dimblend.time.TimeLockSync}.
 * While active, ClientLevelMixin forces the local day time every tick, which
 * drives sky rendering and sky-light darkness for the rotating dimension.
 */
public final class ClientTimeLock {
    private static volatile TimeLockTarget target = TimeLockTarget.NONE;

    private ClientTimeLock() {
    }

    public static void apply(TimeLockTarget.Mode mode, long time) {
        target = new TimeLockTarget(mode, time);
    }

    public static boolean active() {
        return target.mode() != TimeLockTarget.Mode.NONE;
    }

    /** Day time to force for the current tick; jitter modes roll a fresh value per call. */
    public static long currentDayTime() {
        TimeLockTarget current = target;
        if (current.mode() == TimeLockTarget.Mode.TWILIGHT_JITTER) {
            long span = TimeLockTarget.TWILIGHT_MAX_EXCLUSIVE - TimeLockTarget.TWILIGHT_MIN;
            return TimeLockTarget.TWILIGHT_MIN + ThreadLocalRandom.current().nextLong(span);
        }
        return current.time();
    }
}
