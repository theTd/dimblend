package dimblend.client.mirage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.mirage.MirageChunkKey;
import org.junit.jupiter.api.Test;

/**
 * Biome routing for the mirage tableau. The bug this pins: a mirage chunk is band A's terrain in
 * band B's coordinates, so asking the real {@code ClientLevel} for a biome there returns band B's
 * cave/plains biome and the grass, foliage and water tints flip to the wrong palette.
 */
class MirageBiomeLookupTest {
    private static final int SEAM = 4096;
    private static final byte WEST = MirageChunkKey.WEST_BAND_INTO_EAST;
    private static final byte EAST = MirageChunkKey.EAST_BAND_INTO_WEST;
    /** Quarts: the seam plane sits exactly on quart column 1024. */
    private static final int SEAM_QUART = 1024;

    @Test
    void ownChunkReadsItsPalette() {
        assertEquals(MirageBiomeLookup.Route.LOCAL,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, 1026, 14, false));
        assertEquals(MirageBiomeLookup.Route.LOCAL,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, 1024, 14, true),
                "a sibling has no say about this chunk's own cells");
    }

    @Test
    void aLoadedSiblingOfTheSameSideWinsOverTheLevel() {
        assertEquals(MirageBiomeLookup.Route.SIBLING,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, 1030, 14, true));
        assertEquals(MirageBiomeLookup.Route.SIBLING,
                MirageBiomeLookup.route(WEST, SEAM, 257, 9, 1030, 14, true));
    }

    @Test
    void theRealLevelIsOnlyTrustedOnTheMirageBandsOwnSide() {
        // Band A is west of the seam: west of the plane the real chunks are A's continuation.
        assertEquals(MirageBiomeLookup.Route.REAL,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, SEAM_QUART - 1, 14, false));
        assertEquals(MirageBiomeLookup.Route.REAL,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, SEAM_QUART - 40, 14, false));
        // East of the seam in band B's coordinates: the level holds band B's biomes, so clamp.
        assertEquals(MirageBiomeLookup.Route.CLAMPED,
                MirageBiomeLookup.route(WEST, SEAM, 256, 3, SEAM_QUART + 6, 14, false));

        // Band B is east of the seam, so the mirror rule.
        assertEquals(MirageBiomeLookup.Route.REAL,
                MirageBiomeLookup.route(EAST, SEAM, 255, 3, SEAM_QUART, 14, false));
        assertEquals(MirageBiomeLookup.Route.CLAMPED,
                MirageBiomeLookup.route(EAST, SEAM, 255, 3, SEAM_QUART - 6, 14, false));
    }

    @Test
    void roundingNeverReachesPastTheSeam() {
        // The seam quart itself belongs to the east band: reading it west of the plane is a real
        // band-A quart, reading it east is band B.
        assertTrue(MirageBiomeLookup.isRealSide(WEST, SEAM, SEAM_QUART - 1));
        assertFalse(MirageBiomeLookup.isRealSide(WEST, SEAM, SEAM_QUART));
        assertTrue(MirageBiomeLookup.isRealSide(EAST, SEAM, SEAM_QUART));
        assertFalse(MirageBiomeLookup.isRealSide(EAST, SEAM, SEAM_QUART - 1));
        // Negative coordinates floor the same way vanilla QuartPos does.
        assertTrue(MirageBiomeLookup.isRealSide(WEST, 0, -1));
        assertFalse(MirageBiomeLookup.isRealSide(EAST, 0, -1));
        assertTrue(MirageBiomeLookup.isRealSide(EAST, 0, 0));
    }

    @Test
    void clampsStayInsideTheChunk() {
        assertEquals(1026, MirageBiomeLookup.clampQx(1026, 256));
        assertEquals(1027, MirageBiomeLookup.clampQx(1030, 256), "past the chunk clamps to its last");
        assertEquals(1024, MirageBiomeLookup.clampQx(1020, 256), "before the chunk clamps to its first");

        assertEquals(14, MirageBiomeLookup.clampQz(14, 3));
        assertEquals(15, MirageBiomeLookup.clampQz(19, 3));
        assertEquals(12, MirageBiomeLookup.clampQz(9, 3));

        assertEquals(-16, MirageBiomeLookup.clampQy(-20, -4, 24), "first quart of section -4");
        assertEquals(79, MirageBiomeLookup.clampQy(90, -4, 24), "last quart of section 19");
        assertEquals(0, MirageBiomeLookup.clampQy(0, -4, 24));
    }
}
