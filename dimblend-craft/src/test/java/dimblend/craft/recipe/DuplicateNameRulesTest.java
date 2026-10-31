package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dimblend.craft.client.LanguageOverrides;
import dimblend.craft.mixin.MillstoneRecipeMixinPlugin;

/**
 * R12（2026-10-31 用户逐条确认）重名物品处理的自检：断源规则、材料/产物替换规则，
 * 以及外 namespace 的显示名覆盖表。
 *
 * <p>枚举依据：整合包 {@code E:\misc\TrainTripWorld\mods} 全部 188 个 jar（含
 * {@code META-INF/jarjar} 内 82 个嵌套 jar）的 {@code data/**} 与
 * {@code assets/*}{@code /lang/*.json} 逐条扫描，具体数字见本类断言中的注释。</p>
 *
 * <p>方向约定（用户口径「只改名 = 改非主 mod 一侧」，dup-02 / dup-06 为按此约定推断）：
 * <ul>
 *   <li>dup-02 石磨：保留 Create，改森罗厨房一侧（{@code kaleidoscope_cookery}）；</li>
 *   <li>dup-04 铜粒：暮色 3 条产出配方保留、产物改成 {@code create:copper_nugget}（配方不再产出
 *       暮色铜粒），Create 一侧配方原样；</li>
 *   <li>dup-05 浆果拼盘：保留森罗，改暮色森林一侧（{@code twilightforest}）；</li>
 *   <li>dup-06 可乐：保留世界名酒（{@code kaleidoscope_world_liquor:cola}），删次元酒窖产出 pepsi；</li>
 *   <li>dup-07 冰箱：任务书明确改 {@code cold_sweat} 一侧，森罗 7 个 freezer 不动。</li>
 * </ul></p>
 */
class DuplicateNameRulesTest {

    private static final String CREATE = "create";
    private static final String COOKERY = "kaleidoscope_cookery";
    private static final String TWILIGHT = "twilightforest";
    private static final String COLD_SWEAT = "cold_sweat";

    /** 暮色森林产出铜粒的 3 条配方（逐 jar 枚举，全部为暮色本体；口径改为保留配方、换产物）。 */
    private static final Set<ResourceLocation> TWILIGHT_COPPER_NUGGET_PRODUCERS = Set.of(
            ResourceLocation.parse("twilightforest:material/smelted_copper_nugget"),
            ResourceLocation.parse("twilightforest:material/blasted_copper_nugget"),
            ResourceLocation.parse("twilightforest:copper_ingot_to_nuggets"));

    /** 次元酒窖产出 pepsi 的唯一配方（带 the_bumblezone 加载条件，本包未装该 mod，当前不加载）。 */
    private static final ResourceLocation PEPSI_PRODUCER =
            ResourceLocation.parse("kaleidoscope_dim_wine:barrel/pepsi");

    /** Create 6.0.10 三个语言文件中全部含「玫瑰石英」/「Rose Quartz」的 9 个键（逐条枚举）。 */
    private static final Set<String> ROSE_QUARTZ_KEYS = new LinkedHashSet<>(List.of(
            "advancement.create.rose_quartz.desc",
            "block.create.rose_quartz_block",
            "block.create.rose_quartz_lamp",
            "block.create.rose_quartz_tiles",
            "block.create.small_rose_quartz_tiles",
            "create.ponder.rose_quartz_lamp.header",
            "create.ponder.rose_quartz_lamp.text_1",
            "item.create.polished_rose_quartz",
            "item.create.rose_quartz"));

    /** 磨石改名覆盖：值恰好等于旧显示名的同方块键（方块名 / EMI 分类 / Jade 标签）。 */
    private static final Set<String> MILLSTONE_KEYS = Set.of(
            "block.kaleidoscope_cookery.millstone",
            "emi.category.kaleidoscope_cookery.millstone",
            "config.jade.plugin_kaleidoscope_cookery.millstone");

