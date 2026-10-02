package dimblend.radio.acoustics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dimblend.radio.acoustics.AcousticMaterials.*;

class AcousticMaterialsTest {
    @Test void everyMaterialIsPhysical() {
        for (int material = 0; material < COUNT; material++) {
            for (float value : absorption(material)) assertTrue(value > 0 && value < 1, "absorption of " + material);
            float[] one = transmission(material, 1);
            for (float value : one) assertTrue(value > 0 && value <= 0.95f, "transmission of " + material);
            assertTrue(one[0] >= one[1] && one[1] >= one[2], "walls pass lows best: " + material);
        }
    }

    @Test void theOriginalClassesKeepTheirValues() {
        // Indices 0-4 and their absorption are what the reverb was tuned with (reflectivity 0.15..0.9).
        assertArrayEquals(new float[] {0.04f, 0.06f, 0.12f}, absorption(STONE), 1e-6f);
        assertArrayEquals(new float[] {0.34f, 0.51f, 0.98f}, absorption(WOOL), 1e-6f);
        assertArrayEquals(new float[] {0.35f, 0.20f, 0.08f}, transmission(STONE, 1), 1e-6f);
    }

    @Test void solidWallsFollowTheMassLawAndFoliageLosesLess() {
        float[] one = transmission(STONE, 1), two = transmission(STONE, 2);
        for (int band = 0; band < 3; band++) assertEquals(one[band] / 2, two[band], 1e-6, "-6 dB per doubling");
        assertEquals(transmission(FOLIAGE, 1)[0] / 2, transmission(FOLIAGE, 4)[0], 1e-6, "foliage: -3 dB per doubling");
        assertTrue(transmission(WOOL, 1)[0] > transmission(STONE, 1)[0]);
        assertTrue(transmission(GLASS, 1)[1] > transmission(STONE, 1)[1], "windows leak");
        assertTrue(transmission(METAL, 1)[1] < transmission(STONE, 1)[1], "metal is the best barrier");
        assertTrue(absorption(SNOW)[2] > absorption(SOIL)[2], "snow soaks up highs");
        assertTrue(absorption(METAL)[2] < absorption(WOOD)[2]);
    }

    @Test void thicknessIsClamped() {
        assertArrayEquals(transmission(STONE, MAX_THICKNESS), transmission(STONE, 1000), 1e-9f);
        assertArrayEquals(transmission(STONE, MIN_THICKNESS), transmission(STONE, 0.01), 1e-9f);
        for (float value : transmission(WOOL, 0.01)) assertTrue(value <= 0.95f, "never more than open air");
    }

    @Test void aRunOfOneMaterialInLayersEqualsOneLayer() {
        Path layered = new Path();
        layered.add(STONE, 0.5);
        layered.add(STONE, 1.5);
        assertEquals(2, layered.length(), 1e-9);
        assertArrayEquals(transmission(STONE, 2), layered.transmission(), 1e-6f);
    }

    @Test void mixedLayersFallBetweenTheirMaterials() {
        Path lined = new Path();
        lined.add(WOOL, 1);
        lined.add(STONE, 1);
        float[] mixed = lined.transmission(), wool = transmission(WOOL, 2), stone = transmission(STONE, 2);
        for (int band = 0; band < 3; band++) {
            float low = Math.min(wool[band], stone[band]), high = Math.max(wool[band], stone[band]);
            assertTrue(mixed[band] >= low - 1e-6 && mixed[band] <= high + 1e-6, "between two blocks of each, band " + band);
            assertTrue(mixed[band] < transmission(STONE, 1)[band], "the lining adds to the wall, band " + band);
        }
        // Equal one-block highs (0.08): a wool-lined block of stone passes the highs of two stone blocks.
        assertEquals(stone[2], mixed[2], 1e-6);

        Path glazed = new Path();
        glazed.add(GLASS, 0.125);
        glazed.add(STONE, 1);
        assertTrue(glazed.transmission()[1] < transmission(STONE, 1)[1], "order and thin layers still count");
    }

    @Test void anEmptyPathIsOpen() {
        assertArrayEquals(new float[] {0.95f, 0.95f, 0.95f}, new Path().transmission(), 0);
    }
}
