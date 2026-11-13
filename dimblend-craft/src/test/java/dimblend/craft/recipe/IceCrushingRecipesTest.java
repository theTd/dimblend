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
 * 蓝冰 / 浮冰 的 Create 粉碎配方（随包数据）。
 *
 * <p>全整合包 190 个 jar 扫描确认：上游没有任何 {@code blue_ice} 或 {@code packed_ice} 的
 * {@code create:crushing} 配方，因此两条配方由本 mod 随包提供，且不需要
 * {@code RecipeEditRules} 的改写或删除规则（无上游配方可匹配，加了只会留一条 WARN）。</p>
 *
 * <p>JSON 通过类加载器读取（与 {@link FilterFrameStonecuttingTest} 一致），文件缺失或格式
 * 错误在单测阶段就失败，而不是等到进游戏才发现。</p>
 */
class IceCrushingRecipesTest {

    private static final String BLUE_ICE_RECIPE = "data/dimblend_craft/recipe/blue_ice_crushing.json";
    private static final String PACKED_ICE_RECIPE = "data/dimblend_craft/recipe/packed_ice_crushing.json";

    /** 1 蓝冰 -> 3 浮冰。 */
    @Test
    void crushingBlueIceYieldsThreePackedIce() throws Exception {
        JsonObject recipe = packResource(BLUE_ICE_RECIPE);

        assertEquals("create:crushing", recipe.get("type").getAsString(),
                "蓝冰粉碎配方必须是 create:crushing");
        assertSingleItemIngredient(recipe, "minecraft:blue_ice");
        assertSingleResult(recipe, "minecraft:packed_ice");
    }

    /** 1 浮冰 -> 3 冰。 */
    @Test
    void crushingPackedIceYieldsThreeIce() throws Exception {
        JsonObject recipe = packResource(PACKED_ICE_RECIPE);

        assertEquals("create:crushing", recipe.get("type").getAsString(),
                "浮冰粉碎配方必须是 create:crushing");
        assertSingleItemIngredient(recipe, "minecraft:packed_ice");
        assertSingleResult(recipe, "minecraft:ice");
    }

    /** 加工时间 150 tick：与其他随包粉碎配方一致，且必须为正数。 */
    @Test
    void bothRecipesProcessInOneHundredFiftyTicks() throws Exception {
        for (String path : new String[] {BLUE_ICE_RECIPE, PACKED_ICE_RECIPE}) {
            JsonObject recipe = packResource(path);

            JsonElement rawTime = recipe.get("processing_time");
            assertNotNull(rawTime, path + " 缺少 processing_time");
            int processingTime = rawTime.getAsInt();
            assertEquals(150, processingTime, path + " 的 processing_time 必须是 150");
            assertTrue(processingTime > 0, path + " 的 processing_time 必须大于 0");
        }
    }

    /**
     * 两条配方的产物分别来自原版、但配方类型来自 Create，故只在 Create 存在时加载；
     * 缺失时跳过而不是解析失败。
     */
    @Test
    void recipesOnlyLoadWhenCreateIsPresent() throws Exception {
        for (String path : new String[] {BLUE_ICE_RECIPE, PACKED_ICE_RECIPE}) {
            JsonObject recipe = packResource(path);

            JsonElement rawConditions = recipe.get("neoforge:conditions");
            assertNotNull(rawConditions, path + " 必须带 neoforge:conditions 门槛");
            assertTrue(rawConditions.isJsonArray(), path + " 的 neoforge:conditions 必须是数组");
            assertTrue(hasModLoadedCondition(rawConditions.getAsJsonArray(), "create"),
                    path + " 必须要求 create 模组（create:crushing 类型）");
        }
    }

    // —— helpers ——

    /** 材料表恰好 1 项，且为指定的物品（不带 tag、不带 chance）。 */
    private static void assertSingleItemIngredient(JsonObject recipe, String expectedItem) {
        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        assertNotNull(ingredients, "缺少 ingredients 数组");
        assertEquals(1, ingredients.size(), "ingredients 必须恰好 1 项");

        JsonObject ingredient = ingredients.get(0).getAsJsonObject();
        assertEquals(expectedItem, ingredient.get("item").getAsString(),
                "ingredients[0].item 必须是 " + expectedItem);
        assertFalse(ingredient.has("chance"), "ingredients[0] 不应带 chance 字段");
    }

    /** 产物表恰好 1 项：指定物品、数量 3、无概率字段（必定产出）。 */
    private static void assertSingleResult(JsonObject recipe, String expectedId) {
        JsonArray results = recipe.getAsJsonArray("results");
        assertNotNull(results, "缺少 results 数组");
        assertEquals(1, results.size(), "results 必须恰好 1 项");

        JsonObject result = results.get(0).getAsJsonObject();
        assertEquals(expectedId, result.get("id").getAsString(),
                "results[0].id 必须是 " + expectedId);
        assertEquals(3, result.get("count").getAsInt(), "results[0].count 必须是 3");
        assertFalse(result.has("chance"), "results[0] 不应带 chance 字段（必须必出）");
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
        try (InputStream in = IceCrushingRecipesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少随包配方资源: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
