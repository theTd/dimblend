package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

/**
 * 规则表自检：规则之间不得互相矛盾，且黄铜两条配方的口径必须与需求一致。
 *
 * <p>背景：2026-10-31 早先一轮把黄铜改成「仅超级加热可产出」，为此删掉了
 * {@code create:mixing/brass_ingot}。同一天用户改口为「保留两者、不改优先度、
 * 普通加热配方改为 8 铜粒 + 8 锌粒 = 1 黄铜锭」，于是删除规则必须撤掉、
 * 改为对该配方的字段改写。本类把这条口径钉死，防止删除规则又被加回来
 * （那种矛盾在运行期只留一条 WARN 日志，游戏里完全看不出异常）。</p>
 */
class RecipeEditRulesTest {

    private static final ResourceLocation HEATED_BRASS = ResourceLocation.parse("create:mixing/brass_ingot");
    private static final ResourceLocation SUPERHEATED_BRASS =
            ResourceLocation.parse("dimblend_craft:brass_ingot_from_superheated_mixing");
    private static final ResourceLocation RAILWAY_CASING = ResourceLocation.parse("create:railway_casing");
    private static final ResourceLocation TRAIN_DOOR = ResourceLocation.parse("create:crafting/kinetics/train_door");
    private static final ResourceLocation TRAIN_TRAPDOOR =
            ResourceLocation.parse("create:crafting/kinetics/train_trapdoor");

    /** 需求（2026-10-31 用户口径）：保留 Create 自带的加热搅拌，不得再删除它。 */
    @Test
    void heatedBrassMixingIsNoLongerDeleted() {
        assertFalse(deliberatelyDeleted(RecipeEditRules.edits()).contains(HEATED_BRASS),
                "create:mixing/brass_ingot 仍被删除规则命中；新口径要求加热版保留");
    }

    /**
     * 需求：普通加热配方改为「8 铜粒 + 8 锌粒 => 1 黄铜锭」。
     *
     * <p>材料用标签而不是写死物品 ID：{@code c:nuggets/copper} 与 {@code c:nuggets/zinc}
     * 由 Create 6.0.10 随包定义（{@code data/c/tags/item/nuggets/copper.json}、
     * {@code nuggets/zinc.json}），暮色森林与 Steam 'n' Rails 分别往这两个标签里
     * 追加了自己的铜粒 / 锌粒，写死 {@code create:copper_nugget} 会把它们排除掉。</p>
     */
    @Test
    void heatedBrassMixingIsRewrittenToEightPlusEightNuggets() {
        RecipeEdit.FieldModify rule = fieldRuleFor(RecipeEditRules.edits(), HEATED_BRASS);
        JsonObject ingredients = ingredientsOf(rule, HEATED_BRASS);

        assertEquals(16, ingredients.getAsJsonArray("__list").size(),
                "规则「" + rule.label() + "」的材料数应为 16（8 铜粒 + 8 锌粒），实际 "
                        + ingredients.getAsJsonArray("__list").size());
        assertEquals(8, countTag(ingredients.getAsJsonArray("__list"), "c:nuggets/copper"),
                "规则「" + rule.label() + "」中 c:nuggets/copper 必须恰好出现 8 次");
        assertEquals(8, countTag(ingredients.getAsJsonArray("__list"), "c:nuggets/zinc"),
                "规则「" + rule.label() + "」中 c:nuggets/zinc 必须恰好出现 8 次");

        JsonElement countElement = rule.fieldEdits().get("results.0.count");
        assertNotNull(countElement, "规则「" + rule.label() + "」未改写 " + HEATED_BRASS + " 的 results.0.count");
        assertEquals(1, countElement.getAsInt(), "加热版产物数量必须改为 1（原版是 2）");

        // 产物 ID 与 chance 不得被重写：只改数量，上游以后给 results 加字段也不会被抹掉。
        assertFalse(rule.fieldEdits().containsKey("results"),
                "规则「" + rule.label() + "」不应整段重写 results（只改 results.0.count）");
        assertFalse(rule.fieldEdits().containsKey("results.0.id"),
                "规则「" + rule.label() + "」不应改写产物 ID，加热版产物仍应是 create:brass_ingot");

        // 需求说的是「普通加热配方」，热度档必须保持上游原来的 heated，不得改成 superheated。
        assertFalse(rule.fieldEdits().containsKey("heat_requirement"),
                "规则「" + rule.label() + "」不应改写 heat_requirement（加热版必须仍是普通加热）");
    }

