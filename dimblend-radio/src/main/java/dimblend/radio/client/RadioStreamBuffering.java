package dimblend.radio.client;

import dimblend.radio.acoustics.SteamRenderer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Start short, add headroom when the device actually runs dry, and give it back once playback
 * proves stable: every queued buffer delays head-turn spatialization by one block.
 */
public final class RadioStreamBuffering {
    /**
     * Headroom earlier playbacks needed beyond the short start. A new playback starts with it,
     * instead of starving once more to relearn it, and each start lets one buffer of it go, so a
     * one-off hitch does not keep every later stream deep.
     */
    private static final AtomicInteger LEARNED = new AtomicInteger();
    /** Played audio (seconds) without the queue running low before one buffer is given back. */
    private static final double STABLE_SECONDS = 3;
    /** Queued buffers that must remain at the window's lowest point for a shrink to stay safe. */
    private static final int SHRINK_SLACK = 2;
    /** Each underrun of a playback doubles its stability window, up to this many times. */
    private static final int MAX_BACKOFF = 4;

    public static int bufferCount(float sampleRate) {
        return Math.max(2, (int) Math.ceil(sampleRate * 0.025 / SteamRenderer.FRAME));
    }

    /** Buffering for a new playback, starting from the depth earlier underruns taught. */
    public static State forPlayback(float rate) {
        return new State(rate, LEARNED.getAndUpdate(extra -> Math.max(0, extra - 1)), true);
    }

    static void forgetLearned() { LEARNED.set(0); }

    public static final class State {
        private final int base, maximum, stableBuffers;
        private final boolean shared;
        private int target, underruns;
        private int windowPlayed, windowLowest = Integer.MAX_VALUE;

        /** Buffering that neither starts from nor teaches learned headroom. */
        public State(float rate) {
            this(rate, 0, false);
        }

        private State(float rate, int extra, boolean shared) {
            base = bufferCount(rate);
            maximum = Math.max(base, (int) Math.ceil(rate * 0.090 / SteamRenderer.FRAME));
            stableBuffers = (int) Math.ceil(rate * STABLE_SECONDS / SteamRenderer.FRAME);
            target = Math.min(maximum, base + extra);
            this.shared = shared;
        }

        public int target() { return target; }

        /**
         * Buffers to queue on a refill. A window of played audio in which the queue never fell
         * below {@link #SHRINK_SLACK} buffers gives one buffer of headroom back (not below the
         * short start); a paused source plays nothing, so it never counts as stable.
         * @param played buffers the device finished since the previous refill
         * @param queued buffers still queued, including the one playing
         */
        public int refill(int played, int queued) {
            windowLowest = Math.min(windowLowest, queued);
            windowPlayed += played;
            if (windowPlayed >= stableBuffers << Math.min(underruns, MAX_BACKOFF)) {
                if (target > base && windowLowest >= SHRINK_SLACK) target--;
                windowPlayed = 0;
                windowLowest = Integer.MAX_VALUE;
            }
            return Math.max(0, target - queued);
        }

        /** The device ran dry: deepen this playback and wait longer before shrinking again. */
        public int underrun() {
            target = Math.min(maximum, target + 2);
            underruns++;
            windowPlayed = 0;
            windowLowest = Integer.MAX_VALUE;
            if (shared) LEARNED.accumulateAndGet(target - base, Math::max);
            return target;
        }
    }

    private RadioStreamBuffering() { }
}
