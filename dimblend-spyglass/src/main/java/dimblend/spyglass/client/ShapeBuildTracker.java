package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Which structure meshes are being built and which must not be requested again, so each
 * {@code (key, revision)} is built at most once and only a bounded number of builds run at a time.
 *
 * <p>A rejection is remembered per key with the revision it applies to: a newer revision of the
 * same structure is a different shape and may be built. Rejections either last until the next
 * {@link #clear()} ({@link #NEVER}) — a failed or oversized build would fail the same way again —
 * or until a frame (the cache was full of on-screen meshes; space may free up later).
 *
 * <p>Not thread-safe; render thread only. Pure bookkeeping, unit tested.
 */
final class ShapeBuildTracker {
    /** Retry frame of a rejection that holds until the next clear. */
    static final long NEVER = Long.MAX_VALUE;

    private record Rejection(int revision, long retryFrame) {
    }

    private final int maxInFlight;
    private final Map<StructureKey, Integer> inFlight = new HashMap<>();
    private final Map<StructureKey, Rejection> rejected = new HashMap<>();

    ShapeBuildTracker(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
    }

    boolean hasCapacity() {
        return this.inFlight.size() < this.maxInFlight;
    }

    /** Whether a build of this shape revision may start now. */
    boolean canStart(StructureKey key, int revision, long frame) {
        if (!hasCapacity() || this.inFlight.containsKey(key)) {
            return false;
        }
        Rejection rejection = this.rejected.get(key);
        return rejection == null || rejection.revision() != revision || frame >= rejection.retryFrame();
    }

    void started(StructureKey key, int revision) {
        if (this.inFlight.containsKey(key)) {
            throw new IllegalStateException("A build of " + key + " is already in flight");
        }
        this.inFlight.put(key, revision);
    }

    /** The build of this key has completed (successfully or not). */
    void finished(StructureKey key) {
        this.inFlight.remove(key);
    }

    /** Do not request this revision again before {@code retryFrame} ({@link #NEVER}: until clear). */
    void reject(StructureKey key, int revision, long retryFrame) {
        this.rejected.put(key, new Rejection(revision, retryFrame));
    }

    int inFlightCount() {
        return this.inFlight.size();
    }

    void clear() {
        this.inFlight.clear();
        this.rejected.clear();
    }
}
