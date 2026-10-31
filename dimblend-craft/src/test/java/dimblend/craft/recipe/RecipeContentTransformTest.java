package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * 通用配方内容替换原语的纯 JVM 自检（2026-10-31 R12 dup-04 / dup-11 / dup-11b）。
 *
 * <p>本类只覆盖 JSON 变换本身：材料按 {@code item} / {@code tag} 精确命中才替换，
 * 产物按 {@code result} / {@code results} / {@code transitional_item} 路径替换且保留数量。
 * 真实配方对象的「编码 → 变换 → 解码」往返由 {@code RecipeJsonEditor} 负责，
 * 单测在纯 JVM 下没有配方注册表，无法构造；那一步由实机 {@code [配方]} 日志确认。</p>
 */
class RecipeContentTransformTest {

    private static final RecipeEdit.IngredientTarget TF_COPPER_NUGGET =
            RecipeEdit.IngredientTarget.item("twilightforest:copper_nugget");
    private static final RecipeEdit.IngredientTarget CREATE_COPPER_NUGGET =
            RecipeEdit.IngredientTarget.item("create:copper_nugget");
    private static final RecipeEdit.IngredientTarget COOKERY_FLOUR =
            RecipeEdit.IngredientTarget.item("kaleidoscope_cookery:flour");
    private static final RecipeEdit.IngredientTarget FLOUR_TAG = RecipeEdit.IngredientTarget.tag("c:flour");
    private static final RecipeEdit.IngredientTarget WHEAT_FLOUR_TAG =
            RecipeEdit.IngredientTarget.tag("c:flours/wheat");

    /** 无序合成：材料列表里重复出现的物品全部被替换，产物侧原样。 */
    @Test
    void ingredientItemIsReplacedEverywhereInList() {
        JsonElement recipe = json("""
                {
                  "type": "minecraft:crafting_shapeless",
                  "ingredients": [
                    {"item": "kaleidoscope_cookery:flour"},
                    {"item": "minecraft:water_bucket"},
                    {"item": "kaleidoscope_cookery:flour"}
                  ],
                  "result": {"count": 2, "id": "kaleidoscope_cookery:raw_dough"}
                }
                """);

        JsonElement transformed = ingredientRule(COOKERY_FLOUR, WHEAT_FLOUR_TAG).transform(recipe);

        assertEquals(json("""
                {
                  "type": "minecraft:crafting_shapeless",
                  "ingredients": [
                    {"tag": "c:flours/wheat"},
                    {"item": "minecraft:water_bucket"},
                    {"tag": "c:flours/wheat"}
                  ],
                  "result": {"count": 2, "id": "kaleidoscope_cookery:raw_dough"}
                }
                """), transformed);
    }

    /** 材料按标签 id 精确命中：{@code c:flour} 标签替换成 {@code c:flours/wheat} 标签。 */
    @Test
    void ingredientTagIsReplacedByTag() {
        JsonElement recipe = json("""
                {
                  "type": "kaleidoscope_cookery:pot",
                  "ingredients": [
                    {"tag": "c:flour"},
                    {"item": "kaleidoscope_end:dream_berry"}
                  ],
                  "result": {"id": "kaleidoscope_end:dragon_souffle"}
                }
                """);

        JsonElement transformed = ingredientRule(FLOUR_TAG, WHEAT_FLOUR_TAG).transform(recipe);

        assertEquals(json("""
                {
                  "type": "kaleidoscope_cookery:pot",
                  "ingredients": [
                    {"tag": "c:flours/wheat"},
                    {"item": "kaleidoscope_end:dream_berry"}
                  ],
                  "result": {"id": "kaleidoscope_end:dragon_souffle"}
                }
                """), transformed);
    }

