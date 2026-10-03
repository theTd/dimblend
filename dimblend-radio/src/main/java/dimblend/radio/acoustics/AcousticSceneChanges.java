package dimblend.radio.acoustics;

import java.util.concurrent.atomic.AtomicLong;

/** Edits of palettes in the current acoustic snapshot wake the next camera-frame capture. */
public final class AcousticSceneChanges {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong REVISION = new AtomicLong();

    public static long beginCapture() {
        return GENERATION.incrementAndGet();
    }

    public static void paletteChanged(long generation) {
        if (generation == 0 || generation != GENERATION.get()) return;
        REVISION.incrementAndGet();
    }

    public static long revision() { return REVISION.get(); }

    public static boolean needsCapture(boolean missing, long capturedRevision, long capturedAt, long now) {
        return missing || capturedRevision != revision() || now - capturedAt >= 500_000_000L;
    }

    public static void reset() { GENERATION.incrementAndGet(); REVISION.incrementAndGet(); }
    private AcousticSceneChanges() { }
}
