package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

/**
 * 蓝冰 / 浮冰 的 Create 粉碎配方（随包数据 + 上游拆解粉碎配方防御性删除）。
 *
 * <p>两条 {@code 1 -> 3} 粉碎配方由本 mod 随包提供；玩家端 PCL 整合包安装的
 * {@code Create Recycle Everything (Continued) 1.1} 另有 {@code 1 -> 9} 拆解粉碎配方
 * （本机开发实例未装该 jar），由 {@link RecipeEditRules#edits()} 按精确配方 ID 防御性删除。</p>
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

    /**
     * 2026-11-14：删除 Create Recycle Everything 的蓝冰/浮冰 1->9 拆解粉碎配方，
     * 同时确保随包 1->3 配方与非粉碎冰配方不被误删。
     */
    @Test
    void upstreamRecycleEverythingIceCrushingRecipesAreDefensivelyDeleted() {
        Set<ResourceLocation> expectedDeleted = Set.of(
                ResourceLocation.parse("create_recycle_everything:crushing/vanilla/scrap_blue_ice"),
                ResourceLocation.parse("create_recycle_everything:crushing/vanilla/scrap_packed_ice"),
                ResourceLocation.parse("create:crushing/misc/scrap_blue_ice"),
                ResourceLocation.parse("create:crushing/misc/scrap_packed_ice"));
        RecipeEdit.DeleteByRecipeId found = null;
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete
                    && "蓝冰与浮冰原拆解粉碎配方".equals(delete.label())) {
                found = delete;
                break;
            }
        }
        assertNotNull(found, "缺少删除蓝冰/浮冰上游拆解粉碎配方的规则");
        assertEquals(expectedDeleted, found.recipeIds(),
                "必须覆盖 create_recycle_everything 1.1 与 1.0.x 两条同源拆解粉碎配方 ID");
        assertTrue(found.defensive(),
                "开发实例未装 create_recycle_everything，必须标 defensive = true 避免假 WARN");

        Set<ResourceLocation> mustKeepIds = Set.of(
                ResourceLocation.parse("dimblend_craft:blue_ice_crushing"),
                ResourceLocation.parse("dimblend_craft:packed_ice_crushing"),
                ResourceLocation.parse("create:splashing/ice"),
                ResourceLocation.parse("minecraft:packed_ice"),
                ResourceLocation.parse("minecraft:blue_ice"));
        Set<ResourceLocation> mustKeepResults = Set.of(
                ResourceLocation.parse("minecraft:ice"),
                ResourceLocation.parse("minecraft:packed_ice"),
                ResourceLocation.parse("minecraft:blue_ice"));
        for (List<RecipeEdit> phase : List.of(
                RecipeEditRules.edits(), RecipeEditRules.ingredientEdits(), RecipeEditRules.lateEdits())) {
            for (RecipeEdit edit : phase) {
                if (edit instanceof RecipeEdit.DeleteByRecipeId delete) {
                    for (ResourceLocation keep : mustKeepIds) {
                        assertFalse(delete.recipeIds().contains(keep),
                                "规则「" + delete.label() + "」误删了必须保留的配方 " + keep);
                    }
                }
                if (edit instanceof RecipeEdit.DeleteByResultId delete) {
                    for (ResourceLocation result : mustKeepResults) {
                        assertFalse(delete.resultIds().matches(result),
                                "规则「" + delete.label() + "」按产物删除命中了 " + result);
                    }
                }
            }
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
