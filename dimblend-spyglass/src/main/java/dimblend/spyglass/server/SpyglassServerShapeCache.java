package dimblend.spyglass.server;

import dimblend.spyglass.structure.StructureKey;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-side LRU cache of encoded structure shapes, shared by all players.
 *
 * <p>Keyed by (dimension, structure key, revision): a re-saved chunk bumps the revision of every
 * structure covering it, so a stale entry is simply never hit again instead of being invalidated.
 * The cache is bounded by encoded body bytes, not entry count; a shape larger than the whole budget
 * is not cached at all. All access is synchronized: {@link StructureShapeExtractor} fills it on the
 * scan executor while the tick thread reads it for streaming.
 */
final class SpyglassServerShapeCache {
    static final long MAX_ENCODED_BYTES = 64L << 20;

    private final Map<Key, EncodedStructureShape> entries =
            new LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, EncodedStructureShape> eldest) {
                    return false;
                }
            };
    private long encodedBytes;

    synchronized @Nullable EncodedStructureShape get(Key key) {
        return this.entries.get(key);
    }

    synchronized void put(Key key, EncodedStructureShape shape) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(shape, "shape");
        if (shape.bodyBytes() > MAX_ENCODED_BYTES) {
            return;
        }
        EncodedStructureShape previous = this.entries.put(key, shape);
        if (previous != null) {
            this.encodedBytes -= previous.bodyBytes();
        }
        this.encodedBytes += shape.bodyBytes();
        Iterator<Map.Entry<Key, EncodedStructureShape>> eldest = this.entries.entrySet().iterator();
        while (this.encodedBytes > MAX_ENCODED_BYTES && eldest.hasNext()) {
            Map.Entry<Key, EncodedStructureShape> entry = eldest.next();
            if (entry.getValue() == shape) {
                continue;
            }
            eldest.remove();
            this.encodedBytes -= entry.getValue().bodyBytes();
        }
    }

    synchronized long encodedBytes() {
        return this.encodedBytes;
    }

    synchronized int size() {
        return this.entries.size();
    }

    synchronized void clear() {
        this.entries.clear();
        this.encodedBytes = 0L;
    }

    /** One shape identity; the revision is the region header max of its covering chunks. */
    record Key(ResourceLocation dimension, StructureKey key, int revision) {
        Key {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(key, "key");
            if (revision < 0) {
                throw new IllegalArgumentException("Negative shape revision: " + revision);
            }
        }
    }
}
