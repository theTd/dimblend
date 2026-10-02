package dimblend.radio.client;

import dimblend.radio.acoustics.SteamRenderer;
import java.util.concurrent.atomic.AtomicInteger;

/** Start short, then add headroom only when the device actually runs dry. */
public final class RadioStreamBuffering {
    /**
     * Headroom earlier playbacks needed beyond the short start. A new playback starts with it,
     * instead of starving once more to relearn it, and each start lets one buffer of it go, so a
     * one-off hitch does not keep every later stream deep.
     */
    private static final AtomicInteger LEARNED = new AtomicInteger();

    public static int bufferCount(float sampleRate) {
        return Math.max(2, (int) Math.ceil(sampleRate * 0.025 / SteamRenderer.FRAME));
    }

    /** Buffering for a new playback, starting from the depth earlier underruns taught. */
    public static State forPlayback(float rate) {
        return new State(rate, LEARNED.getAndUpdate(extra -> Math.max(0, extra - 1)), true);
    }

    static void forgetLearned() { LEARNED.set(0); }

    public static final class State {
        private final int base, maximum;
        private final boolean shared;
        private int target;

        /** Buffering that neither starts from nor teaches learned headroom. */
        public State(float rate) {
            this(rate, 0, false);
        }

        private State(float rate, int extra, boolean shared) {
            base = bufferCount(rate);
            maximum = Math.max(base, (int) Math.ceil(rate * 0.090 / SteamRenderer.FRAME));
            target = Math.min(maximum, base + extra);
            this.shared = shared;
        }

        public int target() { return target; }

        /** Keep learned headroom for this playback; shrinking it repeatedly would cause oscillation. */
        public int underrun() {
            target = Math.min(maximum, target + 2);
            if (shared) LEARNED.accumulateAndGet(target - base, Math::max);
            return target;
        }
    }

    private RadioStreamBuffering() { }
}
