package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Voidscape's own dimension is {@code has_skylight: false}, so its sky light is
 * always 0 with no sky data layer at all. The rotating dimension cannot drop the
 * sky engine (other lanes need it), so the voidscape lane reproduces that answer
 * through two vanilla light-engine mixins. These contracts guard the wiring: a
 * dropped registration or a retargeted injection would silently restore sky light
 * in the band instead of failing loudly.
 */
class VoidscapeSkyLightContractTest {
    private static final Path MIXINS_JSON = Path.of("src/main/resources/dimblend.mixins.json");
    private static final Path STORAGE_MIXIN = Path.of("src/main/java/dimblend/mixin/SkyLightSectionStorageMixin.java");
    private static final Path DATA_MIXIN = Path.of("src/main/java/dimblend/mixin/LayerLightSectionStorageMixin.java");
    private static final Path POLICY = Path.of("src/main/java/dimblend/compat/VoidscapeSkyLight.java");

    @Test
    void skyLightMixinsAreRegisteredOnBothSides() throws Exception {
        String json = Files.readString(MIXINS_JSON, StandardCharsets.UTF_8);
        int client = json.indexOf("\"client\"");
        assertTrue(client > 0, "client mixin array");
        int common = json.indexOf("\"mixins\"");
        for (String mixin : new String[]{"SkyLightSectionStorageMixin", "LayerLightSectionStorageMixin"}) {
            int at = json.indexOf("\"" + mixin + "\"");
            assertTrue(at > common, mixin + " must be registered");
            assertTrue(at < client, mixin + " must be a common mixin (the light engine is loaded on both sides)");
        }
    }

    @Test
    void skyReadsAreMaskedInTheSkyStorage() throws Exception {
        String source = Files.readString(STORAGE_MIXIN, StandardCharsets.UTF_8);
        assertTrue(source.contains("@Mixin(SkyLightSectionStorage.class)"), "sky storage target");
        assertTrue(source.contains("getLightValue(J)I"), "must hook the sky storage's own read entry point");
        assertTrue(source.contains("VoidscapeSkyLight.isMasked("), "must consult the lane policy");
        assertTrue(source.contains("setReturnValue(0)"), "masked reads must answer 0");
    }

    @Test
    void skyDataIsAnEmptyLayerForTheSkyLayerOnly() throws Exception {
        String source = Files.readString(DATA_MIXIN, StandardCharsets.UTF_8);
        assertTrue(source.contains("@Mixin(LayerLightSectionStorage.class)"), "layer storage target");
        assertTrue(
                source.contains("getDataLayerData(J)Lnet/minecraft/world/level/chunk/DataLayer;"),
                "must hook the save/packet data-layer read");
        assertTrue(source.contains("LightLayer.SKY"), "must be gated to the sky layer so block light is untouched");
        assertTrue(
                source.contains("setReturnValue(new DataLayer())"),
                "masked sections must answer with an all-zero layer: null means 'no data', and readers that "
                        + "bypass getLightValue (Sodium's cloned sections) fill a null sky layer with 15");
        assertFalse(source.contains("setReturnValue(null)"), "a null sky layer is read as sky 15 by Sodium");
    }

    @Test
    void policyIsScopedToTheRotatingVoidscapeLane() throws Exception {
        String source = Files.readString(POLICY, StandardCharsets.UTF_8);
        assertTrue(source.contains("VoidscapeBand.isRotating("), "other dimensions must be untouched");
        assertTrue(source.contains("VoidscapeBand.isVoidscapeColumn("), "server side resolves the band layout by X");
        assertTrue(
                source.contains("VoidscapeBand.isVoidscapeBiome("),
                "client side falls back to the voidscape biome namespace");
        assertFalse(source.contains("isClientSide"), "the policy must stay shared between both sides");
    }
}