    /** 有序合成的 key 表同样被覆盖。 */
    @Test
    void ingredientIsReplacedInsideShapedKey() {
        JsonElement recipe = json("""
                {
                  "type": "minecraft:crafting_shaped",
                  "key": {
                    "A": {"item": "minecraft:milk_bucket"},
                    "D": {"tag": "c:flour"}
                  },
                  "pattern": ["ADD"],
                  "result": {"count": 1, "id": "kaleidoscope_twilight:glacier_cake"}
                }
                """);

        JsonElement transformed = ingredientRule(FLOUR_TAG, WHEAT_FLOUR_TAG).transform(recipe);

        assertEquals(json("""
                {
                  "type": "minecraft:crafting_shaped",
                  "key": {
                    "A": {"item": "minecraft:milk_bucket"},
                    "D": {"tag": "c:flours/wheat"}
                  },
                  "pattern": ["ADD"],
                  "result": {"count": 1, "id": "kaleidoscope_twilight:glacier_cake"}
                }
                """), transformed);
    }

    /**
     * 只按精确 id 命中：只是「包含」该物品的标签不动。
     *
     * <p>铜粒规则要求只替换直接写 {@code twilightforest:copper_nugget} 的材料；
     * {@code c:nuggets/copper} 标签同时包含 Create 与暮色的铜粒，把它换成单个物品
     * 会改变其它 mod 的配方语义，必须原样保留。</p>
     */
    @Test
    void tagThatMerelyContainsTheItemIsLeftAlone() {
        JsonElement recipe = json("""
                {
                  "type": "create:mixing",
                  "ingredients": [{"tag": "c:nuggets/copper"}, {"tag": "c:nuggets/zinc"}],
                  "results": [{"id": "create:brass_ingot", "count": 1}]
                }
                """);

        JsonElement transformed = ingredientRule(TF_COPPER_NUGGET, CREATE_COPPER_NUGGET).transform(recipe);

        assertEquals(recipe, transformed, "c:nuggets/copper 标签不应被铜粒规则改写");
    }

    /** 材料规则的替换必须幂等：第二次变换不再产生任何差异。 */
    @Test
    void ingredientReplacementIsIdempotent() {
        JsonElement recipe = json("""
                {"type": "minecraft:crafting_shapeless",
                 "ingredients": [{"item": "kaleidoscope_cookery:flour"}],
                 "result": {"id": "minecraft:dough"}}
                """);
        RecipeEdit.ReplaceIngredient rule = ingredientRule(COOKERY_FLOUR, WHEAT_FLOUR_TAG);

        JsonElement once = rule.transform(recipe);
        JsonElement twice = rule.transform(once);

        assertNotEquals(recipe, once, "第一次变换应产生差异");
        assertEquals(once, twice, "替换规则必须幂等");
    }

    /** 产物 id 替换保留数量：磨石 小麦 => 面粉 x2 改成 Create 小麦面粉 x2。 */
    @Test
    void resultIdIsReplacedAndCountKept() {
        JsonElement recipe = json("""
                {
                  "type": "kaleidoscope_cookery:millstone",
                  "ingredient": {"item": "minecraft:wheat"},
                  "result": {"id": "kaleidoscope_cookery:flour", "count": 2}
                }
                """);

        JsonElement transformed = resultRule("kaleidoscope_cookery:flour", "create:wheat_flour").transform(recipe);

        assertEquals(json("""
                {
                  "type": "kaleidoscope_cookery:millstone",
                  "ingredient": {"item": "minecraft:wheat"},
                  "result": {"id": "create:wheat_flour", "count": 2}
                }
                """), transformed);
    }

    /** 机器配方的 results 数组：只改命中项的 id，数量与其它产物条目保持原样。 */
    @Test
    void resultIdIsReplacedInProcessingResultsArray() {
        JsonElement recipe = json("""
                {
                  "type": "create:crushing",
                  "ingredients": [{"item": "minecraft:stone"}],
                  "results": [
                    {"id": "kaleidoscope_cookery:flour", "count": 3},
                    {"id": "create:wheat_flour", "chance": 0.25}
                  ]
                }
                """);

        JsonElement transformed = resultRule("kaleidoscope_cookery:flour", "create:wheat_flour").transform(recipe);

        assertEquals(json("""
                {
                  "type": "create:crushing",
                  "ingredients": [{"item": "minecraft:stone"}],
                  "results": [
                    {"id": "create:wheat_flour", "count": 3},
                    {"id": "create:wheat_flour", "chance": 0.25}
                  ]
                }
                """), transformed);
    }

