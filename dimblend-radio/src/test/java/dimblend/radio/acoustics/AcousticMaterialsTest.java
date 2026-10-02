package dimblend.radio.acoustics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticMaterialsTest {
    private static final int WOOL = 0, LEAVES = 1, STONE = 4;

    @Test void bucketsMatchTheReflectivityTable() {
        for (int bucket = 0; bucket < AcousticMaterials.COUNT; bucket++) {
            assertEquals(bucket, AcousticMaterials.bucket(AcousticMaterials.reflectivity(bucket)));
        }
    }

    @Test void solidWallsFollowTheMassLawAndFoliageLosesLess() {
        assertArrayEquals(new float[] {0.35f, 0.20f, 0.08f}, AcousticMaterials.transmission(STONE, 1), 1e-6f);
        float[] one = AcousticMaterials.transmission(STONE, 1), two = AcousticMaterials.transmission(STONE, 2);
        for (int band = 0; band < 3; band++) assertEquals(one[band] / 2, two[band], 1e-6, "-6 dB per doubling");
        float[] leaves = AcousticMaterials.transmission(LEAVES, 4);
        assertEquals(AcousticMaterials.transmission(LEAVES, 1)[0] / 2, leaves[0], 1e-6, "foliage: -3 dB per doubling");
        assertTrue(AcousticMaterials.transmission(WOOL, 1)[0] > AcousticMaterials.transmission(STONE, 1)[0]);
    }

    @Test void thicknessIsClampedAndQuantized() {
        assertArrayEquals(AcousticMaterials.transmission(STONE, AcousticMaterials.MAX_THICKNESS),
                AcousticMaterials.transmission(STONE, 1000), 1e-9f);
        for (float value : AcousticMaterials.transmission(WOOL, 0.01)) assertTrue(value <= 0.95f, "never more than open air");
        assertEquals(AcousticMaterials.thicknessStep(AcousticMaterials.MIN_THICKNESS), AcousticMaterials.thicknessStep(0));
        assertEquals(3, AcousticMaterials.thickness(AcousticMaterials.thicknessStep(3.01)), 1e-9);
    }
}
