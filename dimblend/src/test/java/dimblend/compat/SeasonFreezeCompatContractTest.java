package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.TestSourceTree;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Pins the two halves of "no winter freezing in the rotating dimension". Serene Seasons' own
 * snow/ice/melt decisions go through {@code SeasonHooks.getBiomeTemperature}. Cold Sweat, with
 * its default custom freezing behavior, cancels {@code Biome.shouldFreeze} at HEAD and freezes
 * or melts from its own {@code SereneSeasonsTempModifier}. Patching only the first half left
 * winter ice forming. Both configs are {@code required=false}, so a dropped registration fails
 * silently in game. This test catches it instead.
 */
class SeasonFreezeCompatContractTest {

    @Test
    void bothSeasonMixinConfigsAreRegisteredWithFml() throws Exception {
        String modsToml = read("templates/META-INF/neoforge.mods.toml");
        assertTrue(modsToml.contains("config=\"${mod_id}.sereneseasons.mixins.json\""),
                "Serene Seasons hook config must be declared in [[mixins]]");
        assertTrue(modsToml.contains("config=\"${mod_id}.coldsweat.mixins.json\""),
                "Cold Sweat season-modifier config must be declared in [[mixins]]; without it water"
                        + " freezing in rotating still follows the winter offset");
        assertTrue(modsToml.contains("modId=\"cold_sweat\""),
                "cold_sweat must be declared as an optional dependency (version window of the pinned target)");
    }

    @Test
    void coldSweatConfigListsTheModifierMixinAndStaysOptional() throws Exception {
        String json = read("resources/dimblend.coldsweat.mixins.json");
        assertTrue(json.contains("\"coldsweat.SereneSeasonsTempModifierMixin\""), "mixin must be listed");
        assertTrue(json.contains("\"required\": false"), "the game must still boot without Cold Sweat");
        assertTrue(json.contains("\"defaultRequire\": 1"), "a drifted target must fail loudly, not re-enable freezing");
    }

    @Test
    void coldSweatMixinNeutralizesTheSeasonModifierInRotatingOnly() throws Exception {
        String src = read("java/dimblend/mixin/coldsweat/SereneSeasonsTempModifierMixin.java");
        assertTrue(src.contains(
                        "targets = \"com.momosoftworks.coldsweat.api.temperature.modifier.compat.SereneSeasonsTempModifier\""),
                "target is Cold Sweat's Serene Seasons world-temperature modifier");
        assertTrue(src.contains("\"calculate(Lnet/minecraft/world/entity/LivingEntity;\"")
                        && src.contains("\"Lcom/momosoftworks/coldsweat/api/util/Temperature$Trait;)Ljava/util/function/Function;\""),
                "calculate descriptor pinned to the javap-verified 2.4.2 signature");
        assertTrue(src.contains("at = @At(\"HEAD\")") && src.contains("cancellable = true"),
                "must short-circuit before the whitelist/season lookup");
        assertTrue(src.contains("DimBlendRegistries.ROTATING_LEVEL.equals(entity.level().dimension())"),
                "only the rotating dimension is exempt; other whitelisted dimensions keep seasonal temperature");
        assertTrue(src.contains("cir.setReturnValue(Function.identity())"),
                "identity = what Cold Sweat itself returns for a non-whitelisted dimension");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(TestSourceTree.mainFile(relative), StandardCharsets.UTF_8);
    }
}
