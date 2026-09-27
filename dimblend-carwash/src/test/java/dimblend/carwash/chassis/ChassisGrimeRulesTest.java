package dimblend.carwash.chassis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChassisGrimeRulesTest {

    @Test
    void dirtIsClampedTo0Through255() {
        assertEquals(0, ChassisGrimeRules.clampDirt(-16));
        assertEquals(255, ChassisGrimeRules.clampDirt(300));
        assertEquals(40, ChassisGrimeRules.clampDirt(40));
    }

    @Test
    void oneDirtLayerPerMultipleOf32() {
        assertEquals(0, ChassisGrimeRules.dirtLayers(31));
        assertEquals(1, ChassisGrimeRules.dirtLayers(32));
        assertEquals(4, ChassisGrimeRules.dirtLayers(128));
        assertEquals(7, ChassisGrimeRules.dirtLayers(224));
        assertEquals(7, ChassisGrimeRules.dirtLayers(255));
    }

    @Test
    void gravelOnlyForLayersReachedAbove128() {
        // 128 本身不大于 128：不贴碎石；160/192/224 各贴一层
        assertEquals(0, ChassisGrimeRules.gravelLayers(128));
        assertEquals(0, ChassisGrimeRules.gravelLayers(159));
        assertEquals(1, ChassisGrimeRules.gravelLayers(160));
        assertEquals(2, ChassisGrimeRules.gravelLayers(192));
        assertEquals(3, ChassisGrimeRules.gravelLayers(255));
    }

    @Test
    void soilingChanceNeedsMoreThan4MetresPerSecond() {
        assertEquals(0.0, ChassisGrimeRules.soilingChance(4.0));
        assertEquals(0.0, ChassisGrimeRules.soilingChance(Double.NaN));
        assertEquals(0.5, ChassisGrimeRules.soilingChance(6.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(12.0), 1e-9);
        assertEquals(1.0, ChassisGrimeRules.soilingChance(30.0), 1e-9);
        assertTrue(ChassisGrimeRules.soilingChance(4.01) > 0.33);
    }

    @Test
    void layerVariantsArePackedOneBytePerLayer() {
        long variants = 0L;
        variants = ChassisGrimeRules.withLayerVariant(variants, 1, 0xAB);
        variants = ChassisGrimeRules.withLayerVariant(variants, 7, 0x3C);
        assertEquals(0xAB, ChassisGrimeRules.layerVariant(variants, 1));
        assertEquals(0x3C, ChassisGrimeRules.layerVariant(variants, 7));
        assertEquals(0, ChassisGrimeRules.layerVariant(variants, 4));
        assertEquals(0x0B, ChassisGrimeRules.dirtVariant(0xAB));
        assertEquals(0x0A, ChassisGrimeRules.gravelVariant(0xAB));

        variants = ChassisGrimeRules.withLayerVariant(variants, 1, 0x01);
        assertEquals(0x01, ChassisGrimeRules.layerVariant(variants, 1));
        assertEquals(0x3C, ChassisGrimeRules.layerVariant(variants, 7));
    }

    @Test
    void visualIgnoresVariantsOfLayersNotReached() {
        long variants = ChassisGrimeRules.withLayerVariant(0L, 1, 0x11);
        variants = ChassisGrimeRules.withLayerVariant(variants, 2, 0x22);
        // 同为 1 层（32..63）时，第 2 层残留的变体不影响快照相等
        assertEquals(ChassisGrimeVisual.of(40, ChassisGrimeRules.withLayerVariant(0L, 1, 0x11)),
                ChassisGrimeVisual.of(40, variants));
        assertTrue(ChassisGrimeVisual.of(31, variants).isClean());
        assertEquals(ChassisGrimeVisual.CLEAN, ChassisGrimeVisual.of(0, variants));
    }

    @Test
    void gravelVariantComesFromTheLayerThatAddedIt() {
        long variants = ChassisGrimeRules.withLayerVariant(0L, 5, 0x70);
        ChassisGrimeVisual visual = ChassisGrimeVisual.of(160, variants);
        assertEquals(5, visual.dirtLayers());
        assertEquals(1, visual.gravelLayers());
        assertEquals(0x7, visual.gravelVariant(1));
        assertEquals(0x0, visual.dirtVariant(5));
    }
}
