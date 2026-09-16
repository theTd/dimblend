package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VoidscapeYShiftContractTest {
    @Test
    void rotatingJsonWrapsVoidscapeWithDownwardOffset() throws Exception {
        Path rotating = Path.of("src/main/resources/data/dimblend/dimension/rotating.json");
        String json = Files.readString(rotating, StandardCharsets.UTF_8);
        int wrapper = json.indexOf("\"type\": \"dimblend:y_shifted\"");
        int offset = json.indexOf("\"y_offset\": -64", wrapper);
        int inner = json.indexOf("\"type\": \"voidscape:void\"", offset);
        assertTrue(wrapper >= 0, "missing dimblend:y_shifted wrapper");
        assertTrue(offset > wrapper, "y_shifted wrapper must set y_offset -64");
        assertTrue(inner > offset, "y_shifted inner must be voidscape:void");
        assertTrue(
                json.indexOf("\"type\": \"dimblend:y_shifted_noise\"", inner) > inner,
                "twilight y_shifted_noise must remain after voidscape");
    }

    /**
     * SpireFeature measures its antispire columns against the absolute Y=0 floor of
     * Voidscape's own dimension; shifted down by 64 the whole antispire layer lives at
     * Y&lt;0 and the feature generates nothing in the rotating dimension. The mixin must
     * patch both the target and the exact expression (the compiled guard is
     * {@code getY()} + {@code ifgt}, so a constant-based patch would silently miss), and
     * it must be registered in the common array or dedicated servers lose the fix.
     */
    @Test
    void voidscapeSpireColumnsMeasureAgainstTheLevelFloor() throws Exception {
        String mixin = Files.readString(
                Path.of("src/main/java/dimblend/mixin/voidscape/SpireFeatureMixin.java"), StandardCharsets.UTF_8);
        assertTrue(
                mixin.contains("@Mixin(targets = \"tamaized.voidscape.features.SpireFeature\", remap = false)"),
                "mixin must target Voidscape's SpireFeature by name (the jar is runtime-only)");
        assertTrue(mixin.contains("method = \"checkForRoom\""), "mixin must patch SpireFeature.checkForRoom");
        assertTrue(
                mixin.contains("target = \"Lnet/minecraft/core/BlockPos;getY()I\""),
                "mixin must rewrite the column Y expression itself");
        assertTrue(
                mixin.contains("y - level.getMinBuildHeight()"),
                "antispire floor must be measured against the level floor, not an absolute Y");

        String mixins = Files.readString(
                Path.of("src/main/resources/dimblend.mixins.json"), StandardCharsets.UTF_8);
        int common = mixins.indexOf("\"mixins\"");
        int client = mixins.indexOf("\"client\"");
        int registration = mixins.indexOf("\"voidscape.SpireFeatureMixin\"");
        assertTrue(common >= 0 && client > common, "mixins.json must keep separate common/client arrays");
        assertTrue(registration > common, "SpireFeatureMixin must be registered");
        assertTrue(registration < client, "SpireFeatureMixin must be in the common array, not client");
    }

    /**
     * The antispire scan's sibling bound in {@code place} is {@code getMinBuildHeight() + 32}
     * (Voidscape's own {@code layerBottomDownwardsStart}, shifted) while the patched floor
     * guard is the level's own minimum build height. Those two windows only line up while the
     * applied {@code y_offset} equals {@code min_y}; if they ever diverge, antispires lose
     * their anchor again.
     */
    @Test
    void voidscapeShiftMatchesTheRotatingDimensionsFloor() throws Exception {
        String dimension = Files.readString(
                Path.of("src/main/resources/data/dimblend/dimension/rotating.json"), StandardCharsets.UTF_8);
        String type = Files.readString(
                Path.of("src/main/resources/data/dimblend/dimension_type/rotating.json"), StandardCharsets.UTF_8);
        int offset = readInt(dimension, "\"y_offset\": ", dimension.indexOf("\"type\": \"dimblend:y_shifted\""));
        int minY = readInt(type, "\"min_y\": ", 0);
        assertTrue(offset < 0, "voidscape y_shifted offset must stay negative");
        assertTrue(
                offset == minY,
                "rotating min_y (" + minY + ") must equal the voidscape y_offset (" + offset
                        + "), otherwise the antispire window and the world floor diverge");
    }

    private static int readInt(String json, String key, int from) {
        int at = json.indexOf(key, from);
        assertTrue(at >= 0, "missing " + key);
        int start = at + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Integer.parseInt(json.substring(start, end));
    }
}
