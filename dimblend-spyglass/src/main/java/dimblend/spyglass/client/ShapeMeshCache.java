package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureKey;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Least-recently-used cache of built structure meshes, one per structure key, bounded by the total
 * number of vertices (GPU memory) and by the number of entries.
 *
 * <p>Generic over the mesh type so the bookkeeping is unit tested without GL: the real entries are
 * {@link UploadedShapeMesh}es whose {@link Mesh#close()} deletes their vertex buffers. Every mesh
 * that leaves the cache is closed exactly once: replaced by a newer revision, evicted, rejected, or
 * dropped by {@link #clear()}.
 *
 * <p>Recency is a frame stamp, not insertion order: {@link #use} stamps the entry with the current
 * frame and eviction removes the entry with the oldest stamp. Entries used in the current or the
 * previous frame are pinned — evicting a mesh that is on screen would only rebuild it next frame —
 * so a new mesh that does not fit next to the pinned ones is rejected instead
 * ({@link PutResult#OVER_BUDGET}).
 *
 * <p>Not thread-safe; the client keeps it on the render thread.
 */
final class ShapeMeshCache<M extends ShapeMeshCache.Mesh> {
    /** What the cache needs to know about a mesh. */
    interface Mesh {
        /** Shape revision the mesh was built from; a newer revision replaces an older one. */
        int revision();

        long vertexCount();

        /** Frees the mesh's resources; called once when the mesh leaves the cache. */
        void close();
    }

    enum PutResult {
        /** Stored; an older revision of the same key, if any, was closed. */
        STORED,
        /** The cache already holds this or a newer revision; the offered mesh was closed. */
        STALE,
        /** The mesh alone exceeds the per-mesh vertex limit; it was closed, the old entry kept. */
        TOO_LARGE,
        /** No room next to the pinned (recently drawn) meshes; the offered mesh was closed. */
        OVER_BUDGET
    }

    private static final class Entry<M> {
        private final M mesh;
        private final long vertices;
        private long lastUsedFrame;

        private Entry(M mesh, long vertices, long lastUsedFrame) {
            this.mesh = mesh;
            this.vertices = vertices;
            this.lastUsedFrame = lastUsedFrame;
        }
    }

    private final long maxTotalVertices;
    private final long maxMeshVertices;
    private final int maxEntries;
    private final Map<StructureKey, Entry<M>> entries = new HashMap<>();
    private long totalVertices;

    ShapeMeshCache(long maxTotalVertices, long maxMeshVertices, int maxEntries) {
        if (maxTotalVertices <= 0L || maxMeshVertices <= 0L || maxMeshVertices > maxTotalVertices || maxEntries <= 0) {
            throw new IllegalArgumentException("Invalid mesh cache limits");
        }
        this.maxTotalVertices = maxTotalVertices;
        this.maxMeshVertices = maxMeshVertices;
        this.maxEntries = maxEntries;
    }

    /** The cached mesh of this key (any revision) without touching its recency. */
    @Nullable M peek(StructureKey key) {
        Entry<M> entry = this.entries.get(key);
        return entry == null ? null : entry.mesh;
    }

    /** The cached mesh of this key (any revision), stamped as used in {@code frame}. */
    @Nullable M use(StructureKey key, long frame) {
        Entry<M> entry = this.entries.get(key);
        if (entry == null) {
            return null;
        }
        entry.lastUsedFrame = Math.max(entry.lastUsedFrame, frame);
        return entry.mesh;
    }

    /**
     * Offers a freshly built mesh. On anything but {@link PutResult#STORED} the offered mesh is
     * closed here, so the caller never has to.
     */
    PutResult put(StructureKey key, M mesh, long frame) {
        Entry<M> existing = this.entries.get(key);
        if (existing != null && existing.mesh.revision() >= mesh.revision()) {
            mesh.close();
            return PutResult.STALE;
        }
        long vertices = Math.max(0L, mesh.vertexCount());
        if (vertices > this.maxMeshVertices) {
            // The older revision, if any, still shows the structure; keep it.
            mesh.close();
            return PutResult.TOO_LARGE;
        }
        if (existing != null) {
            // Newer revision: the old buffers are stale whether or not the new mesh fits.
            remove(key, existing);
        }
        while (this.totalVertices + vertices > this.maxTotalVertices || this.entries.size() >= this.maxEntries) {
            if (!evictOldestUnpinned(frame)) {
                mesh.close();
                return PutResult.OVER_BUDGET;
            }
        }
        this.entries.put(key, new Entry<>(mesh, vertices, frame));
        this.totalVertices += vertices;
        return PutResult.STORED;
    }

    /** Closes and forgets every mesh. */
    void clear() {
        RuntimeException failure = null;
        for (Entry<M> entry : this.entries.values()) {
            try {
                entry.mesh.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                }
            }
        }
        this.entries.clear();
        this.totalVertices = 0L;
        if (failure != null) {
            throw failure;
        }
    }

    long totalVertices() {
        return this.totalVertices;
    }

    int size() {
        return this.entries.size();
    }

    private boolean evictOldestUnpinned(long frame) {
        StructureKey oldestKey = null;
        Entry<M> oldest = null;
        for (Map.Entry<StructureKey, Entry<M>> candidate : this.entries.entrySet()) {
            Entry<M> entry = candidate.getValue();
            if (isPinned(entry, frame)) {
                continue;
            }
            if (oldest == null || entry.lastUsedFrame < oldest.lastUsedFrame) {
                oldest = entry;
                oldestKey = candidate.getKey();
            }
        }
        if (oldest == null) {
            return false;
        }
        remove(oldestKey, oldest);
        return true;
    }

    private static boolean isPinned(Entry<?> entry, long frame) {
        return entry.lastUsedFrame >= frame - 1L;
    }

    private void remove(StructureKey key, Entry<M> entry) {
        this.entries.remove(key);
        this.totalVertices -= entry.vertices;
        entry.mesh.close();
    }
}
