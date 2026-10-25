package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.StructureKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ShapeMeshCacheTest {
    private static final StructureKey A = key("a");
    private static final StructureKey B = key("b");
    private static final StructureKey C = key("c");
    private static final StructureKey D = key("d");

    @Test
    void storesAndAccountsVertices() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh mesh = new FakeMesh(1, 40);

        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(A, mesh, 1));

        assertSame(mesh, cache.peek(A));
        assertEquals(40, cache.totalVertices());
        assertEquals(1, cache.size());
        assertFalse(mesh.closed);
    }

    @Test
    void evictsTheLeastRecentlyUsedMeshAndClosesIt() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh a = new FakeMesh(1, 40);
        FakeMesh b = new FakeMesh(1, 40);
        cache.put(A, a, 1);
        cache.put(B, b, 2);
        // A is used again later than B, so B is the least recently used one.
        cache.use(A, 5);

        FakeMesh c = new FakeMesh(1, 40);
        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(C, c, 10));

        assertTrue(b.closed);
        assertNull(cache.peek(B));
        assertSame(a, cache.peek(A));
        assertSame(c, cache.peek(C));
        assertEquals(80, cache.totalVertices());
    }

    @Test
    void evictsAsManyMeshesAsNeeded() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh a = new FakeMesh(1, 30);
        FakeMesh b = new FakeMesh(1, 30);
        FakeMesh c = new FakeMesh(1, 30);
        cache.put(A, a, 1);
        cache.put(B, b, 2);
        cache.put(C, c, 3);

        FakeMesh d = new FakeMesh(1, 90);
        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(D, d, 10));

        assertTrue(a.closed && b.closed && c.closed);
        assertEquals(1, cache.size());
        assertEquals(90, cache.totalVertices());
    }

    @Test
    void neverEvictsMeshesUsedInThisOrThePreviousFrame() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh a = new FakeMesh(1, 50);
        FakeMesh b = new FakeMesh(1, 40);
        cache.put(A, a, 1);
        cache.put(B, b, 1);
        cache.use(A, 9);
        cache.use(B, 10);

        FakeMesh c = new FakeMesh(1, 30);
        assertEquals(ShapeMeshCache.PutResult.OVER_BUDGET, cache.put(C, c, 10));

        assertTrue(c.closed);
        assertFalse(a.closed);
        assertFalse(b.closed);
        assertEquals(90, cache.totalVertices());
        assertNull(cache.peek(C));
    }

    @Test
    void evictsAnUnpinnedMeshButNotAPinnedOne() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh old = new FakeMesh(1, 50);
        FakeMesh onScreen = new FakeMesh(1, 40);
        cache.put(A, old, 1);
        cache.put(B, onScreen, 1);
        cache.use(B, 10);

        FakeMesh c = new FakeMesh(1, 30);
        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(C, c, 10));

        assertTrue(old.closed);
        assertFalse(onScreen.closed);
        assertEquals(70, cache.totalVertices());
    }

    @Test
    void aNewerRevisionReplacesAndClosesTheOldMesh() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh first = new FakeMesh(3, 60);
        cache.put(A, first, 1);
        cache.use(A, 2);

        FakeMesh second = new FakeMesh(4, 70);
        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(A, second, 2));

        // The replaced mesh is closed even though it was pinned, and its vertices are released, so
        // the larger new revision fits.
        assertTrue(first.closed);
        assertSame(second, cache.peek(A));
        assertEquals(70, cache.totalVertices());
        assertEquals(1, cache.size());
    }

    @Test
    void anOlderOrEqualRevisionIsStaleAndClosed() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh current = new FakeMesh(5, 10);
        cache.put(A, current, 1);

        FakeMesh same = new FakeMesh(5, 10);
        FakeMesh older = new FakeMesh(4, 10);
        assertEquals(ShapeMeshCache.PutResult.STALE, cache.put(A, same, 2));
        assertEquals(ShapeMeshCache.PutResult.STALE, cache.put(A, older, 2));

        assertTrue(same.closed);
        assertTrue(older.closed);
        assertFalse(current.closed);
        assertEquals(10, cache.totalVertices());
    }

    @Test
    void aTooLargeMeshIsClosedAndTheOlderRevisionKept() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 25, 10);
        FakeMesh current = new FakeMesh(1, 20);
        cache.put(A, current, 1);

        FakeMesh huge = new FakeMesh(2, 26);
        assertEquals(ShapeMeshCache.PutResult.TOO_LARGE, cache.put(A, huge, 2));

        assertTrue(huge.closed);
        assertSame(current, cache.peek(A));
        assertEquals(20, cache.totalVertices());
    }

    @Test
    void emptyMeshesAreBoundedByTheEntryCap() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 2);
        FakeMesh a = new FakeMesh(1, 0);
        FakeMesh b = new FakeMesh(1, 0);
        FakeMesh c = new FakeMesh(1, 0);
        cache.put(A, a, 1);
        cache.put(B, b, 2);

        assertEquals(ShapeMeshCache.PutResult.STORED, cache.put(C, c, 10));

        assertTrue(a.closed);
        assertEquals(2, cache.size());
        assertEquals(0, cache.totalVertices());
    }

    @Test
    void useStampsRecencyButPeekDoesNot() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh a = new FakeMesh(1, 50);
        FakeMesh b = new FakeMesh(1, 50);
        cache.put(A, a, 1);
        cache.put(B, b, 2);
        cache.peek(A);

        cache.put(C, new FakeMesh(1, 50), 10);

        assertTrue(a.closed, "peek must not protect A from eviction");
        assertFalse(b.closed);
        assertNull(cache.use(D, 10));
    }

    @Test
    void clearClosesEverything() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh a = new FakeMesh(1, 10);
        FakeMesh b = new FakeMesh(1, 20);
        cache.put(A, a, 1);
        cache.put(B, b, 1);

        cache.clear();

        assertTrue(a.closed && b.closed);
        assertEquals(0, cache.size());
        assertEquals(0, cache.totalVertices());
        assertEquals(1, a.closeCount);
    }

    @Test
    void everyMeshIsClosedExactlyOnce() {
        ShapeMeshCache<FakeMesh> cache = new ShapeMeshCache<>(100, 100, 10);
        FakeMesh first = new FakeMesh(1, 60);
        FakeMesh second = new FakeMesh(2, 60);
        cache.put(A, first, 1);
        cache.put(A, second, 5);
        cache.clear();

        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeMeshCache<FakeMesh>(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShapeMeshCache<FakeMesh>(10, 11, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShapeMeshCache<FakeMesh>(10, 10, 0));
    }

    private static StructureKey key(String path) {
        return new StructureKey(ResourceLocation.fromNamespaceAndPath("test", path), 0, 0);
    }

    private static final class FakeMesh implements ShapeMeshCache.Mesh {
        private final int revision;
        private final long vertices;
        private boolean closed;
        private int closeCount;

        private FakeMesh(int revision, long vertices) {
            this.revision = revision;
            this.vertices = vertices;
        }

        @Override
        public int revision() {
            return this.revision;
        }

        @Override
        public long vertexCount() {
            return this.vertices;
        }

        @Override
        public void close() {
            this.closed = true;
            this.closeCount++;
        }
    }
}