    /** 需求：不更改优先度 —— 黄铜两条配方都不得被排序规则移动。 */
    @Test
    void brassPriorityIsNotChanged() {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.MoveRecipeBefore move) {
                assertFalse(move.recipeId().equals(HEATED_BRASS), "不得给加热版黄铜加排序规则（需求：不改优先度）");
                assertFalse(move.anchorId().equals(HEATED_BRASS), "不得把加热版黄铜当排序锚点（需求：不改优先度）");
                assertFalse(move.recipeId().equals(SUPERHEATED_BRASS), "超热黄铜配方不应再依赖排序决定命中");
                assertFalse(move.anchorId().equals(SUPERHEATED_BRASS), "超热黄铜配方不应被当作排序锚点");
            }
        }
    }

    /** 硬不变量：一条配方不允许既被显式删除、又被别的规则改写或当作排序锚点。 */
    @Test
    void noRuleTouchesARecipeAnotherRuleDeletes() {
        List<RecipeEdit> edits = RecipeEditRules.edits();
        Set<ResourceLocation> deleted = deliberatelyDeleted(edits);
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.ModifyRecipeFields fields) {
                for (ResourceLocation id : fields.recipeIds()) {
                    assertFalse(deleted.contains(id), "规则「" + fields.label() + "」改写的配方 " + id
                            + " 已被另一条规则删除，该改写永远空转");
                }
            }
            if (edit instanceof RecipeEdit.MoveRecipeBefore move) {
                assertFalse(deleted.contains(move.recipeId()), "排序规则「" + move.label() + "」的目标 "
                        + move.recipeId() + " 已被删除，排序永远无法应用");
                assertFalse(deleted.contains(move.anchorId()), "排序规则「" + move.label() + "」的锚点 "
                        + move.anchorId() + " 已被删除，排序永远无法应用");
            }
        }
    }

    /** 超热黄铜配方保持原样：随包数据仍声明超级加热与 x2，且不被任何规则改写。 */
    @Test
    void superheatedBrassRecipeIsStandaloneAndStillSuperheated() throws Exception {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ModifyRecipeFields fields) {
                assertFalse(fields.recipeIds().contains(SUPERHEATED_BRASS), "超热黄铜配方不应被字段规则改写");
            }
            if (edit instanceof RecipeEdit.MoveRecipeBefore move) {
                assertFalse(move.recipeId().equals(SUPERHEATED_BRASS), "超热黄铜配方不应再依赖排序决定命中");
            }
        }
        JsonObject json = packResource("data/dimblend_craft/recipe/brass_ingot_from_superheated_mixing.json");
        assertEquals("create:mixing", json.get("type").getAsString());
        assertEquals("superheated", json.get("heat_requirement").getAsString());
        JsonObject result = json.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("create:brass_ingot", result.get("id").getAsString());
        assertEquals(2, result.get("count").getAsInt(), "超热版维持 x2，加热版改口径不改变超热版产量");
    }

    /**
     * 需求 R10（2026-10-31）：恢复列车门 / 列车活板门两条配方。
     *
     * <p>两条配方都以 {@code create:railway_casing} 为原料，原本被「列车机壳参与的配方」
     * 整体删除；规则改为按配方 ID 豁免这两条。重载阶段
     * （{@link RecipeEditRules#ingredientEdits()}）与服务器启动后阶段
     * （{@link RecipeEditRules#lateEdits()}）各有一条同一规则，两处必须带同一份豁免集合，
     * 否则启动后阶段会把重载阶段刚保留的配方再删掉。</p>
     */
    @Test
    void trainDoorRecipesAreTheOnlyCasingDeletionExemptions() {
        for (List<RecipeEdit> edits : List.of(RecipeEditRules.ingredientEdits(), RecipeEditRules.lateEdits())) {
            RecipeEdit.DeleteByIngredientId rule = casingRule(edits);
            assertEquals(Set.of(TRAIN_DOOR, TRAIN_TRAPDOOR), rule.exemptRecipeIds(),
                    "机壳原料删除规则只允许豁免列车门与列车活板门（规则：" + rule.label() + "）");
        }
    }

    /**
     * 豁免之外，其余以机壳为原料的配方仍必须被删除。
     *
     * <p>2026-10-31 按整合包 {@code TrainTripWorld/mods} 的配方文件逐 jar 枚举：
     * 以 {@code create:railway_casing} 为原料的配方共 9 条 —— Create 6.0.10 七条
     * （controls、track_observer、track_observer_from_other_plates、track_signal、
     * track_station、train_door、train_trapdoor），Steam 'n' Rails 0.2.0-beta.2 两条
     * （portable_fuel_interface、track_coupler）。豁免 train_door / train_trapdoor 后，
     * 下面 7 条必须继续被原料规则命中。规则按真实 {@code Ingredient} 匹配原料，
     * 本单测在纯 JVM 下无法构造配方注册表，因此只断言「这 7 条不在豁免集合内」；
     * 实际删除由 {@code RecipeInputMatcher} 保证，需在游戏内 {@code [配方]} 日志确认。</p>
     */
    @Test
    void otherCasingRecipesAreNotExemptFromDeletion() {
        Set<ResourceLocation> stillDeleted = Set.of(
                ResourceLocation.parse("create:crafting/kinetics/controls"),
                ResourceLocation.parse("create:crafting/kinetics/track_observer"),
                ResourceLocation.parse("create:crafting/kinetics/track_observer_from_other_plates"),
                ResourceLocation.parse("create:crafting/kinetics/track_signal"),
                ResourceLocation.parse("create:crafting/kinetics/track_station"),
                ResourceLocation.parse("railways:crafting/portable_fuel_interface"),
                ResourceLocation.parse("railways:crafting/track_coupler"));
        assertEquals(7, stillDeleted.size(), "枚举的未豁免机壳配方应为 7 条");
        RecipeEdit.DeleteByIngredientId rule = casingRule(RecipeEditRules.ingredientEdits());
        for (ResourceLocation id : stillDeleted) {
            assertFalse(rule.isExempt(id), "配方 " + id + " 被误加进豁免集合，机壳原料删除对它失效");
        }
    }

    /** 取出命中给定配方 ID 的字段改写规则；不存在即断言失败。 */
    private static RecipeEdit.FieldModify fieldRuleFor(List<RecipeEdit> edits, ResourceLocation recipeId) {
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.FieldModify modify && modify.matchesId(recipeId)) {
                return modify;
            }
        }
        throw new AssertionError("规则表中缺少对 " + recipeId + " 的字段改写规则");
    }

    /**
     * 从规则的 {@code ingredients} 字段编辑里取出材料数组，并以 {@code __list} 键包一层
     * 返回（避免调用方对 {@link JsonElement} 直接做数组断言）。
     */
    private static JsonObject ingredientsOf(RecipeEdit.FieldModify rule, ResourceLocation recipeId) {
        JsonElement raw = rule.fieldEdits().get("ingredients");
        assertNotNull(raw, "规则「" + rule.label() + "」未改写 " + recipeId + " 的 ingredients 字段");
        assertTrue(raw.isJsonArray(), "规则「" + rule.label() + "」的 ingredients 必须是 JSON 数组");
        JsonObject wrapper = new JsonObject();
        wrapper.add("__list", raw);
        return wrapper;
    }

    private static JsonArray resultsOf(RecipeEdit.FieldModify rule, ResourceLocation recipeId) {
        JsonElement raw = rule.fieldEdits().get("results");
        assertNotNull(raw, "规则「" + rule.label() + "」需要整段改写 " + recipeId + " 的 results 字段"
                + "（只改 results.0.count 依赖上游编码时是否写出该可省略字段，不稳妥）");
        assertTrue(raw.isJsonArray(), "规则「" + rule.label() + "」的 results 必须是 JSON 数组");
        return raw.getAsJsonArray();
    }

    private static int countTag(JsonArray ingredients, String tag) {
        int found = 0;
        for (JsonElement element : ingredients) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement value = element.getAsJsonObject().get("tag");
            if (value != null && tag.equals(value.getAsString())) {
                found++;
            }
        }
        return found;
    }

    private static RecipeEdit.DeleteByIngredientId casingRule(List<RecipeEdit> edits) {
        for (RecipeEdit edit : edits) {
            if (edit instanceof RecipeEdit.DeleteByIngredientId delete
                    && delete.ingredientId().equals(RAILWAY_CASING)) {
                return delete;
            }
        }
        throw new AssertionError("规则表中缺少以 " + RAILWAY_CASING + " 为原料的删除规则");
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

    private static JsonObject packResource(String path) throws Exception {
        try (InputStream in = RecipeEditRulesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少随包配方资源：" + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}