    /** 冰箱改名覆盖：方块名与容器界面标题。 */
    private static final Set<String> ICEBOX_KEYS = Set.of(
            "block.cold_sweat.icebox",
            "container.cold_sweat.icebox");

    private static final String BERRY_MEDLEY_KEY = "item.twilightforest.berry_medley";

    /**
     * dup-04（2026-10-31 改口径：断掉一方但把暮色铜粒参与的配方换成 Create 的）：
     * 3 条暮色配方**保留**，产物 id 改成 {@code create:copper_nugget}，数量与材料原样；
     * 暮色铜粒不再被任何配方产出（全包枚举里只有这 3 条产出它，全部被本规则改写）。
     *
     * <p>「配方仍存在」以「没有任何删除规则命中这 3 个 id」钉住；产物替换用 3 条真实配方 JSON
     * （取自 {@code twilightforest-4.8.3345} 的 {@code data/twilightforest/recipe/**}）直接验证。</p>
     */
    @Test
    void twilightCopperNuggetRecipesAreKeptButProduceCreateNugget() {
        Set<ResourceLocation> deleted = deliberatelyDeleted(RecipeEditRules.edits());
        for (ResourceLocation id : TWILIGHT_COPPER_NUGGET_PRODUCERS) {
            assertFalse(deleted.contains(id), "暮色铜粒配方 " + id + " 不应被删除（口径：保留配方、改产物）");
        }

        RecipeEdit.ReplaceResultId rule = findResultRule(ResourceLocation.parse("twilightforest:copper_nugget"));
        assertNotNull(rule, "缺少暮色铜粒 → create:copper_nugget 的产物替换规则");
        assertEquals(ResourceLocation.parse("create:copper_nugget"), rule.to(),
                "暮色铜粒产物必须替换成 create:copper_nugget");
        assertFalse(rule.defensive(), "3 条产出配方今天存在，不属于防御性规则");

        // 1) 熔炼：铜浆果 -> 铜粒 x1
        JsonElement smelting = rule.transform(json("""
                {"type":"minecraft:smelting","category":"misc","cookingtime":200,"experience":1.0,
                 "group":"copper_nugget","ingredient":{"item":"twilightforest:copper_berry"},
                 "result":{"count":1,"id":"twilightforest:copper_nugget"}}
                """));
        assertEquals("create:copper_nugget", resultId(smelting), "熔炼产物未替换");
        assertEquals(1, resultCount(smelting), "熔炼产物数量应保持 1");
        assertEquals("twilightforest:copper_berry", ingredientItem(smelting), "熔炼材料不应被改写");

        // 2) 高炉：铜浆果 -> 铜粒 x1
        JsonElement blasting = rule.transform(json("""
                {"type":"minecraft:blasting","category":"misc","cookingtime":100,"experience":1.0,
                 "group":"copper_nugget","ingredient":{"item":"twilightforest:copper_berry"},
                 "result":{"count":1,"id":"twilightforest:copper_nugget"}}
                """));
        assertEquals("create:copper_nugget", resultId(blasting), "高炉产物未替换");
        assertEquals(1, resultCount(blasting), "高炉产物数量应保持 1");

        // 3) 无序合成：铜锭 -> 铜粒 x9（数量必须还是 9）
        JsonElement shapeless = rule.transform(json("""
                {"type":"minecraft:crafting_shapeless","category":"misc",
                 "ingredients":[{"tag":"c:ingots/copper"}],
                 "result":{"count":9,"id":"twilightforest:copper_nugget"}}
                """));
        assertEquals("create:copper_nugget", resultId(shapeless), "合成产物未替换");
        assertEquals(9, resultCount(shapeless), "铜锭换铜粒的产物数量应保持 9");
        assertEquals("c:ingots/copper", ingredientTag(shapeless), "合成材料不应被改写");
    }

