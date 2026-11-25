package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 2026-11-25 批次的随包配方自检：新增 5 条、改写 4 条、删除 2 条。
 *
 * <p>每条断言都对着上游 jar 的真实 schema 写（不是照需求文档抄的字段名）：</p>
 * <ul>
 *   <li>{@code createaddition:charging}：{@code energy} 与 {@code max_charge_rate} 都是
 *       必填 {@code fieldOf}（{@code ChargingRecipeParams} 反编译核实），
 *       且材料/产物用的是 {@code ingredients}/{@code results} 两个数组
 *       （{@code electrify_gold_ingot.json} 逐字核实 —— 文档草稿里的单数写法是错的）；</li>
 *   <li>{@code createdieselgenerators:basin_fermenting} / {@code bulk_fermenting}：
 *       {@code ingredients}/{@code processing_time}/{@code results}，
 *       产物是流体条目 {@code {"id", "amount"}}（{@code fermentable.json} 核实）；</li>
 *   <li>{@code create:mixing} 与 {@code create:compacting}：物品材料是 {@code {"item"}}，
 *       流体材料是 {@code neoforge:single}（Create 6.0.10 的 {@code tea.json} /
 *       {@code dough_by_mixing.json} 核实）。</li>
 * </ul>
 */
class Batch1125RecipesTest {

    private static final String DIR = "data/dimblend_craft/recipe/";

    // —— 1. 删除的两条：脏水瓶的营火烹饪 / 烟熏（2026-09 口径推翻，2026-11-25 删源） ——

    @Test
    void dirtyWaterBottleCookingRecipesAreGone() {
        assertFalse(exists("dirty_water_bottle_from_campfire.json"),
                "dirty_water_bottle_from_campfire.json 应已删除（只能经滤芯得到可饮用水）");
        assertFalse(exists("dirty_water_bottle_from_smoking.json"),
                "dirty_water_bottle_from_smoking.json 应已删除");
    }

    // —— 2. 火药混合：产物 3 -> 4 ——

    @Test
    void gunpowderMixingYieldsFour() throws Exception {
        JsonObject recipe = pack("gunpowder_from_mixing.json");
        assertEquals(4, result(recipe, 0).get("count").getAsInt(), "火药混合必须产出 4 个");
        assertEquals("minecraft:gunpowder", result(recipe, 0).get("id").getAsString());
        assertEquals("heated", recipe.get("heat_requirement").getAsString(), "热度档不在本次需求内");
    }

    // —— 3. 净水链两条的交换：可饮用水 500 -> 1000，纯净水 1000 -> 500 ——

    @Test
    void acceptableWaterMixingReturnsFullBucket() throws Exception {
        JsonObject recipe = pack("acceptable_water_from_mixing.json");
        JsonObject water = result(recipe, 0);
        assertEquals(1000, water.get("amount").getAsInt(),
                "可饮用水（Purity 1 + 烈焰粉）应产出 1000mB，与输入等量");
        assertEquals("{Purity:2}", purity(water), "产物纯度必须是 2（杀菌）");
        assertEquals(1000, recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("amount").getAsInt(), "输入仍是 1000mB Purity 1 水");
        assertEquals("minecraft:blaze_powder", result(recipe, 1).get("id").getAsString(),
                "烈焰粉必须作为催化剂返还");
    }

    @Test
    void purifiedWaterMixingHalvesOutput() throws Exception {
        JsonObject recipe = pack("purified_water_from_heated_mixing.json");
        JsonObject water = result(recipe, 0);
        assertEquals(500, water.get("amount").getAsInt(), "纯净水（加热）应产出 500mB");
        assertEquals("{Purity:3}", purity(water), "产物纯度必须是 3（纯净）");
        assertEquals("heated", recipe.get("heat_requirement").getAsString(), "仍必须加热");
        assertEquals(1000, recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("amount").getAsInt(), "输入仍是 1000mB Purity 2 水");
    }

