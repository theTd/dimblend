package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Edits of palettes in the current acoustic snapshot wake the next camera-frame capture. */
public final class AcousticSceneChanges {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final Map<Long, Long> OBSERVED = new ConcurrentHashMap<>();

    public static long beginCapture() {
        long generation = GENERATION.incrementAndGet();
        OBSERVED.clear();
        return generation;
    }

    public static void observe(long generation, long section) { OBSERVED.put(section, generation); }

    public static void paletteChanged(long generation, long section) {
        if (generation == 0 || generation != GENERATION.get()) return;
        SectionGeometryCache.invalidate(section);
        REVISION.incrementAndGet();
    }

    public static void geometryChanged(long section) {
        Long observed = OBSERVED.get(section);
        if (observed != null && observed == GENERATION.get()) REVISION.incrementAndGet();
    }

    public static long revision() { return REVISION.get(); }

    public static boolean needsCapture(boolean missing, long capturedRevision, long capturedAt, long now) {
        return missing || capturedRevision != revision() || now - capturedAt >= 500_000_000L;
    }

    public static void reset() { GENERATION.incrementAndGet(); OBSERVED.clear(); REVISION.incrementAndGet(); }
    private AcousticSceneChanges() { }
}