    /** Create 一侧的铜粒配方不能误删。 */
    @Test
    void createCopperNuggetProducersAreKept() {
        Set<ResourceLocation> deleted = deliberatelyDeleted(RecipeEditRules.edits());
        for (ResourceLocation id : List.of(
                ResourceLocation.parse("create:crafting/materials/copper_nugget"),
                ResourceLocation.parse("create:crushing/tuff"),
                ResourceLocation.parse("create:crushing/tuff_recycling"),
                ResourceLocation.parse("create:crushing/veridium"),
                ResourceLocation.parse("create:crushing/veridium_recycling"),
                ResourceLocation.parse("create:splashing/crushed_raw_copper"))) {
            assertFalse(deleted.contains(id), "Create 铜粒产出配方 " + id + " 不应被删除");
        }
    }

    /**
     * 铜粒材料替换规则：直接写 {@code twilightforest:copper_nugget} 的材料改成
     * {@code create:copper_nugget}。
     *
     * <p>全整合包 11734 条配方（11609 主 jar + 125 嵌套 jar）枚举结果：当前没有任何配方
     * 直接以该物品为材料（只有 {@code c:nuggets/copper} 标签间接接受它），所以规则今天
     * 必然空转，标为防御性；上游以后新增直接引用时自动替换。</p>
     */
    @Test
    void copperNuggetIngredientRuleIsDefensiveAndItemScoped() {
        RecipeEdit.ReplaceIngredient rule = findIngredientRule(
                RecipeEdit.IngredientTarget.item("twilightforest:copper_nugget"));
        assertNotNull(rule, "缺少暮色铜粒的材料替换规则");
        assertEquals(RecipeEdit.IngredientTarget.item("create:copper_nugget"), rule.to(),
                "暮色铜粒材料必须替换成 create:copper_nugget");
        assertTrue(rule.defensive(), "当前无直接引用者，规则必须是防御性（未命中只记 INFO）");
    }

    /** dup-11：森罗面粉材料（物品形式与 c:flour 标签形式）都替换成 Create 小麦面粉标签。 */
    @Test
    void flourIngredientRulesCoverItemAndTagForms() {
        RecipeEdit.ReplaceIngredient itemRule =
                findIngredientRule(RecipeEdit.IngredientTarget.item("kaleidoscope_cookery:flour"));
        assertNotNull(itemRule, "缺少 kaleidoscope_cookery:flour 物品形式的材料替换规则");
        assertEquals(RecipeEdit.IngredientTarget.tag("c:flours/wheat"), itemRule.to(),
                "森罗面粉物品材料必须替换成 c:flours/wheat 标签");

        RecipeEdit.ReplaceIngredient tagRule =
                findIngredientRule(RecipeEdit.IngredientTarget.tag("c:flour"));
        assertNotNull(tagRule, "缺少 c:flour 标签形式的材料替换规则");
        assertEquals(RecipeEdit.IngredientTarget.tag("c:flours/wheat"), tagRule.to(),
                "c:flour 标签必须替换成 c:flours/wheat 标签");
        assertFalse(tagRule.defensive(), "c:flour 标签今天有 13 条配方使用，不属于防御性规则");
    }

    /** dup-11b：产物是森罗面粉的配方改成 Create 小麦面粉，数量不动。 */
    @Test
    void flourResultRuleReplacesProducerResultWithWheatFlour() {
        RecipeEdit.ReplaceResultId rule = findResultRule(ResourceLocation.parse("kaleidoscope_cookery:flour"));
        assertNotNull(rule, "缺少森罗面粉产物替换规则");
        assertEquals(ResourceLocation.parse("create:wheat_flour"), rule.to(),
                "森罗面粉产物必须替换成 create:wheat_flour");
        assertFalse(rule.defensive(), "磨石产面粉配方今天存在，不属于防御性规则");
    }

