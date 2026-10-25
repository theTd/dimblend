package dimblend.spyglass.client;

import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShapeCodec;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * Reassembles the parts of one shape body, keeping only the newest revision per structure key.
 *
 * <p>Client main thread only, no game state: the pure part bookkeeping is unit tested without a
 * running client. Parts may arrive in any order and interleaved with other shapes; a part with an
 * older revision than the pending body is dropped, a newer one replaces the pending body (its parts
 * belong to the revision the client wants).
 *
 * <p>The number of in-flight bodies is bounded, so a server that never completes a body cannot grow
 * client memory without limit.
 */
final class ShapePartAssembler {
    /** Bodies being reassembled at the same time. Parts of one shape arrive contiguously over TCP. */
    static final int MAX_IN_FLIGHT = 64;
    /** Bytes buffered for incomplete bodies; a stalled body cannot grow client memory past this. */
    static final int MAX_IN_FLIGHT_BYTES = 8 * 1024 * 1024;

    private final Map<StructureKey, PendingBody> pending = new LinkedHashMap<>();
    private int inFlightBytes;

    /**
     * @return the completed body when this part finished its shape, otherwise {@code null}
     */
    @Nullable
    AssembledBody accept(SpyglassShapePayload part) {
        PendingBody body = this.pending.get(part.key());
        if (body == null) {
            body = new PendingBody(part.dimension(), part.revision(), part.partCount());
            this.pending.put(part.key(), body);
            evictOverBudget();
        } else if (body.revision != part.revision() || body.partCount != part.partCount()) {
            if (part.revision() < body.revision) {
                // Late part of a revision the client already replaced.
                return null;
            }
            this.inFlightBytes -= body.bytes;
            this.pending.remove(part.key());
            body = new PendingBody(part.dimension(), part.revision(), part.partCount());
            this.pending.put(part.key(), body);
            evictOverBudget();
        } else if (!body.dimension.equals(part.dimension())) {
            return null;
        }

        int stored = body.store(part.partIndex(), part.data());
        this.inFlightBytes += stored;
        if (!body.isComplete()) {
            evictOverBudget();
            return null;
        }
        this.pending.remove(part.key());
        this.inFlightBytes -= body.bytes;
        byte[] joined = body.join();
        if (joined.length > StructureShapeCodec.MAX_BODY_BYTES) {
            return null;
        }
        return new AssembledBody(part.dimension(), part.key(), part.revision(), joined);
    }

    void clear() {
        this.pending.clear();
        this.inFlightBytes = 0;
    }

    int inFlight() {
        return this.pending.size();
    }

    int inFlightBytes() {
        return this.inFlightBytes;
    }

    private void evictOverBudget() {
        while ((this.pending.size() > MAX_IN_FLIGHT || this.inFlightBytes > MAX_IN_FLIGHT_BYTES)
                && this.pending.size() > 1) {
            Iterator<Map.Entry<StructureKey, PendingBody>> eldest = this.pending.entrySet().iterator();
            Map.Entry<StructureKey, PendingBody> entry = eldest.next();
            eldest.remove();
            this.inFlightBytes -= entry.getValue().bytes;
        }
    }

    static final class PendingBody {
        private final ResourceLocation dimension;
        private final int revision;
        private final int partCount;
        private final byte[][] parts;
        private int received;
        private int bytes;

        private PendingBody(ResourceLocation dimension, int revision, int partCount) {
            this.dimension = dimension;
            this.revision = revision;
            this.partCount = partCount;
            this.parts = new byte[partCount][];
        }

        /** @return the number of bytes newly buffered (0 for a duplicate part) */
        private int store(int index, byte[] data) {
            if (index < 0 || index >= this.parts.length) {
                return 0;
            }
            byte[] copy = Arrays.copyOf(data, data.length);
            byte[] previous = this.parts[index];
            this.parts[index] = copy;
            if (previous == null) {
                this.received++;
                this.bytes += copy.length;
                return copy.length;
            }
            this.bytes += copy.length - previous.length;
            return copy.length - previous.length;
        }

        private boolean isComplete() {
            return this.received == this.partCount;
        }

        private byte[] join() {
            byte[] joined = new byte[this.bytes];
            int offset = 0;
            for (byte[] part : this.parts) {
                System.arraycopy(part, 0, joined, offset, part.length);
                offset += part.length;
            }
            return joined;
        }
    }

    /** One fully reassembled shape body with the identity it was streamed for. */
    record AssembledBody(ResourceLocation dimension, StructureKey key, int revision, byte[] body) {
    }
}
