package dimblend.worldgen;

public final class MeshPressure {
    public enum Signal { NO_SIGNAL, OK, CONGESTED }

    public static final long STALE_MS = 250;

    /** Immutable sample; published as a single volatile reference so readers see consistent field sets. */
    private record Sample(int toBatch, int toUpload, int freeBuffers, boolean framesHealthy, long sampledAtMs) {}

    private static final Sample NEVER = new Sample(-1, -1, -1, false, 0L);
    private static volatile Sample sample = NEVER;

    private MeshPressure() {}

    public static void update(int queued, int uploads, int free, boolean framesHealthy) {
        sample = new Sample(queued, uploads, free, framesHealthy, System.nanoTime() / 1_000_000);
    }

    public static Signal current() {
        Sample s = sample;
        if (s.toBatch() < 0 || System.nanoTime() / 1_000_000 - s.sampledAtMs() > STALE_MS) {
            return Signal.NO_SIGNAL;   // dedicated server / unloaded world / frozen client
        }
        return classify(s.toBatch(), s.toUpload(), s.freeBuffers(), s.framesHealthy());
    }

    static Signal classify(int queued, int uploads, int free, boolean framesHealthy) {
        return !framesHealthy || queued > 0 || uploads > 0 || free <= 0 ? Signal.CONGESTED : Signal.OK;
    }

    public static int toBatch() { return Math.max(0, sample.toBatch()); }
    public static int freeBuffers() { return Math.max(0, sample.freeBuffers()); }
}
