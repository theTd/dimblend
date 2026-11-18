package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 随包配方 {@code dimblend_craft:canola_seeds_compacting}：1 油菜籽 -> 200mB 种子油（无加热）。
 *
 * <p>输出是 Create Crafts &amp; Additions 的流体 {@code createaddition:seed_oil}，不是
 * 森罗厨房的菜籽油 {@code kaleidoscope_grilling:canola_oil}；不加热，也不改上游
 * {@code #c:seeds -> 100mB} 的通用配方。</p>
 */
class CanolaSeedsCompactingTest {

    private static final String RECIPE = "data/dimblend_craft/recipe/canola_seeds_compacting.json";

    @Test
    void compactsOneCanolaSeedIntoTwoHundredMillibucketsOfSeedOil() throws Exception {
        JsonObject recipe = packResource(RECIPE);

        assertEquals("create:compacting", recipe.get("type").getAsString(),
                "必须是 Create 工作盆塑形配方");

        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        assertNotNull(ingredients, "缺少 ingredients 数组");
        assertEquals(1, ingredients.size(), "材料恰好 1 项");
        assertEquals("kaleidoscope_grilling:canola_seeds",
                ingredients.get(0).getAsJsonObject().get("item").getAsString(),
                "材料必须是油菜籽");

        JsonArray results = recipe.getAsJsonArray("results");
        assertNotNull(results, "缺少 results 数组");
        assertEquals(1, results.size(), "产物恰好 1 项");
        JsonObject result = results.get(0).getAsJsonObject();
        assertEquals("createaddition:seed_oil", result.get("id").getAsString(),
                "产物必须是 Create Addition 的种子油流体");
        assertEquals(200, result.get("amount").getAsInt(), "1 油菜籽 = 200mB 种子油");
        assertFalse(result.has("count"), "流体产物使用 amount，不是物品 count");
    }

    @Test
    void compactingNeedsNoHeat() throws Exception {
        JsonObject recipe = packResource(RECIPE);
        assertFalse(recipe.has("heat_requirement"), "不写 heat_requirement 即无加热");
    }

    @Test
    void loadsOnlyWhenCreateCanolaAndCreateAdditionArePresent() throws Exception {
        JsonArray conditions = packResource(RECIPE).getAsJsonArray("neoforge:conditions");
        assertNotNull(conditions, "必须带 neoforge:conditions 门槛");
        for (String modId : new String[]{"create", "kaleidoscope_grilling", "createaddition"}) {
            assertTrue(hasModLoadedCondition(conditions, modId), "必须要求 " + modId);
        }
    }

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
        try (InputStream in = CanolaSeedsCompactingTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少随包配方资源: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
