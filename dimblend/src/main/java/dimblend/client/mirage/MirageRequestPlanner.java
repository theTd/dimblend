package dimblend.client.mirage;

import dimblend.mirage.MirageCapGeometry;
import java.util.Arrays;

/**
 * Pure request planning for the mirage tableau: which seams are close enough to ask about, and
 * whether a side's cap is already complete.
 *
 * <p><b>Why the range filter is not optional.</b> {@code floorDiv} on the request window returns
 * every band boundary whose chunk interval touches {@code cameraX ± radius}, but a boundary can be
 * hundreds of blocks outside that window: at {@code cameraX = 4078}, {@code bandSize = 2048} the
 * lower edge {@code floorDiv(3966, 2048) = 1} yields {@code slabX = 2048}, 2030 blocks away. That
 * seam would be discovered, requested, evicted as far, and rebuilt on the next tick — twenty
 * wasted packets per second. {@link #candidateSlabs} therefore keeps only seams within
 * {@code radius} of the camera, symmetric for negative coordinates.</p>
 *
 * <p><b>Why completeness matters.</b> The resend timer exists for chunks the server did not
 * answer; once every chunk of the elliptical cap has arrived, another request only makes the
 * server replay unchanged payloads (which the client counts as {@code dropped}).</p>
 */
public final class MirageRequestPlanner {
    /**
     * How long a stale retry stays suppressed after the last chunk arrived, in client ticks (3 s
     * at 20 tps). Below this the server is mid-stream: mini-batches are still coming, so a retry
     * would only ask it to restart a queue that is already making progress.
     */
    public static final int STREAM_ACTIVE_TICKS = 60;

    private MirageRequestPlanner() {
    }

    /** Chunk presence lookup, so the completeness check stays testable without a live client. */
    @FunctionalInterface
    public interface Presence {
        boolean has(int chunkX, int chunkZ);
    }

    /**
     * Seam X coordinates ({@code k * bandSize}) whose block lies within {@code radius} of the
     * camera, in ascending order.
     */
    public static int[] candidateSlabs(double cameraX, int bandSize, int radius) {
        if (bandSize <= 0 || radius < 0) {
            return new int[0];
        }
        int camera = (int) Math.floor(cameraX);
        int first = Math.floorDiv(camera - radius, bandSize);
        int last = Math.floorDiv(camera + radius, bandSize);
        int[] slabs = new int[last - first + 1];
        int count = 0;
        for (int k = first; k <= last; k++) {
            int slabX = k * bandSize;
            if (Math.abs(slabX - cameraX) > radius) {
                continue;
            }
            slabs[count++] = slabX;
        }
        return Arrays.copyOf(slabs, count);
    }

    /**
     * True when a complete cap makes the pending resend pointless: the cap is whole, the centre
     * did not move, and the shape did not grow. A stale timer alone is not enough.
     */
    public static boolean suppressesResend(boolean sent, boolean moved, boolean reshaped, boolean complete) {
        return sent && !moved && !reshaped && complete;
    }

    /** True while chunk payloads are still arriving ({@code 0} = nothing received yet). */
    public static boolean streamActive(long lastPayloadTick, long nowTick) {
        return lastPayloadTick != 0L && nowTick - lastPayloadTick < STREAM_ACTIVE_TICKS;
    }

    /**
     * True when a stale retry must be swallowed because the server is actively streaming.
     *
     * <p>The live capture is the reason this exists: the client re-requested every
     * {@code RESEND_TICKS} while a 218-chunk cap was still being generated in mini-batches, and
     * every one of those requests made the old server throw away its whole queue. Only a stale
     * timer may be suppressed — a moved centre, a grown shape or the first request always go out,
     * and a silent server (nothing for {@link #STREAM_ACTIVE_TICKS} ticks) is retried as before.</p>
     */
    public static boolean suppressesStale(
            boolean sent, boolean moved, boolean reshaped, boolean stale, boolean streamActive) {
        return sent && !moved && !reshaped && stale && streamActive;
    }

    /** First chunk X of the cap's bounding box, inclusive (shared with the server). */
    public static int fromChunkX(int slabX, byte side, int depth) {
        return MirageCapGeometry.firstChunkX(slabX, side, depth);
    }

    /** Last chunk X of the cap's bounding box, inclusive (shared with the server). */
    public static int toChunkX(int slabX, byte side, int depth) {
        return MirageCapGeometry.lastChunkX(slabX, side, depth);
    }

    /** Blocks of mirage rectangle beyond the seam plane, for one loaded depth in chunks. */
    public static float depthBlocks(int depth) {
        return depth << 4;
    }

    /**
     * True when every chunk of the requested elliptical cap is present. The chunk set is decided
     * by {@link MirageCapGeometry#inCap(int, byte, int, int, int, int, int)} — the exact predicate
     * the server uses to decide what to generate — so a trimmed corner that was never requested is
     * not mistaken for a missing chunk.
     */
    public static boolean sideComplete(
            int slabX, byte side, int centerChunkZ, int depth, int halfWidth, Presence presence) {
        if (depth <= 0 || halfWidth < 0) {
            return false;
        }
        for (int chunkX = fromChunkX(slabX, side, depth); chunkX <= toChunkX(slabX, side, depth); chunkX++) {
            for (int chunkZ = centerChunkZ - halfWidth; chunkZ <= centerChunkZ + halfWidth; chunkZ++) {
                if (!MirageCapGeometry.inCap(
                        slabX, side, centerChunkZ, depth, halfWidth, chunkX, chunkZ)) {
                    continue;
                }
                if (!presence.has(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }
}