    /** 可乐一侧：只删次元酒窖 pepsi 产出，世界名酒的 cola 配方一条不碰。 */
    @Test
    void colaSideIsNotTouchedWhilePepsiProducerIsDeleted() {
        RecipeEdit.DeleteByRecipeId pepsiRule = findDeleteRule(PEPSI_PRODUCER);
        assertNotNull(pepsiRule, "缺少 pepsi 产出配方删除规则");
        assertTrue(pepsiRule.defensive(),
                "pepsi 配方带 the_bumblezone 加载条件、本包当前不加载，规则必须是防御性");

        ResourceLocation cola = ResourceLocation.parse("kaleidoscope_world_liquor:cola");
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete) {
                assertFalse(delete.recipeIds().contains(cola), "世界名酒 cola 配方不得被删除");
            }
            if (edit instanceof RecipeEdit.ReplaceResultId replaceResult) {
                assertFalse(replaceResult.from().equals(cola), "世界名酒 cola 产物不得被替换");
            }
        }
    }

    /** 玫瑰石英：Create 三个语言文件的 9 个含旧名的键全部覆盖，且不再残留旧名。 */
    @Test
    void roseQuartzOverridesCoverEveryEnumeratedKey() {
        assertEquals(ROSE_QUARTZ_KEYS, roseQuartzKeys(LanguageOverrides.load("zh_cn")));
        assertEquals(ROSE_QUARTZ_KEYS, roseQuartzKeys(LanguageOverrides.load("zh_tw")));
        assertEquals(ROSE_QUARTZ_KEYS, roseQuartzKeys(LanguageOverrides.load("en_us")));

        Map<String, String> zhCn = LanguageOverrides.load("zh_cn");
        assertEquals("感电石英", zhCn.get("item.create.rose_quartz"));
        assertEquals("磨制感电石英", zhCn.get("item.create.polished_rose_quartz"));
        assertEquals("感电石英块", zhCn.get("block.create.rose_quartz_block"));
        assertEquals("感电石英细瓦", zhCn.get("block.create.small_rose_quartz_tiles"));
        assertEquals("感电石英灯通入红石信号后会处于激活状态",
                zhCn.get("create.ponder.rose_quartz_lamp.text_1"));
        assertEquals("使用砂纸将感电石英打磨至透明", zhCn.get("advancement.create.rose_quartz.desc"));

        Map<String, String> zhTw = LanguageOverrides.load("zh_tw");
        assertEquals("感電石英", zhTw.get("item.create.rose_quartz"));
        assertEquals("拋光感電石英", zhTw.get("item.create.polished_rose_quartz"));
        assertEquals("小感電石英磚瓦", zhTw.get("block.create.small_rose_quartz_tiles"));
        assertEquals("感電石英燈由紅石訊號開啟", zhTw.get("create.ponder.rose_quartz_lamp.text_1"));

        Map<String, String> enUs = LanguageOverrides.load("en_us");
        assertEquals("Charged Quartz", enUs.get("item.create.rose_quartz"));
        assertEquals("Block of Charged Quartz", enUs.get("block.create.rose_quartz_block"));
        assertEquals("Charged Quartz Lamps activate on a Redstone signal",
                enUs.get("create.ponder.rose_quartz_lamp.text_1"));

        for (String code : List.of("zh_cn", "zh_tw", "en_us")) {
            Map<String, String> table = LanguageOverrides.load(code);
            for (String key : ROSE_QUARTZ_KEYS) {
                String value = table.get(key);
                assertNotNull(value, code + " 缺少 " + key);
                assertFalse(value.contains("玫瑰石英"), code + " 的 " + key + " 仍含「玫瑰石英」：" + value);
                assertFalse(value.contains("Rose Quartz"), code + " 的 " + key + " 仍含 Rose Quartz：" + value);
            }
        }
    }

    /** 石磨 / 浆果拼盘 / 冰箱：按用户确认的目标名覆盖。 */
    @Test
    void renamedDuplicateItemsUseConfirmedNames() {
        Map<String, Map<String, String>> expected = new LinkedHashMap<>();
        expected.put("zh_cn", Map.of(
                "block.kaleidoscope_cookery.millstone", "灵魂驴拉磨",
                "emi.category.kaleidoscope_cookery.millstone", "灵魂驴拉磨",
                "config.jade.plugin_kaleidoscope_cookery.millstone", "灵魂驴拉磨",
                BERRY_MEDLEY_KEY, "暮光浆果拼盘",
                "block.cold_sweat.icebox", "冷冻盒",
                "container.cold_sweat.icebox", "冷冻盒"));
        expected.put("zh_tw", Map.of(
                "block.kaleidoscope_cookery.millstone", "靈魂驢拉磨",
                "emi.category.kaleidoscope_cookery.millstone", "靈魂驢拉磨",
                "config.jade.plugin_kaleidoscope_cookery.millstone", "靈魂驢拉磨",
                BERRY_MEDLEY_KEY, "暮光漿果拼盤",
                "block.cold_sweat.icebox", "冷凍盒",
                "container.cold_sweat.icebox", "冷凍盒"));
        expected.put("en_us", Map.of(
                "block.kaleidoscope_cookery.millstone", "Soul Donkey Mill",
                "emi.category.kaleidoscope_cookery.millstone", "Soul Donkey Mill",
                "config.jade.plugin_kaleidoscope_cookery.millstone", "Soul Donkey Mill",
                BERRY_MEDLEY_KEY, "Twilight Berry Platter",
                "block.cold_sweat.icebox", "Freezer Box",
                "container.cold_sweat.icebox", "Freezer Box"));

        for (Map.Entry<String, Map<String, String>> entry : expected.entrySet()) {
            Map<String, String> table = LanguageOverrides.load(entry.getKey());
            for (Map.Entry<String, String> value : entry.getValue().entrySet()) {
                assertEquals(value.getValue(), table.get(value.getKey()),
                        entry.getKey() + " 的 " + value.getKey() + " 覆盖值不符");
            }
        }

        for (String code : List.of("zh_cn", "zh_tw", "en_us")) {
            Map<String, String> table = LanguageOverrides.load(code);
            assertTrue(MILLSTONE_KEYS.stream().allMatch(table::containsKey), code + " 缺少磨石相关的覆盖键");
            assertTrue(ICEBOX_KEYS.stream().allMatch(table::containsKey), code + " 缺少冰箱相关的覆盖键");
            assertTrue(table.containsKey(BERRY_MEDLEY_KEY), code + " 缺少浆果拼盘覆盖键");
        }
    }

    /** 三个语言文件必须键集一致（缺一个键会让该语言回落到英文，静默不一致）。 */
    @Test
    void languageOverrideTablesShareOneKeySet() {
        Set<String> zhCn = LanguageOverrides.load("zh_cn").keySet();
        Set<String> zhTw = LanguageOverrides.load("zh_tw").keySet();
        Set<String> enUs = LanguageOverrides.load("en_us").keySet();
        assertFalse(zhCn.isEmpty(), "zh_cn 覆盖表为空");
        assertEquals(enUs, zhCn, "zh_cn 与 en_us 覆盖键集不一致");
        assertEquals(enUs, zhTw, "zh_tw 与 en_us 覆盖键集不一致");
    }

    /**
     * 显示名覆盖走客户端 mixin，且必须不受「森罗厨房存在才应用」的门控影响：
     * 森罗缺席时 Create / 暮色 / 冷热 的覆盖仍要生效。
     */
    @Test
    void languageOverrideMixinsBypassKaleidoscopeGate() {
        MillstoneRecipeMixinPlugin plugin = new MillstoneRecipeMixinPlugin();
        assertTrue(plugin.shouldApplyMixin("net.minecraft.client.resources.language.LanguageManager",
                "dimblend.craft.mixin.client.LanguageOverrideMixin"),
                "语言覆盖 mixin 不应被森罗门控拦截");
        assertTrue(plugin.shouldApplyMixin("net.minecraft.client.resources.language.I18n",
                "dimblend.craft.mixin.client.I18nAccessor"),
                "I18n invoker 不应被森罗门控拦截");
    }

    /** mixin 配置必须把两个语言覆盖 mixin 登记在 client 段（服务端不加载客户端语言类）。 */
    @Test
    void mixinConfigRegistersLanguageOverridesOnClientSide() throws Exception {
        byte[] bytes = readResource("/dimblend_craft.mixins.json");
        assertNotNull(bytes, "缺少 dimblend_craft.mixins.json");
        JsonObject config = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("dimblend.craft.mixin", config.get("package").getAsString());
        Set<String> client = new LinkedHashSet<>();
        for (JsonElement entry : config.getAsJsonArray("client")) {
            client.add(entry.getAsString());
        }
        assertTrue(client.contains("client.LanguageOverrideMixin"), "client 段缺少语言覆盖 mixin");
        assertTrue(client.contains("client.I18nAccessor"), "client 段缺少 I18n invoker");
    }

    /** 未提供覆盖表的语言回落英文名；已提供语言以英文为基础叠加。 */
    @Test
    void languageOverridesFallBackToEnglish() {
        assertEquals(LanguageOverrides.load("en_us"), LanguageOverrides.forLanguage("ja_jp"),
                "没有对应语言表时应只剩英文覆盖");

        Map<String, String> zhCn = LanguageOverrides.forLanguage("zh_cn");
        assertEquals(LanguageOverrides.load("en_us").size(), zhCn.size(),
                "中文表应覆盖全部英文键（缺键会回落到英文，静默不一致）");
        assertEquals("灵魂驴拉磨", zhCn.get("block.kaleidoscope_cookery.millstone"));
        assertEquals("冷冻盒", zhCn.get("container.cold_sweat.icebox"));
    }

    /** 覆盖表文件必须是 UTF-8、LF、2 空格缩进、末尾无换行（本仓库手写 JSON 的约定）。 */
    @Test
    void languageOverrideFilesAreFormattingClean() throws Exception {
        for (String code : List.of("zh_cn", "zh_tw", "en_us")) {
            byte[] bytes = readResource("/assets/dimblend_craft/lang_overrides/" + code + ".json");
            assertNotNull(bytes, "缺少覆盖表资源 " + code);
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertFalse(text.endsWith("\n"), code + " 覆盖表末尾不得有换行");
            assertFalse(text.contains("\r"), code + " 覆盖表必须使用 LF");
            assertTrue(text.contains("\n  \""), code + " 覆盖表必须使用 2 空格缩进");
        }
    }

    // —— 辅助 ——

    private static Set<String> roseQuartzKeys(Map<String, String> table) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : ROSE_QUARTZ_KEYS) {
            if (table.containsKey(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static RecipeEdit.ReplaceIngredient findIngredientRule(RecipeEdit.IngredientTarget from) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ReplaceIngredient replace && replace.from().equals(from)) {
                return replace;
            }
        }
        return null;
    }

    private static RecipeEdit.ReplaceResultId findResultRule(ResourceLocation from) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ReplaceResultId replace && replace.from().equals(from)) {
                return replace;
            }
        }
        return null;
    }

    private static RecipeEdit.DeleteByRecipeId findDeleteRule(ResourceLocation recipeId) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete && delete.recipeIds().contains(recipeId)) {
                return delete;
            }
        }
        return null;
    }

    private static Set<ResourceLocation> deliberatelyDeleted(List<RecipeEdit> edits) {
        Set<ResourceLocation> deleted = new LinkedHashSet<>();
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete) {
                deleted.addAll(delete.recipeIds());
            }
        }
        return deleted;
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static String resultId(JsonElement recipe) {
        return recipe.getAsJsonObject().getAsJsonObject("result").get("id").getAsString();
    }

    private static int resultCount(JsonElement recipe) {
        return recipe.getAsJsonObject().getAsJsonObject("result").get("count").getAsInt();
    }

    private static String ingredientItem(JsonElement recipe) {
        return recipe.getAsJsonObject().getAsJsonObject("ingredient").get("item").getAsString();
    }

    private static String ingredientTag(JsonElement recipe) {
        return recipe.getAsJsonObject().getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("tag").getAsString();
    }

    private static byte[] readResource(String path) throws Exception {
        try (InputStream in = DuplicateNameRulesTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