    // —— 4. 细雪熔水补上杀菌纯度（审计 Default #7） ——

    @Test
    void powderSnowMeltingProducesSterilizedWater() throws Exception {
        JsonObject recipe = pack("water_from_powder_snow_mixing.json");
        JsonObject water = result(recipe, 0);
        assertEquals("minecraft:water", water.get("id").getAsString());
        assertEquals(1000, water.get("amount").getAsInt());
        assertEquals("{Purity:2}", purity(water),
                "细雪（+ 烈焰粉）熔出的水必须与冰 + 烈焰粉同档，是杀菌水 {Purity:2}");
        assertEquals("minecraft:blaze_powder", result(recipe, 1).get("id").getAsString(),
                "烈焰粉必须返还");

        JsonObject powderSnow = recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals("neoforge:single", powderSnow.get("type").getAsString(),
                "流体材料必须用 NeoForge 的 neoforge:single（fluid_stack 不是注册过的类型）");
        assertEquals("fluid:powder_snow", powderSnow.get("fluid").getAsString());
        assertEquals(1000, powderSnow.get("amount").getAsInt());
    }

    // —— 5. 新增：冰 + 烈焰粉混合出杀菌水（决议 9 ice_blaze_return_catalyst） ——

    @Test
    void iceAndBlazePowderMixIntoSterilizedWater() throws Exception {
        JsonObject recipe = pack("acceptable_water_from_ice_mixing.json");
        assertEquals("create:mixing", recipe.get("type").getAsString());
        assertEquals("none", recipe.get("heat_requirement").getAsString(), "冰不需要加热");

        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        assertEquals(2, ingredients.size(), "冰 + 烈焰粉两条材料，实际 " + ingredients);
        assertEquals("minecraft:ice", ingredients.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals("minecraft:blaze_powder", ingredients.get(1).getAsJsonObject().get("item").getAsString());

        JsonObject water = result(recipe, 0);
        assertEquals("minecraft:water", water.get("id").getAsString());
        assertEquals(1000, water.get("amount").getAsInt(), "1 个冰换 1000mB 水");
        assertEquals("{Purity:2}", purity(water), "冰化水是杀菌水 {Purity:2}（与冰 + 烈焰粉同档）");
        assertEquals("minecraft:blaze_powder", result(recipe, 1).get("id").getAsString(),
                "烈焰粉是催化剂，必须返还 1 个（决议 9）");

        assertEquals(Set.of("create", "yet_another_thirst"), requiredMods(recipe));
    }

    // —— 6. 新增：岩浆压缩出圆石 ——

    @Test
    void lavaCompactsIntoCobblestone() throws Exception {
        JsonObject recipe = pack("cobblestone_from_lava_compacting.json");
        assertEquals("create:compacting", recipe.get("type").getAsString());
        assertFalse(recipe.has("heat_requirement"),
                "不加热：与随包 canola_seeds_compacting 一致，省略 heat_requirement 即为 none");

        JsonObject lava = recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject();
        assertEquals("neoforge:single", lava.get("type").getAsString());
        assertEquals("minecraft:lava", lava.get("fluid").getAsString());
        assertEquals(75, lava.get("amount").getAsInt(), "75mB 岩浆 = 1 个圆石");

        assertEquals("minecraft:cobblestone", result(recipe, 0).get("id").getAsString());
        assertEquals(Set.of("create"), requiredMods(recipe));
    }

    // —— 7. 新增：蓝冰充能成炽冰（决议 14 hot_ice_rate_360） ——

    @Test
    void blueIceChargesIntoHotIce() throws Exception {
        JsonObject recipe = pack("hot_ice_from_charging.json");
        assertEquals("createaddition:charging", recipe.get("type").getAsString());
        assertEquals(90000, recipe.get("energy").getAsInt(), "总能耗 90000 FE");
        assertEquals(360, recipe.get("max_charge_rate").getAsInt(),
                "决议 14：max_charge_rate 必须是 360（不是默认 360 以外的任何值）");

        assertEquals("minecraft:blue_ice", recipe.getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("item").getAsString());
        JsonObject hotIce = result(recipe, 0);
        assertEquals("dimblend_blocks:hot_ice", hotIce.get("id").getAsString());
        assertEquals(1, hotIce.get("count").getAsInt());

        assertEquals(Set.of("createaddition", "dimblend_blocks"), requiredMods(recipe));
    }

    // —— 8. 新增：啤酒花发酵出乙醇（决议 11 hop_bulk_100mb：桶 50 / 批量 100） ——

    @Test
    void hopFermentingProducesEthanol() throws Exception {
        JsonObject basin = pack("ethanol_from_hop_fermenting.json");
        assertEquals("createdieselgenerators:basin_fermenting", basin.get("type").getAsString());
        assertEquals(200, basin.get("processing_time").getAsInt());
        assertEquals("c:hops", basin.getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("tag").getAsString(), "材料用标签 c:hops（唯一成员是次元酒窖啤酒花）");
        JsonObject basinResult = result(basin, 0);
        assertEquals("createdieselgenerators:ethanol", basinResult.get("id").getAsString());
        assertEquals(50, basinResult.get("amount").getAsInt(), "工作盆发酵：1 啤酒花 -> 50mB 乙醇");
        assertEquals(Set.of("createdieselgenerators", "kaleidoscope_dim_wine"), requiredMods(basin));

        JsonObject bulk = pack("ethanol_from_hop_bulk_fermenting.json");
        assertEquals("createdieselgenerators:bulk_fermenting", bulk.get("type").getAsString());
        assertEquals(200, bulk.get("processing_time").getAsInt());
        assertEquals("c:hops", bulk.getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("tag").getAsString());
        JsonObject bulkResult = result(bulk, 0);
        assertEquals("createdieselgenerators:ethanol", bulkResult.get("id").getAsString());
        assertEquals(100, bulkResult.get("amount").getAsInt(),
                "批量发酵决议值 100mB（默认曾是 50，决议 11 改为 100）");
        assertEquals(Set.of("createdieselgenerators", "kaleidoscope_dim_wine"), requiredMods(bulk));
    }

    // —— 辅助 ——

    private static JsonObject result(JsonObject recipe, int index) {
        JsonArray results = recipe.getAsJsonArray("results");
        assertNotNull(results, "配方缺少 results 数组");
        assertTrue(results.size() > index, "配方 results 不足 " + (index + 1) + " 条：" + results);
        return results.get(index).getAsJsonObject();
    }

    /** 流体 / 物品产物里的纯度组件：{@code components.minecraft:custom_data} 字符串。 */
    private static String purity(JsonObject output) {
        JsonObject components = output.getAsJsonObject("components");
        assertNotNull(components, "产物缺少 components，纯度不会生效：" + output);
        return components.get("minecraft:custom_data").getAsString();
    }

    /** 汇总 {@code neoforge:mod_loaded} 声明的 modid，用于钉死加载条件。 */
    private static Set<String> requiredMods(JsonObject recipe) {
        JsonArray conditions = recipe.getAsJsonArray("neoforge:conditions");
        assertNotNull(conditions, "配方缺少 neoforge:conditions");
        Set<String> mods = new HashSet<>();
        for (JsonElement element : conditions) {
            JsonObject condition = element.getAsJsonObject();
            if ("neoforge:mod_loaded".equals(condition.get("type").getAsString())) {
                mods.add(condition.get("modid").getAsString());
            }
        }
        return mods;
    }

    private static JsonObject pack(String fileName) throws Exception {
        try (InputStream in = Batch1125RecipesTest.class.getClassLoader()
                .getResourceAsStream(DIR + fileName)) {
            assertNotNull(in, "缺少随包配方资源：" + DIR + fileName);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static boolean exists(String fileName) {
        try (InputStream in = Batch1125RecipesTest.class.getClassLoader()
                .getResourceAsStream(DIR + fileName)) {
            return in != null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
