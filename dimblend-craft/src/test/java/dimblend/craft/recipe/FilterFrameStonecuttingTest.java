package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * T-03: stonecutting recipe that turns one Create spout into one YetAnotherThirst filter frame.
 *
 * <p>{@code yet_another_thirst:filter_frame} ships without any recipe of its own (188 pack jars
 * scanned), so the frame is currently unobtainable. This pack supplies the recipe the user asked
 * for: a spout loaded into the stonecutter produces one frame.</p>
 *
 * <p>The JSON is read through the same classpath loader {@link RecipeEditRulesTest} uses for the
 * shipped recipes, so a missing or malformed file fails here instead of only in game.</p>
 */
class FilterFrameStonecuttingTest {

    private static final String RECIPE_PATH = "data/dimblend_craft/recipe/filter_frame_from_spout.json";

    private static final String SPOUT = "create:spout";
    private static final String FILTER_FRAME = "yet_another_thirst:filter_frame";

    /** The recipe itself: stonecutting type, spout input, one frame out. */
    @Test
    void stonecutterCutsOneSpoutIntoOneFilterFrame() throws Exception {
        JsonObject recipe = packResource(RECIPE_PATH);

        assertEquals("minecraft:stonecutting", recipe.get("type").getAsString(),
                "T-03: the recipe must be a minecraft:stonecutting recipe");

        JsonObject ingredient = recipe.getAsJsonObject("ingredient");
        assertNotNull(ingredient, "T-03: the recipe has no ingredient object");
        assertEquals(SPOUT, ingredient.get("item").getAsString(),
                "T-03: the stonecutter input must be the Create spout");

        JsonObject result = recipe.getAsJsonObject("result");
        assertNotNull(result, "T-03: the recipe has no result object");
        assertEquals(FILTER_FRAME, result.get("id").getAsString(),
                "T-03: the stonecutter output must be the thirst filter frame");
        assertEquals(1, result.get("count").getAsInt(),
                "T-03: one spout must yield exactly one filter frame");
    }

    /**
     * Both sides of the recipe come from other mods. Every other cross-mod recipe in this data
     * pack is gated on {@code neoforge:mod_loaded}, so a pack without either mod skips the recipe
     * instead of failing to parse an unknown item id.
     */
    @Test
    void recipeOnlyLoadsWhenBothSourceModsArePresent() throws Exception {
        JsonObject recipe = packResource(RECIPE_PATH);

        JsonElement rawConditions = recipe.get("neoforge:conditions");
        assertNotNull(rawConditions, "T-03: the recipe must be gated on its two source mods");
        assertTrue(rawConditions.isJsonArray(), "T-03: neoforge:conditions must be a JSON array");

        JsonArray conditions = rawConditions.getAsJsonArray();
        assertTrue(hasModLoadedCondition(conditions, "create"),
                "T-03: the recipe must require the create mod (spout ingredient)");
        assertTrue(hasModLoadedCondition(conditions, "yet_another_thirst"),
                "T-03: the recipe must require the yet_another_thirst mod (filter frame result)");
    }

    // —— helpers ——

    private static boolean hasModLoadedCondition(JsonArray conditions, String modId) {
        for (JsonElement element : conditions) {
            JsonObject condition = element.getAsJsonObject();
            JsonElement type = condition.get("type");
            JsonElement id = condition.get("modid");
            if (type != null && id != null
                    && "neoforge:mod_loaded".equals(type.getAsString())
                    && modId.equals(id.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject packResource(String path) throws Exception {
        try (InputStream in = FilterFrameStonecuttingTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "T-03: missing bundled recipe resource: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
