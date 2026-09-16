package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoidscapeMixinContractTest {
    private static final List<String> COMMON = List.of(
            "voidscape.BlockTransformerItemMixin",
            "voidscape.EtherealPlantGeneratorMixin",
            "voidscape.GerminatorBlockEntityMixin",
            "voidscape.InsanityMixin",
            "voidscape.LevelUtilMixin",
            "voidscape.VoidDimensionDeathHandlerMixin",
            "voidscape.VoidicCrystalOreGeneratorMixin"
    );

    @Test
    void mixinsJsonRegistersVoidscapeLaneMixins() throws Exception {
        Path json = Path.of("src/main/resources/dimblend.mixins.json");
        String text = Files.readString(json, StandardCharsets.UTF_8);
        for (String mixin : COMMON) {
            assertTrue(text.contains("\"" + mixin + "\""), mixin);
        }
        assertTrue(text.contains("\"voidscape.LevelUtilClientMixin\""), "voidscape.LevelUtilClientMixin");
        int client = text.indexOf("\"client\"");
        assertTrue(client > 0, "client mixin array");
        assertTrue(
                text.indexOf("\"voidscape.LevelUtilClientMixin\"", client) > client,
                "LevelUtilClientMixin must be in the client array");
        int mixins = text.indexOf("\"mixins\"");
        assertTrue(
                text.indexOf("\"voidscape.LevelUtilMixin\"", mixins) < client,
                "LevelUtilMixin must be in the common array");
        assertTrue(
                text.indexOf("\"voidscape.InsanityMixin\"", mixins) < client,
                "InsanityMixin must be in the common array");
    }

    /**
     * Voidscape's two teleport funnels must be closed for {@code dimblend:rotating}:
     * the portal ({@code LevelUtil.getDimensionForTeleport}, called by
     * {@code PortalBlock.entityInside} and {@code Insanity.tick}) and the bedrock
     * entry ({@code Insanity.canTeleport}, the countdown gate in front of
     * {@code LevelUtil.getVoidDimension}). Both targets are declared with their real
     * Voidscape 1.9.588 signatures — verified against the jar with
     * {@code javap}: {@code getDimensionForTeleport(Level) -> Optional} and
     * {@code canTeleport(Entity) -> boolean} — so this test pins the injection
     * points, not the mixin framework.
     */
    @Test
    void rotatingBansVoidscapeTeleports() throws Exception {
        String levelUtil = Files.readString(
                Path.of("src/main/java/dimblend/mixin/voidscape/LevelUtilMixin.java"), StandardCharsets.UTF_8);
        int portal = levelUtil.indexOf("method = \"getDimensionForTeleport\"");
        assertTrue(portal > 0, "the portal funnel must be patched at LevelUtil.getDimensionForTeleport");
        // Assert against the injector's own declaration line: the file holds a second
        // cancellable HEAD injector (isInVoidDimension), so a file-wide contains() would
        // stay green if the portal gate alone lost its cancellable flag.
        String portalInjector = injectorLine(levelUtil, portal);
        assertTrue(portalInjector.contains("@At(\"HEAD\")"), "the portal funnel must be cancelled at HEAD");
        assertTrue(
                portalInjector.contains("cancellable = true"),
                "the portal gate itself must cancel the injection; a non-cancellable callback throws on first hit");
        assertTrue(
                levelUtil.contains("setReturnValue(Optional.empty())"),
                "rotating must answer no portal destination, so no portal countdown is armed");
        assertTrue(
                levelUtil.contains("VoidscapeBand.isRotating(currentLevel)"),
                "the portal gate must be the rotating dimension itself");

        String insanity = Files.readString(
                Path.of("src/main/java/dimblend/mixin/voidscape/InsanityMixin.java"), StandardCharsets.UTF_8);
        int bedrock = insanity.indexOf("method = \"canTeleport\"");
        assertTrue(bedrock > 0, "the bedrock entry must be patched at Insanity.canTeleport");
        assertTrue(
                injectorLine(insanity, bedrock).contains("cancellable = true"),
                "the bedrock gate must cancel the injection; a non-cancellable callback throws on first hit");
        assertTrue(
                insanity.contains("setReturnValue(false)"),
                "rotating must refuse the bedrock teleport countdown");
        assertTrue(
                insanity.contains("VoidscapeBand.isRotating(parent.level())"),
                "the bedrock gate must be the rotating dimension itself");
    }

    /** Source line holding the injector declaration that starts at {@code at}. */
    private static String injectorLine(String source, int at) {
        int start = source.lastIndexOf('\n', at) + 1;
        int end = source.indexOf('\n', at);
        return source.substring(start, end < 0 ? source.length() : end);
    }
}