    /** 序列组装的过渡产物也要替换（结果池之外唯一带 result 语义的字段）。 */
    @Test
    void resultIdIsReplacedInTransitionalItem() {
        JsonElement recipe = json("""
                {
                  "type": "create:sequenced_assembly",
                  "ingredient": {"item": "minecraft:iron_ingot"},
                  "results": [{"id": "create:precision_mechanism"}],
                  "transitional_item": {"id": "kaleidoscope_cookery:flour"}
                }
                """);

        JsonElement transformed = resultRule("kaleidoscope_cookery:flour", "create:wheat_flour").transform(recipe);

        assertEquals(json("""
                {
                  "type": "create:sequenced_assembly",
                  "ingredient": {"item": "minecraft:iron_ingot"},
                  "results": [{"id": "create:precision_mechanism"}],
                  "transitional_item": {"id": "create:wheat_flour"}
                }
                """), transformed);
    }

    /** 产物规则只走产物路径：材料侧 id 与 custom_data 里的同名字段都不得被改写。 */
    @Test
    void resultReplacementDoesNotTouchIngredientOrCustomData() {
        JsonElement recipe = json("""
                {
                  "type": "minecraft:crafting_shapeless",
                  "ingredients": [{"item": "minecraft:wheat"}],
                  "result": {"id": "create:wheat_flour"},
                  "custom_data": {"id": "kaleidoscope_cookery:flour"}
                }
                """);

        JsonElement transformed = resultRule("kaleidoscope_cookery:flour", "create:wheat_flour").transform(recipe);

        assertEquals(recipe, transformed, "产物规则不得改写材料或自定义数据里的 id");
    }

    /** 产物替换幂等。 */
    @Test
    void resultReplacementIsIdempotent() {
        JsonElement recipe = json("""
                {"type": "minecraft:smelting", "ingredient": {"item": "minecraft:wheat"},
                 "result": {"id": "kaleidoscope_cookery:flour", "count": 1}}
                """);
        RecipeEdit.ReplaceResultId rule = resultRule("kaleidoscope_cookery:flour", "create:wheat_flour");

        JsonElement once = rule.transform(recipe);
        JsonElement twice = rule.transform(once);

        assertNotEquals(recipe, once);
        assertEquals(once, twice, "产物替换规则必须幂等");
    }

    /** 材料规则不得碰产物：产物 id 与数量原样。 */
    @Test
    void ingredientRuleLeavesResultUntouched() {
        JsonElement recipe = json("""
                {"type": "minecraft:crafting_shapeless",
                 "ingredients": [{"item": "kaleidoscope_cookery:flour"}],
                 "result": {"id": "kaleidoscope_cookery:flour", "count": 2}}
                """);

        JsonElement transformed = ingredientRule(COOKERY_FLOUR, WHEAT_FLOUR_TAG).transform(recipe);

        assertEquals(json("""
                {"type": "minecraft:crafting_shapeless",
                 "ingredients": [{"tag": "c:flours/wheat"}],
                 "result": {"id": "kaleidoscope_cookery:flour", "count": 2}}
                """), transformed);
    }

    private static RecipeEdit.ReplaceIngredient ingredientRule(RecipeEdit.IngredientTarget from,
            RecipeEdit.IngredientTarget to) {
        return new RecipeEdit.ReplaceIngredient("测试材料替换", from, to, false);
    }

    private static RecipeEdit.ReplaceResultId resultRule(String from, String to) {
        return new RecipeEdit.ReplaceResultId("测试产物替换",
                net.minecraft.resources.ResourceLocation.parse(from),
                net.minecraft.resources.ResourceLocation.parse(to), false);
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }
}
