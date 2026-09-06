package dimblend.worldgen;

public final class MeshPressure {
    public enum Signal { NO_SIGNAL, OK, CONGESTED }

    public static final long STALE_MS = 2500;

    /** Immutable sample; published as a single volatile reference so readers see consistent field sets. */
    private record Sample(int toBatch, int freeBuffers, long sampledAtMs) {}

    private static final Sample NEVER = new Sample(-1, -1, 0L);
    private static volatile Sample sample = NEVER;

    private MeshPressure() {}

    public static void update(int queued, int free) {
        sample = new Sample(queued, free, System.currentTimeMillis());
    }

    public static Signal current() {
        Sample s = sample;
        if (s.toBatch() < 0 || System.currentTimeMillis() - s.sampledAtMs() > STALE_MS) {
            return Signal.NO_SIGNAL;   // dedicated server / unloaded world / frozen client
        }
        return (s.freeBuffers() == 0 && s.toBatch() >= 8) ? Signal.CONGESTED : Signal.OK;
    }

    public static int toBatch() { return Math.max(0, sample.toBatch()); }
    public static int freeBuffers() { return Math.max(0, sample.freeBuffers()); }
}
