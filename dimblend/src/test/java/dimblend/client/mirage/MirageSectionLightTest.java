package dimblend.client.mirage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The section light flood: the point is that a wall under an overhang gets a continuous
 * {@code 15 → 14 → 13 → 12} gradient instead of the heightmap step to black.
 */
class MirageSectionLightTest {
    @FunctionalInterface
    interface Cell {
        int at(int x, int y, int z);
    }

    private static MirageSectionLight.Sampler sampler(Cell cost, Cell skySeed, Cell emission) {
        return new MirageSectionLight.Sampler() {
            @Override
            public int lightBlock(int x, int y, int z) {
                return cost.at(x, y, z);
            }

            @Override
            public int skySeed(int x, int y, int z) {
                return skySeed.at(x, y, z);
            }

            @Override
            public int emission(int x, int y, int z) {
                return emission.at(x, y, z);
            }
        };
    }

    private static int sky(byte[] light, int x, int y, int z) {
        return MirageSectionLight.sky(light[MirageSectionLight.index(x, y, z)]);
    }

    private static int block(byte[] light, int x, int y, int z) {
        return MirageSectionLight.block(light[MirageSectionLight.index(x, y, z)]);
    }

    @Test
    void canyonLedgeUnderAnOverhangFadesInsteadOfGoingBlack() {
        // Floor at y < 4, a 3-step overhang at y = 10 for x >= 1, open shaft at x = 0.
        Cell cost = (x, y, z) -> y < 4 || (y == 10 && x >= 1) ? 15 : 0;
        Cell skySeed = (x, y, z) -> y >= (x == 0 ? 4 : 11) ? 15 : 0;
        byte[] light = MirageSectionLight.compute(0, 0, 0, sampler(cost, skySeed, (x, y, z) -> 0));

        assertEquals(15, sky(light, 0, 9, 0), "the open column is fully lit");
        assertEquals(14, sky(light, 1, 9, 0), "one block under the first step");
        assertEquals(13, sky(light, 2, 9, 0), "two blocks under");
        assertEquals(12, sky(light, 3, 9, 0), "three blocks under: dim, but never black");
        for (int x = 1; x <= 3; x++) {
            assertTrue(sky(light, x, 9, 0) > 0,
                    "x=" + x + " under the overhang must not collapse to 0 like the old heightmap did");
        }
        // The whole open shelf keeps a monotone gradient away from the shaft.
        for (int x = 1; x < 15; x++) {
            assertEquals(15 - x, sky(light, x, 9, 0), "x=" + x);
        }
        assertEquals(0, sky(light, 3, 10, 0), "opaque cells carry no sky light of their own");
    }

    @Test
    void openAirJustOutsideTheSectionFeedsTheBorderCell() {
        // No seed inside the section at all: everything comes from the open cell at x = -1.
        Cell skySeed = (x, y, z) -> x < 0 && y >= 9 ? 15 : 0;
        byte[] light = MirageSectionLight.compute(0, 0, 0, sampler((x, y, z) -> 0, skySeed, (x, y, z) -> 0));

        assertEquals(14, sky(light, 0, 9, 0), "the face relay drops one level");
        assertEquals(13, sky(light, 1, 9, 0));
        assertEquals(12, sky(light, 2, 9, 0));
        assertEquals(13, sky(light, 0, 8, 0), "the flood also turns the corner");
    }

    @Test
    void emissiveBlockDecaysWithDistanceAndDoesNotPassWalls() {
        Cell cost = (x, y, z) -> x == 9 ? 15 : 0;
        Cell emission = (x, y, z) -> x == 8 && y == 8 && z == 8 ? 14 : 0;
        byte[] light = MirageSectionLight.compute(0, 0, 0, sampler(cost, (x, y, z) -> 0, emission));

        assertEquals(14, block(light, 8, 8, 8), "the source keeps its own level");
        assertEquals(13, block(light, 7, 8, 8));
        assertEquals(12, block(light, 6, 8, 8));
        assertEquals(6, block(light, 0, 8, 8), "fourteen levels decay one per block");
        assertEquals(0, block(light, 9, 8, 8), "the wall itself is dark");
        assertEquals(0, block(light, 10, 8, 8), "no light leaks through an opaque plane");
        assertEquals(0, sky(light, 8, 8, 8), "no sky seed below the surface");
    }

    @Test
    void floodDoesNotTurnASmallSourceIntoFullBrightness() {
        Cell emission = (x, y, z) -> x == 0 && y == 0 && z == 0 ? 15 : 0;
        byte[] light = MirageSectionLight.compute(0, 0, 0, sampler((x, y, z) -> 0, (x, y, z) -> 0, emission));
        assertEquals(15, block(light, 0, 0, 0));
        assertEquals(14, block(light, 0, 0, 1));
        assertNotEquals(14, block(light, 3, 3, 3), "distance decay, not a flat blob");
    }

    @Test
    void anOpaqueEmitterLightsTheAirNextToIt() {
        // Glowstone is opaque: the flood never enters it, but it must start from it.
        Cell cost = (x, y, z) -> x == 4 && y == 4 && z == 4 ? 15 : 0;
        Cell emission = (x, y, z) -> x == 4 && y == 4 && z == 4 ? 14 : 0;
        byte[] light = MirageSectionLight.compute(0, 0, 0, sampler(cost, (x, y, z) -> 0, emission));
        assertEquals(14, block(light, 4, 4, 4), "the source keeps its own level");
        assertEquals(13, block(light, 3, 4, 4), "and lights the air it faces");
        assertEquals(13, block(light, 4, 5, 4));
        assertEquals(0, sky(light, 3, 4, 4), "no sky seed below the surface");
    }
}
