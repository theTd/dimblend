package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Y shift must sit <em>below</em> every {@code minecraft:interpolated} marker in the
 * wrapped noise settings. Vanilla {@code NoiseChunk} rewrites interpolated markers into
 * {@code NoiseInterpolator}s whose corner values are sampled at {@code cellStartBlockY}
 * and only evaluated for {@code FunctionContext == NoiseChunk}; a wrapper above the marker
 * (the pre-fix shape, {@code YShiftedDensity(whole tree)}) feeds the interpolator a shifted
 * delegate context instead, which silently falls back to {@code noiseFiller.compute} — the
 * trilinear corner interpolation is bypassed and the band is drawn from uninterpolated
 * noise, a visibly different surface from the source dimension (same-seed probe:
 * ~15-30% of cells differed while biomes stayed identical). These text contracts pin the
 * tree-walking shift and the tree-scan idempotency guards; a behavioral companion probe
 * lives in the generation-rules doc (same-seed band vs {@code voidscape:void} region diff).
 */
class YShiftedNoiseSettingsContractTest {
    @Test
    void shiftIsPushedBelowInterpolationMarkers() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/dimblend/worldgen/YShiftedNoiseSettings.java"), StandardCharsets.UTF_8);
        assertTrue(
                source.contains("static DensityFunction shifted(DensityFunction function, int offset)"),
                "the leaf-shifting rewrite must be the only way wrap() shifts densities");
        assertTrue(
                source.contains("containsInterpolated(node) || existingShiftOffset(node).isPresent()"),
                "nodes above an interpolation marker or below an existing shift must stay"
                        + " unwrapped so NoiseChunk can build NoiseInterpolators whose corners"
                        + " sample the shifted leaves, and the post-order walk must not re-wrap"
                        + " ancestors of a freshly wrapped leaf");
        assertTrue(
                source.contains("\"interpolated\".equals(markerTypeName(marker))"),
                "interpolated markers must be recognized by serial name (Marker.Type is a"
                        + " protected nested class, not referenceable from this package)");
        assertTrue(
                source.contains("shifted(router.initialDensityWithoutJaggedness(), yOffset)"),
                "the initial density must go through the same below-interpolation shift");
        assertTrue(
                source.contains("shifted(router.finalDensity(), yOffset)"),
                "the final density must go through the same below-interpolation shift");
        assertTrue(
                source.contains("node instanceof YShiftedDensity") && source.contains("return node;"),
                "the visitor must refuse to wrap an already-shifted wrapper");
    }

    /**
     * The leaf-wrapped form no longer has a top-level {@code YShiftedDensity}, so the
     * idempotency guards must scan the tree; a root {@code instanceof} check would let a
     * world-save round-trip apply the shift twice.
     */
    @Test
    void idempotencyGuardsScanTheWholeTree() throws Exception {
        String settings = Files.readString(
                Path.of("src/main/java/dimblend/worldgen/YShiftedNoiseSettings.java"), StandardCharsets.UTF_8);
        assertTrue(
                settings.contains("static OptionalInt existingShiftOffset(DensityFunction function)"),
                "a tree-scan helper must locate the shift offset");
        assertTrue(
                settings.contains("existingShiftOffset(settings.noiseRouter().finalDensity())"),
                "alreadyShifted must consult the tree scan, not a root instanceof");
        String generator = Files.readString(
                Path.of("src/main/java/dimblend/worldgen/YShiftedChunkGenerator.java"), StandardCharsets.UTF_8);
        assertTrue(
                generator.contains("YShiftedNoiseSettings.existingShiftOffset("),
                "applyShift must reject conflicting offsets via the tree scan");
        assertTrue(
                !generator.contains("finalDensity() instanceof YShiftedDensity"),
                "applyShift must not fall back to a root instanceof check");
    }
}