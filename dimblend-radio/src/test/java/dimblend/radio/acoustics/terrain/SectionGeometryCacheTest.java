package dimblend.radio.acoustics.terrain;

import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionGeometryCacheTest {
    private static final AABB ONE_SECTION = new AABB(0, 0, 0, 15, 15, 15);

    @AfterEach
    void cleanup() {
        SectionGeometryCache.clear();
    }

    private static void put(long pos, int ox, int oy, int oz, int quads) {
        SectionGeometryCache.put(pos, ox, oy, oz, new float[quads * 12], new byte[quads],
                new byte[quads * 3]);
    }

    @Test
    void presentSectionsListsGeometryAndCoveredKeys() {
        assertTrue(SectionGeometryCache.presentSections(ONE_SECTION, -4, 20).sections().isEmpty());
        assertTrue(SectionGeometryCache.presentSections(ONE_SECTION, -4, 20).covered().isEmpty());
        long pos = SectionPos.asLong(0, 0, 0);
        put(pos, 0, 0, 0, 1);
        var coverage = SectionGeometryCache.presentSections(ONE_SECTION, -4, 20);
        assertEquals(1, coverage.sections().size());
        assertTrue(coverage.covered().contains(pos));
    }

    @Test
    void zeroQuadSectionsAreNotCovered() {
        // Built but acoustically empty (plants/fluids/block entities only): the voxel filler
        // must still get its chance at collision shapes.
        long pos = SectionPos.asLong(0, 0, 0);
        put(pos, 0, 0, 0, 0);
        var coverage = SectionGeometryCache.presentSections(ONE_SECTION, -4, 20);
        assertTrue(coverage.sections().isEmpty());
        assertTrue(coverage.covered().isEmpty());
    }

    @Test
    void sectionsOutsideBoundsOrWorldHeightAreIgnored() {
        put(SectionPos.asLong(100, 0, 100), 1600, 0, 1600, 1);
        assertTrue(SectionGeometryCache.presentSections(ONE_SECTION, -4, 20).sections().isEmpty());
        assertTrue(SectionGeometryCache.presentSections(new AABB(0, 320, 0, 15, 335, 15), -4, 20)
                .sections().isEmpty());
    }

    @Test
    void foldHashTracksRebuildAndEviction() {
        long pos = SectionPos.asLong(0, 0, 0);
        long empty = SectionGeometryCache.foldHash(ONE_SECTION, -4, 20);
        put(pos, 0, 0, 0, 1);
        long built = SectionGeometryCache.foldHash(ONE_SECTION, -4, 20);
        assertNotEquals(empty, built);
        put(pos, 0, 0, 0, 2);
        assertNotEquals(built, SectionGeometryCache.foldHash(ONE_SECTION, -4, 20));
        SectionGeometryCache.remove(pos);
        assertEquals(empty, SectionGeometryCache.foldHash(ONE_SECTION, -4, 20));
    }

    @Test
    void foldHashIgnoresOutOfBoundsSections() {
        long before = SectionGeometryCache.foldHash(ONE_SECTION, -4, 20);
        put(SectionPos.asLong(100, 0, 100), 1600, 0, 1600, 1);
        assertEquals(before, SectionGeometryCache.foldHash(ONE_SECTION, -4, 20));
    }
}
