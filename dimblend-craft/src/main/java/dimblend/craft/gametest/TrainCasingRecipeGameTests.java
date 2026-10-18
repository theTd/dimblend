package dimblend.craft.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dimblend.craft.DimblendCraft;
import dimblend.craft.recipe.RecipeInputMatcher;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimblendCraft.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TrainCasingRecipeGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        if (ModList.get().isLoaded("create")) event.register(TrainCasingRecipeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 40)
    public static void casingInputsAreRemovedButCasingOutputIsKept(GameTestHelper helper) {
        ResourceLocation casing = ResourceLocation.parse("create:railway_casing");
        var registries = helper.getLevel().registryAccess();
        Recipe<?> direct = recipe(helper, """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"create:railway_casing"}],
                 "result":{"id":"minecraft:stick","count":1}}
                """);
        Recipe<?> tagged = recipe(helper, """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"tag":"dimblend_chassis:casings"}],
                 "result":{"id":"minecraft:stick","count":1}}
                """);
        Recipe<?> output = recipe(helper, """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:stick"}],
                 "result":{"id":"create:railway_casing","count":1}}
                """);
        Recipe<?> nested = recipe(helper, """
                {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:stone"},
                 "transitional_item":{"id":"minecraft:stone","count":1},"loops":1,
                 "sequence":[{"type":"create:deploying","ingredients":[{"item":"minecraft:stone"},
                 {"item":"create:railway_casing"}],"results":[{"id":"minecraft:stone","count":1}]}],
                 "results":[{"id":"minecraft:stone","count":1}]}
                """);
        helper.assertTrue(RecipeInputMatcher.consumes(direct, casing, registries), "direct input is matched");
        helper.assertTrue(RecipeInputMatcher.consumes(tagged, casing, registries), "tag input is matched");
        helper.assertTrue(!RecipeInputMatcher.consumes(output, casing, registries), "casing output is preserved");
        helper.assertTrue(RecipeInputMatcher.consumes(nested, casing, registries), "nested deployer input is matched");
        record Case(String input, boolean consumes) {}
        for (var example : java.util.List.of(
                new Case("""
                        {"type":"neoforge:difference","base":{"item":"minecraft:stone"},
                         "subtracted":{"item":"create:railway_casing"}}
                        """, false),
                new Case("""
                        {"type":"neoforge:difference","base":{"item":"create:railway_casing"},
                         "subtracted":{"item":"minecraft:stone"}}
                        """, true),
                new Case("""
                        {"type":"neoforge:intersection","children":[{"item":"minecraft:stone"},
                         {"item":"create:railway_casing"}]}
                        """, false),
                new Case("""
                        {"type":"neoforge:intersection","children":[{"tag":"dimblend_chassis:casings"},
                         {"item":"create:railway_casing"}]}
                        """, true),
                new Case("""
                        {"type":"neoforge:difference","base":[{"tag":"dimblend_chassis:casings"},
                         {"item":"minecraft:stone"}],"subtracted":{"item":"minecraft:stone"}}
                        """, true),
                new Case("""
                        {"type":"neoforge:difference","base":{"item":"create:railway_casing"},
                         "subtracted":{"tag":"dimblend_chassis:empty"}}
                        """, true),
                new Case("""
                        {"type":"neoforge:difference","base":{"tag":"dimblend_chassis:empty"},
                         "subtracted":{"item":"minecraft:stone"}}
                        """, false))) {
            helper.assertTrue(RecipeInputMatcher.consumes(withInput(helper, example.input()), casing, registries)
                    == example.consumes(), "compound input preserves its actual predicate: " + example.input());
        }
        var manager = new net.minecraft.world.item.crafting.RecipeManager(registries);
        var consumedId = ResourceLocation.parse("dimblend_chassis:consumed");
        var retainedId = ResourceLocation.parse("dimblend_chassis:retained");
        manager.replaceRecipes(java.util.List.of(new net.minecraft.world.item.crafting.RecipeHolder<>(consumedId, tagged),
                new net.minecraft.world.item.crafting.RecipeHolder<>(retainedId, output)));
        dimblend.craft.recipe.RecipeIngredientReloadFilter.defer(manager, registries);
        helper.assertTrue(manager.byKey(consumedId).isPresent(), "input filtering is deferred until tags finish binding");
        dimblend.craft.recipe.RecipeIngredientReloadFilter.onTagsUpdated(
                new net.neoforged.neoforge.event.TagsUpdatedEvent(registries, true, false));
        helper.assertTrue(manager.byKey(consumedId).isPresent(), "client tag sync must not consume the server reload");
        dimblend.craft.recipe.RecipeIngredientReloadFilter.onTagsUpdated(
                new net.neoforged.neoforge.event.TagsUpdatedEvent(registries, false, false));
        helper.assertTrue(manager.byKey(consumedId).isEmpty() && manager.byKey(retainedId).isPresent(),
                "server tag binding applies ingredient deletion and preserves casing production");
        helper.succeed();
    }

    private static Recipe<?> recipe(GameTestHelper helper, String json) {
        return Recipe.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess()),
                JsonParser.parseString(json)).getOrThrow();
    }

    private static Recipe<?> withInput(GameTestHelper helper, String input) {
        return recipe(helper, "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[" + input
                + "],\"result\":{\"id\":\"minecraft:stick\",\"count\":1}}");
    }
}
