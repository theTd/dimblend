package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>2026-11-03 批次 M1 / T-01 起，本类还覆盖五条新增运行时规则（拆解台特殊配方、
 * 森罗生面团、疣猪兽皮、末影袋、经验泵），并禁止把末影珍珠 / 末影之眼写成全局材料替换。</p>
 */
class RecipeEditRulesTest {

    private static final ResourceLocation HEATED_BRASS = ResourceLocation.parse("create:mixing/brass_ingot");
    private static final ResourceLocation SUPERHEATED_BRASS =
            ResourceLocation.parse("dimblend_craft:brass_ingot_from_superheated_mixing");
    private static final ResourceLocation RAILWAY_CASING = ResourceLocation.parse("create:railway_casing");
    private static final ResourceLocation TRAIN_DOOR = ResourceLocation.parse("create:crafting/kinetics/train_door");
    private static final ResourceLocation TRAIN_TRAPDOOR =
            ResourceLocation.parse("create:crafting/kinetics/train_trapdoor");
    private static final ResourceLocation TRAIN_CONTROLS =
            ResourceLocation.parse("create:crafting/kinetics/controls");

    // —— 2026-11-03 批次 M1 / T-01 的目标 ——

    private static final Set<ResourceLocation> TWILIGHT_UNCRAFTING_SPECIAL = Set.of(
            ResourceLocation.parse("twilightforest:uncrafting/tipped_arrow"),
            ResourceLocation.parse("twilightforest:uncrafting/written_book"),
            ResourceLocation.parse("twilightforest:relinquish_keepsakes"));
    private static final ResourceLocation RAW_DOUGH = ResourceLocation.parse("kaleidoscope_cookery:raw_dough");
    private static final ResourceLocation HOGLIN_HIDE_SPICY =
            ResourceLocation.parse("kaleidoscope_nether:hoglin_hide");
    private static final ResourceLocation HOGLIN_HIDE_COLD = ResourceLocation.parse("cold_sweat:hoglin_hide");
    private static final ResourceLocation ENDER_POUCH = ResourceLocation.parse("enderstorage:ender_pouch");
    private static final ResourceLocation XP_PUMP_UPGRADE =
            ResourceLocation.parse("sophisticatedbackpacks:xp_pump_upgrade");
    private static final ResourceLocation ENDER_EYE = ResourceLocation.parse("minecraft:ender_eye");
    private static final ResourceLocation ENDER_PEARLS = ResourceLocation.parse("c:ender_pearls");
    private static final ResourceLocation VOID_CORE = ResourceLocation.parse("voidscape:machine_core");

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
            assertEquals(Set.of(TRAIN_DOOR, TRAIN_TRAPDOOR, TRAIN_CONTROLS), rule.exemptRecipeIds(),
                    "机壳原料删除规则只豁免列车门、列车活板门与列车驾驶台（规则：" + rule.label() + "）");
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
                ResourceLocation.parse("create:crafting/kinetics/track_observer"),
                ResourceLocation.parse("create:crafting/kinetics/track_observer_from_other_plates"),
                ResourceLocation.parse("create:crafting/kinetics/track_signal"),
                ResourceLocation.parse("create:crafting/kinetics/track_station"),
                ResourceLocation.parse("railways:crafting/portable_fuel_interface"),
                ResourceLocation.parse("railways:crafting/track_coupler"));
        assertEquals(6, stillDeleted.size(), "枚举的未豁免机壳配方应为 6 条");
        RecipeEdit.DeleteByIngredientId rule = casingRule(RecipeEditRules.ingredientEdits());
        for (ResourceLocation id : stillDeleted) {
            assertFalse(rule.isExempt(id), "配方 " + id + " 被误加进豁免集合，机壳原料删除对它失效");
        }
    }

    // —— 2026-11-03 批次 M1 / T-01：五条运行时配方规则 ——

    /** DoD 1：暮色拆解台的 3 条特殊拆解配方按配方 id 删除。 */
    @Test
    void twilightUncraftingSpecialRecipesAreDeletedById() {
        RecipeEdit.DeleteByRecipeId rule = deleteRuleByLabel("暮色拆解台特殊配方");
        assertNotNull(rule, "缺少规则「暮色拆解台特殊配方」");
        assertEquals(TWILIGHT_UNCRAFTING_SPECIAL, rule.recipeIds(),
                "规则「" + rule.label() + "」应删除 3 条特殊拆解配方，实际 " + rule.recipeIds());
        assertFalse(rule.defensive(), "三条配方今天都随暮色森林 4.8.3345 提供，不属于防御性规则");
    }

    /**
     * DoD 2：森罗生面团按产物删除。
     *
     * <p>8 条 {@code minecraft:flour_from_*_wheat} 都是 {@code minecraft:crafting_shapeless}，
     * 走 {@link RecipeEdit.DeleteByResultId} 的默认合成台通道即可，不需要额外指定配方类型。</p>
     */
    @Test
    void rawDoughRecipesAreDeletedByResult() {
        RecipeEdit.DeleteByResultId rule = resultDeleteRuleByLabel("森罗生面团");
        assertNotNull(rule, "缺少规则「森罗生面团」");
        assertEquals(Set.of(RAW_DOUGH), rule.resultIds().exactIds(),
                "规则「" + rule.label() + "」只删 kaleidoscope_cookery:raw_dough"
                        + "（create:dough 是「面团」，不在需求内），实际 " + rule.resultIds().exactIds());
        assertTrue(rule.resultIds().idPrefixes().isEmpty(), "产物删除必须按精确 ID，不能用前缀");
        assertTrue(rule.recipeTypeIds().isEmpty(),
                "8 条 flour_from_* 都是 crafting_shapeless，默认合成台通道已覆盖，不应额外指定配方类型");
        assertFalse(rule.defensive(), "8 条配方今天都存在，不属于防御性规则");
    }

    /**
     * DoD 3：材料里精确写成 {@code kaleidoscope_nether:hoglin_hide} 的替换为
     * {@code cold_sweat:hoglin_hide}。
     *
     * <p>5 条配方（皮革转换、石磨、三口锅）今天都直接写物品 ID，不属于防御性规则；
     * 替换是内容级变换，与配方类型无关，这里用无序合成与森罗锅两种真实写法各验一次。</p>
     */
    @Test
    void hoglinHideIsReplacedWithColdSweatHide() {
        RecipeEdit.ReplaceIngredient rule =
                ingredientRuleFrom(RecipeEdit.IngredientTarget.item(HOGLIN_HIDE_SPICY.toString()));
        assertNotNull(rule, "缺少 kaleidoscope_nether:hoglin_hide 的材料替换规则");
        assertEquals("森罗疣猪兽皮改冷汗疣猪兽皮", rule.label(), "规则名必须与 DoD 一致");
        assertEquals(RecipeEdit.IngredientTarget.item(HOGLIN_HIDE_COLD.toString()), rule.to(),
                "材料必须替换成 cold_sweat:hoglin_hide");
        assertFalse(rule.defensive(), "5 条配方今天都用它当材料，不属于防御性规则");

        // 真实写法 1：hoglin_hide_to_leather（minecraft:crafting_shapeless，材料只写物品 ID）。
        JsonElement shapeless = rule.transform(json("""
                {"type":"minecraft:crafting_shapeless",
                 "ingredients":[{"item":"kaleidoscope_nether:hoglin_hide"}],
                 "result":{"id":"minecraft:leather","count":3}}
                """));
        assertEquals("cold_sweat:hoglin_hide", firstIngredientItem(shapeless),
                "无序合成 hoglin_hide_to_leather 的材料未替换");
        assertEquals(3, shapeless.getAsJsonObject().getAsJsonObject("result").get("count").getAsInt(),
                "产物与数量必须原样");

        // 真实写法 2：森罗锅 pot（自定义类型，材料列表里混着水桶）。
        JsonElement pot = rule.transform(json("""
                {"type":"kaleidoscope_cookery:pot",
                 "ingredients":[{"item":"minecraft:water_bucket"},{"item":"kaleidoscope_nether:hoglin_hide"}],
                 "result":{"id":"kaleidoscope_cookery:oil","count":4},"time":200}
                """));
        assertEquals("cold_sweat:hoglin_hide",
                pot.getAsJsonObject().getAsJsonArray("ingredients").get(1).getAsJsonObject()
                        .get("item").getAsString(),
                "森罗锅配方（自定义类型）的材料未替换：替换必须与配方类型无关");
        assertEquals("minecraft:water_bucket",
                pot.getAsJsonObject().getAsJsonArray("ingredients").get(0).getAsJsonObject()
                        .get("item").getAsString(),
                "同一配方里的其它材料不得被改写");
        assertSame(pot, rule.transform(pot), "材料替换必须幂等：第二次应用不得再产生变化");
    }

    /**
     * 用户 2026-11-04：删除「疣猪兽皮 => 皮革」的两个配方。
     *
     * <p>全包扫描（所有 mod jar 的 recipe JSON）确认「材料含 hoglin_hide 且产物含 leather」
     * 的配方正好两条，与用户说的「两个」一致：合成台无序合成的
     * {@code hoglin_hide_to_leather}（1 皮 → 3 皮革）与石磨的
     * {@code millstone/hoglin_hide}（1 皮 → 5 皮革）。恶魂皮那条不在此列——那是恶魂皮。</p>
     */
    @Test
    void hoglinHideToLeatherRecipesAreDeletedById() {
        RecipeEdit.DeleteByRecipeId rule = deleteRuleByLabel("疣猪兽皮制皮革");
        assertNotNull(rule, "缺少规则「疣猪兽皮制皮革」");
        assertEquals(Set.of(ResourceLocation.parse("kaleidoscope_nether:hoglin_hide_to_leather"),
                ResourceLocation.parse("kaleidoscope_nether:millstone/hoglin_hide")), rule.recipeIds(),
                "必须正好删掉这两个把疣猪兽皮变成皮革的配方，实际 " + rule.recipeIds());
        assertFalse(rule.defensive(), "两条配方随森罗下界 1.1.2 提供且实测存在，不属于防御性规则");
        for (ResourceLocation id : rule.recipeIds()) {
            assertFalse(id.getPath().contains("ghast"),
                    "恶魂皮配方不得被误删：" + id);
        }
    }

    /**
     * 用户 2026-11-04：所有森罗生面团参与的配方改为 create 面团。
     *
     * <p>生产侧 8 条已在 3.18.2 删掉，所以生面团此后不可获得；还引用它的配方只有蒸笼一条，
     * 而它的四个材料**和**产物里的方块实体数据都写死了生面团。这条测试钉住两件事：
     * 材料改了、蒸笼里预装的东西也改了（正是 {@code ReplaceIngredient} 不覆盖的那部分），
     * 同时不能误改方块实体**类型** id 或显示模型 id。</p>
     */
    @Test
    void rawDoughIsUnifiedIntoCreateDoughIncludingSteamerContents() {
        RecipeEdit.ReplaceItemIdDeep rule = deepIdRule("森罗生面团换 create 面团");
        assertNotNull(rule, "缺少规则「森罗生面团换 create 面团」");
        assertEquals(RAW_DOUGH, rule.from());
        assertEquals(ResourceLocation.parse("create:dough"), rule.to(),
                "森罗生面团必须整体换成 create 面团");
        assertFalse(rule.defensive(), "蒸笼配方今天存在，不属于防御性规则");

        // 真实写法：kaleidoscope_cookery:raw_dough_steamer（crafting_shapeless，
        // 4 个生面团 + 蒸笼；产物把 4 个生面团预装进 block_entity_data）。
        JsonElement steamer = rule.transform(json("""
                {"type":"minecraft:crafting_shapeless",
                 "ingredients":[{"item":"kaleidoscope_cookery:steamer"},
                   {"item":"kaleidoscope_cookery:raw_dough"},{"item":"kaleidoscope_cookery:raw_dough"},
                   {"item":"kaleidoscope_cookery:raw_dough"},{"item":"kaleidoscope_cookery:raw_dough"}],
                 "result":{"id":"kaleidoscope_cookery:steamer","count":1,
                   "components":{"minecraft:block_entity_data":{
                     "id":"kaleidoscope_cookery:steamer",
                     "Items":[{"id":"kaleidoscope_cookery:raw_dough","Slot":0,"Count":1},
                              {"id":"kaleidoscope_cookery:raw_dough","Slot":1,"Count":1}]}}}}
                """));
        JsonObject out = steamer.getAsJsonObject();
        JsonArray ingredients = out.getAsJsonArray("ingredients");
        for (int i = 1; i <= 4; i++) {
            assertEquals("create:dough", ingredients.get(i).getAsJsonObject().get("item").getAsString(),
                    "第 " + i + " 个材料必须换成 create 面团");
        }
        assertEquals("kaleidoscope_cookery:steamer",
                ingredients.get(0).getAsJsonObject().get("item").getAsString(),
                "蒸笼本体不是生面团，不得被改写");

        JsonObject beData = out.getAsJsonObject("result").getAsJsonObject("components")
                .getAsJsonObject("minecraft:block_entity_data");
        JsonArray items = beData.getAsJsonArray("Items");
        for (int i = 0; i < items.size(); i++) {
            assertEquals("create:dough", items.get(i).getAsJsonObject().get("id").getAsString(),
                    "蒸笼预装的第 " + i + " 格必须换成 create 面团，否则玩家会拿到装着"
                            + "已不可获得物品的蒸笼");
        }
        assertEquals("kaleidoscope_cookery:steamer", beData.get("id").getAsString(),
                "方块实体类型 id 不得被改写（那是 BE 类型，不是物品）");

        assertSame(steamer, rule.transform(steamer), "深度替换必须幂等：第二次应用不得再产生变化");
    }

    /**
     * DoD 4：末影袋只改 {@code key.P} 字段，禁止全局替换 {@code #c:ender_pearls}。
     *
     * <p>配方取自 EnderStorage-1.21.1-2.13.0.191 的
     * {@code data/enderstorage/recipe/ender_pouch.json}：类型 {@code enderstorage:create_recipe}，
     * pattern {@code BLB/LPL/BWB}，{@code key.P} 是 {@code {"tag":"c:ender_pearls"}}。</p>
     */
    @Test
    void enderPouchPearlSlotIsRewrittenToVoidCore() {
        RecipeEdit.ModifyRecipeFields rule = fieldRuleById(ENDER_POUCH);
        assertNotNull(rule, "缺少对 enderstorage:ender_pouch 的字段改写规则");
        assertEquals("末影袋珍珠改虚空核心", rule.label(), "规则名必须与 DoD 一致");
        assertEquals(Set.of(ENDER_POUCH), rule.recipeIds(),
                "规则只能命中 enderstorage:ender_pouch 一条配方，实际 " + rule.recipeIds());
        assertEquals(1, rule.fieldEdits().size(),
                "只允许改 key.P 一个字段，实际改写字段 " + rule.fieldEdits().keySet());
        JsonElement pearlSlot = rule.fieldEdits().get("key.P");
        assertNotNull(pearlSlot, "缺少 key.P 字段改写");
        assertTrue(pearlSlot.isJsonObject(), "key.P 必须写成材料对象");
        assertEquals(VOID_CORE.toString(), pearlSlot.getAsJsonObject().get("item").getAsString(),
                "key.P 必须改成 voidscape:machine_core");
        assertFalse(pearlSlot.getAsJsonObject().has("tag"), "key.P 只能写 item，不得同时保留 tag");
    }

    /**
     * DoD 5：经验泵只改槽位 2 与 8，禁止全局替换 {@code minecraft:ender_eye}。
     *
     * <p>配方取自 sophisticatedbackpacks-1.21.1-3.25.44.1736 的
     * {@code data/sophisticatedbackpacks/recipe/xp_pump_upgrade.json}：
     * {@code minecraft:crafting_shaped}，pattern {@code RER/CPC/RER}，
     * {@code key.E = minecraft:ender_eye}，按行优先落在槽位 2 与 8。</p>
     */
    @Test
    void xpPumpEnderEyeSlotsAreRewrittenToVoidCore() {
        RecipeEdit.ModifyShapedGrid rule = gridRuleByResult(XP_PUMP_UPGRADE);
        assertNotNull(rule, "缺少产物为 sophisticatedbackpacks:xp_pump_upgrade 的网格改写规则");
        assertEquals("经验泵末影之眼改虚空核心", rule.label(), "规则名必须与 DoD 一致");
        assertEquals(Set.of(XP_PUMP_UPGRADE), rule.resultTargets(),
                "规则只能命中经验泵升级这一条配方，实际 " + rule.resultTargets());
        assertEquals(Set.of(2, 8), rule.slotEdits().keySet(),
                "RER/CPC/RER 中 E 位于行优先槽位 2 与 8，实际 " + rule.slotEdits().keySet());
        for (int slot : List.of(2, 8)) {
            assertEquals(RecipeIngredient.item(VOID_CORE.toString()), rule.slotEdits().get(slot),
                    "槽位 " + slot + " 必须改成 voidscape:machine_core");
        }
        assertNull(rule.newResultCount(), "只换材料，不得改产物数量");
    }

    /**
     * DoD 6 的后半句：末影珍珠与末影之眼不得走全局材料替换。
     *
     * <p>全局 {@code ReplaceIngredient} 会重写全包所有命中配方（末影珍珠是常见材料），
     * 这里对三个规则阶段一起扫描，防止以后有人把「只改指定配方」偷懒换成全局替换。</p>
     */
    @Test
    void noGlobalReplacementTargetsEnderPearlsOrEnderEyes() {
        List<RecipeEdit.IngredientTarget> forbidden = List.of(
                RecipeEdit.IngredientTarget.item(ENDER_EYE.toString()),
                RecipeEdit.IngredientTarget.tag(ENDER_PEARLS.toString()));
        for (List<RecipeEdit> edits : List.of(RecipeEditRules.edits(),
                RecipeEditRules.ingredientEdits(), RecipeEditRules.lateEdits())) {
            for (RecipeEdit edit : edits) {
                if (edit instanceof RecipeEdit.ReplaceIngredient replace) {
                    assertFalse(forbidden.contains(replace.from()),
                            "规则「" + replace.label() + "」用全局材料替换命中 " + replace.from().describe()
                                    + "：必须改为只改 enderstorage:ender_pouch 的 key.P 或经验泵的槽位 2/8");
                }
            }
        }
    }

    // —— 2026-11-11 过载离合器加重深银锭 ——

    private static final ResourceLocation CC_OVERSTRESS_CLUTCH =
            ResourceLocation.parse("create_connected:overstress_clutch");
    private static final ResourceLocation DEEPSILVER_INGOT =
            ResourceLocation.parse("eternal_starlight:deepsilver_ingot");

    /**
     * 需求（2026-11-11）：过载离合器的无序合成再加 2 个深银锭。
     *
     * <p>无序合成没有槽位，只能整条重写；上游配方被
     * {@code create_connected:feature_enabled} 条件包住，随包配方因此自带三条
     * {@code neoforge:mod_loaded}——Create / Create Connected / Eternal Starlight
     * 缺一则整条不加载，不会留下"要深银锭却没有深银锭"的死配方。</p>
     */
    @Test
    void overstressClutchReplacementAddsTwoDeepSilverIngots() throws Exception {
        JsonObject recipe = packResource(
                "data/dimblend_craft/recipe/overstress_clutch_from_deepsilver.json");
        assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString(),
                "上游 overstress_clutch 是无序合成，替换配方必须同为 shapeless，"
                        + "否则材料会被当成图案解析");

        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        assertEquals(6, ingredients.size(),
                "原配方 4 个材料 + 深银锭 ×2 = 6；数量不对说明加重没落到配方里");
        assertEquals(2, countItem(ingredients, DEEPSILVER_INGOT.toString()),
                "深银锭必须恰好 2 个");
        for (String original : List.of("create:andesite_casing", "create:shaft",
                "create:iron_sheet", "create:electron_tube")) {
            assertEquals(1, countItem(ingredients, original),
                    "上游材料 " + original + " 必须保留且只出现一次");
        }

        JsonObject result = recipe.getAsJsonObject("result");
        assertEquals(CC_OVERSTRESS_CLUTCH.toString(), result.get("id").getAsString(),
                "产物必须是 create_connected:overstress_clutch");
        assertEquals(1, result.get("count").getAsInt(), "产量沿用上游 1");

        // 三个 mod_loaded 条件缺一不可：任何一个来源缺席时整条配方都不该加载。
        Set<String> requiredMods = new HashSet<>();
        for (JsonElement condition : recipe.getAsJsonArray("neoforge:conditions")) {
            JsonObject object = condition.getAsJsonObject();
            if ("neoforge:mod_loaded".equals(object.get("type").getAsString())) {
                requiredMods.add(object.get("modid").getAsString());
            }
        }
        assertEquals(Set.of("create", "create_connected", "eternal_starlight"), requiredMods,
                "随包替换配方必须同时要求 Create、Create Connected 与永恒星光，实际 " + requiredMods);
    }

    /**
     * 原配方必须被删掉，否则游戏里会同时存在两条产物相同的无序合成，
     * 配方书与自动合成会按遍历序随机命中其中一条（材料要求时有时无）。
     */
    @Test
    void upstreamOverstressClutchRecipeIsRemovedSoOnlyOneRemains() {
        RecipeEdit.DeleteByRecipeId rule = deleteRuleByLabel("过载离合器原配方（改由随包配方加重深银锭）");
        assertNotNull(rule, "缺少删除 create_connected 原过载离合器配方的规则");
        assertEquals(Set.of(ResourceLocation.parse("create_connected:crafting/kinetics/overstress_clutch")),
                rule.recipeIds(), "只能删上游那一条配方，实际 " + rule.recipeIds());
        assertTrue(rule.defensive(),
                "该配方被 create_connected:feature_enabled 条件包住，feature 关闭时不加载，"
                        + "未命中属预期，必须标防御性（否则日志刷假 WARN）");
    }

    /** 无序合成的材料计数（按 item 字段精确匹配）。 */
    private static int countItem(JsonArray ingredients, String item) {
        int found = 0;
        for (JsonElement element : ingredients) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement value = element.getAsJsonObject().get("item");
            if (value != null && item.equals(value.getAsString())) {
                found++;
            }
        }
        return found;
    }

    // —— 2026-11-11 EnderStorage 标准合成 ——
    //
    // fixture 逐字取自整合包 EnderStorage-1.21.1-2.13.0.191.jar 的
    // data/enderstorage/recipe/*.json（只做缩进整理，字段与值未改动）。
    // 真实配方由 codechicken.enderstorage.recipe.CreateRecipe extends ShapedRecipe 承载，
    // serializer 的 Codec 是 ShapedRecipePattern.MAP_CODEC，所以「材料」在 JSON 里是
    // key 符号表，pattern 只是布局。单测通过反射调用生产 RecipeJsonEditor.writePath 把规则
    // 字段编辑写入 fixture 后按 pattern 展开，断言槽位；Codec 往返本身仍只能实机验证。

    private static final ResourceLocation ENDER_TANK = ResourceLocation.parse("enderstorage:ender_tank");
    private static final ResourceLocation ENDER_CHEST = ResourceLocation.parse("enderstorage:ender_chest");
    private static final ResourceLocation ENDER_TANK_RECOLOUR =
            ResourceLocation.parse("enderstorage:recolour_ender_tank");
    private static final ResourceLocation ENDER_CHEST_RECOLOUR =
            ResourceLocation.parse("enderstorage:recolour_ender_chest");

    private static final String ENDER_TANK_JSON = """
            {
              "type": "enderstorage:create_recipe",
              "key": {
                "B": { "item": "minecraft:blaze_rod" },
                "C": { "item": "minecraft:cauldron" },
                "O": { "tag": "c:obsidians" },
                "P": { "tag": "c:ender_pearls" },
                "W": { "tag": "c:wools" }
              },
              "pattern": [ "BWB", "OCO", "BPB" ],
              "result": { "count": 1, "id": "enderstorage:ender_tank" }
            }
            """;

    private static final String ENDER_CHEST_JSON = """
            {
              "type": "enderstorage:create_recipe",
              "key": {
                "B": { "item": "minecraft:blaze_rod" },
                "C": { "tag": "c:chests/wooden" },
                "O": { "tag": "c:obsidians" },
                "P": { "tag": "c:ender_pearls" },
                "W": { "tag": "c:wools" }
              },
              "pattern": [ "BWB", "OCO", "BPB" ],
              "result": { "count": 1, "id": "enderstorage:ender_chest" }
            }
            """;

    private static final String ENDER_TANK_RECOLOUR_JSON = """
            {
              "type": "enderstorage:recolour_recipe",
              "result": { "count": 1, "id": "enderstorage:ender_tank" }
            }
            """;

    private static final String ENDER_CHEST_RECOLOUR_JSON = """
            {
              "type": "enderstorage:recolour_recipe",
              "result": { "count": 1, "id": "enderstorage:ender_chest" }
            }
            """;

    /**
     * 2026-11-11：末影水缸只把末影珍珠位（key.P）换成虚空核心，四角烈焰棒保留。
     *
     * <p>pattern {@code BWB/OCO/BPB} 中 P 只在第三行中间出现一次；B 在四个角。
     * 断言按 3×3 行优先展开后的每个槽位，确认改的是珍珠位而不是四角。</p>
     */
    @Test
    void enderTankPearlSlotIsRewrittenToVoidCoreKeepingBlazeRodCorners() {
        RecipeEdit.ModifyRecipeFields rule = fieldRuleById(ENDER_TANK);
        assertNotNull(rule, "缺少对 enderstorage:ender_tank 的字段改写规则");
        assertEquals(Set.of(ENDER_TANK), rule.recipeIds(), "规则必须只命中 enderstorage:ender_tank");

        JsonObject fixture = json(ENDER_TANK_JSON).getAsJsonObject();
        assertEquals(List.of(
                "minecraft:blaze_rod", "#c:wools", "minecraft:blaze_rod",
                "#c:obsidians", "minecraft:cauldron", "#c:obsidians",
                "minecraft:blaze_rod", "#c:ender_pearls", "minecraft:blaze_rod"),
                expandGrid(fixture), "fixture 与 EnderStorage 2.13.0.191 的 ender_tank.json 不一致");

        JsonObject edited = applyFieldEdits(fixture, rule.fieldEdits());
        assertEquals(List.of(
                "minecraft:blaze_rod", "#c:wools", "minecraft:blaze_rod",
                "#c:obsidians", "minecraft:cauldron", "#c:obsidians",
                "minecraft:blaze_rod", "voidscape:machine_core", "minecraft:blaze_rod"),
                expandGrid(edited),
                "水缸只应把第三行中间（槽位 8）的末影珍珠换成虚空核心，四角烈焰棒与 C/O/W 原样");
        assertEquals("enderstorage:create_recipe", edited.get("type").getAsString(),
                "改材料不得改变自定义配方类型（CreateRecipe 的羊毛染色 assemble 依赖它）");
        assertSameKeySymbols(fixture, edited);
        assertResultUnchanged(fixture, edited);
        assertOtherKeysUnchanged(fixture, edited, "B", "C", "O", "W");
    }

    /**
     * 2026-11-11：末影箱四角烈焰棒（key.B）整组换成烈焰粉，末影珍珠保留。
     *
     * <p>pattern {@code BWB/OCO/BPB} 中 B 位于 4 个角（槽位 1/3/7/9），
     * P 位于第三行中间（槽位 8）。C 仍是 {@code c:chests/wooden} 标签。</p>
     */
    @Test
    void enderChestBlazeRodCornersAreRewrittenToBlazePowderKeepingPearl() {
        RecipeEdit.ModifyRecipeFields rule = fieldRuleById(ENDER_CHEST);
        assertNotNull(rule, "缺少对 enderstorage:ender_chest 的字段改写规则");
        assertEquals(Set.of(ENDER_CHEST), rule.recipeIds(), "规则必须只命中 enderstorage:ender_chest");

        JsonObject fixture = json(ENDER_CHEST_JSON).getAsJsonObject();
        assertEquals(List.of(
                "minecraft:blaze_rod", "#c:wools", "minecraft:blaze_rod",
                "#c:obsidians", "#c:chests/wooden", "#c:obsidians",
                "minecraft:blaze_rod", "#c:ender_pearls", "minecraft:blaze_rod"),
                expandGrid(fixture), "fixture 与 EnderStorage 2.13.0.191 的 ender_chest.json 不一致");

        JsonObject edited = applyFieldEdits(fixture, rule.fieldEdits());
        assertEquals(List.of(
                "minecraft:blaze_powder", "#c:wools", "minecraft:blaze_powder",
                "#c:obsidians", "#c:chests/wooden", "#c:obsidians",
                "minecraft:blaze_powder", "#c:ender_pearls", "minecraft:blaze_powder"),
                expandGrid(edited),
                "末影箱四个角都应变成烈焰粉；末影珍珠位（槽位 8）与 C/W/O 不得变化");
        assertSameKeySymbols(fixture, edited);
        assertResultUnchanged(fixture, edited);
        assertOtherKeysUnchanged(fixture, edited, "C", "O", "P", "W");
    }

    /**
     * 染色配方必须原样保留：{@code enderstorage:recolour_ender_tank} /
     * {@code ..._ender_chest} 与标准合成<b>共享同一个输出物品</b>，任何按产物匹配的
     * 删除或网格规则都会误伤它们；两条新规则必须严格按配方 ID 命中。
     *
     * <p>染色配方的 JSON 只有 type + result，没有 key/pattern。规则按精确配方 ID 命中，
     * 根本不会走到路径写入；即使以后误命中，{@code RecipeJsonEditor.writePath} 也会失败
     * 并让执行器保留原配方。</p>
     */
    @Test
    void enderStorageRecolourRecipesAreNeverTargeted() {
        RecipeEdit.ModifyRecipeFields tank = fieldRuleById(ENDER_TANK);
        RecipeEdit.ModifyRecipeFields chest = fieldRuleById(ENDER_CHEST);
        assertNotNull(tank);
        assertNotNull(chest);

        JsonObject recolourTank = json(ENDER_TANK_RECOLOUR_JSON).getAsJsonObject();
        JsonObject recolourChest = json(ENDER_CHEST_RECOLOUR_JSON).getAsJsonObject();
        assertEquals("enderstorage:recolour_recipe", recolourTank.get("type").getAsString());
        assertEquals("enderstorage:ender_tank", recolourTank.getAsJsonObject("result").get("id").getAsString());
        assertEquals("enderstorage:recolour_recipe", recolourChest.get("type").getAsString());
        assertEquals("enderstorage:ender_chest", recolourChest.getAsJsonObject("result").get("id").getAsString());

        for (ResourceLocation recolour : List.of(ENDER_TANK_RECOLOUR, ENDER_CHEST_RECOLOUR)) {
            assertFalse(tank.matchesId(recolour), "水缸规则不得命中染色配方 " + recolour);
            assertFalse(chest.matchesId(recolour), "末影箱规则不得命中染色配方 " + recolour);
        }

        // 三个规则阶段一起扫描：不得有任何按输出物品 / 配方 ID 命中末影水缸、末影箱
        // （或其染色配方）的删除、网格、产物替换或排序规则。
        Set<ResourceLocation> enderOutputs = Set.of(ENDER_TANK, ENDER_CHEST);
        Set<ResourceLocation> recolours = Set.of(ENDER_TANK_RECOLOUR, ENDER_CHEST_RECOLOUR);
        for (List<RecipeEdit> edits : List.of(RecipeEditRules.edits(),
                RecipeEditRules.ingredientEdits(), RecipeEditRules.lateEdits())) {
            for (RecipeEdit edit : edits) {
                if (edit instanceof RecipeEdit.DeleteByRecipeId delete) {
                    for (ResourceLocation id : delete.recipeIds()) {
                        assertFalse(recolours.contains(id), "规则「" + delete.label() + "」删除了染色配方 " + id);
                    }
                }
                if (edit instanceof RecipeEdit.DeleteByResultId delete) {
                    for (ResourceLocation output : enderOutputs) {
                        assertFalse(delete.resultIds().matches(output), "规则「" + delete.label()
                                + "」按产物 " + output + " 删除，会连带删掉共享该产物的染色配方");
                    }
                }
                if (edit instanceof RecipeEdit.ModifyShapedGrid grid) {
                    for (ResourceLocation output : enderOutputs) {
                        assertFalse(grid.resultTargets().contains(output), "规则「" + grid.label()
                                + "」按产物 " + output + " 做网格改写，会连带改写染色配方（且丢失 CreateRecipe 子类）");
                    }
                }
                if (edit instanceof RecipeEdit.ReplaceResultId replace) {
                    assertFalse(enderOutputs.contains(replace.from()) || enderOutputs.contains(replace.to()),
                            "规则「" + replace.label() + "」改写了末影水缸/末影箱的产物 ID，会影响标准合成与染色配方");
                }
                if (edit instanceof RecipeEdit.ReplaceItemIdDeep deep) {
                    assertFalse(enderOutputs.contains(deep.from()) || enderOutputs.contains(deep.to()),
                            "规则「" + deep.label() + "」深度替换了末影水缸/末影箱的物品 ID");
                }
                if (edit instanceof RecipeEdit.MoveRecipeBefore move) {
                    assertFalse(recolours.contains(move.recipeId()) || recolours.contains(move.anchorId()),
                            "规则「" + move.label() + "」把染色配方当排序目标或锚点");
                }
            }
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

    /** 按规则名查找按配方 ID 删除的规则；不存在返回 null，由调用方断言并给出 DoD 文案。 */
    private static RecipeEdit.DeleteByRecipeId deleteRuleByLabel(String label) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete && label.equals(delete.label())) {
                return delete;
            }
        }
        return null;
    }

    /** 按规则名查找按产物删除的规则；不存在返回 null。 */
    private static RecipeEdit.DeleteByResultId resultDeleteRuleByLabel(String label) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByResultId delete && label.equals(delete.label())) {
                return delete;
            }
        }
        return null;
    }

    /** 查找 from 为目标写法之一的全局材料替换规则；不存在返回 null。 */
    private static RecipeEdit.ReplaceIngredient ingredientRuleFrom(RecipeEdit.IngredientTarget from) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ReplaceIngredient replace && replace.from().equals(from)) {
                return replace;
            }
        }
        return null;
    }

    /** 查找精确命中给定配方 ID 的字段改写规则（{@link RecipeEdit.ModifyRecipeFields}）；不存在返回 null。 */
    private static RecipeEdit.ModifyRecipeFields fieldRuleById(ResourceLocation recipeId) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ModifyRecipeFields fields && fields.matchesId(recipeId)) {
                return fields;
            }
        }
        return null;
    }

    /** 查找以给定产物为目标的有序合成网格改写规则；不存在返回 null。 */
    private static RecipeEdit.ModifyShapedGrid gridRuleByResult(ResourceLocation resultTarget) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ModifyShapedGrid grid && grid.resultTargets().contains(resultTarget)) {
                return grid;
            }
        }
        return null;
    }

    private static String firstIngredientItem(JsonElement recipe) {
        return recipe.getAsJsonObject().getAsJsonArray("ingredients").get(0).getAsJsonObject()
                .get("item").getAsString();
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
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

    /** 按规则名取物品 id 深度替换规则；缺失返回 null 让断言给出可读失败。 */
    private static RecipeEdit.ReplaceItemIdDeep deepIdRule(String label) {
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.ReplaceItemIdDeep rule && rule.label().equals(label)) {
                return rule;
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

    private static final Method EDITOR_WRITE_PATH = editorWritePath();

    /**
     * 反射取得生产 {@code RecipeJsonEditor.writePath} 的私有方法句柄。
     *
     * <p>测试不复制路径写入算法，直接调用产品代码本身；产品 API 不因此扩大，
     * 共享编辑器也不改。它只执行「路径写入」这一步：完整的 serializer Codec
     * 编解码需要游戏注册表与配方实例，离线跑不了，单测不是 serializer 保真证明。</p>
     */
    private static Method editorWritePath() {
        try {
            Method method = RecipeJsonEditor.class.getDeclaredMethod(
                    "writePath", JsonObject.class, String.class, JsonElement.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 用生产 {@code writePath} 把规则字段编辑写进 fixture 的深拷贝。
     *
     * <p>返回 {@code false} 说明规则路径在真实配方 JSON 上不存在（例如误命中染色配方），
     * 生产执行器会保留原配方；测试直接按失败处理，不允许悄悄跳过。</p>
     */
    private static JsonObject applyFieldEdits(JsonObject fixture, Map<String, JsonElement> fieldEdits) {
        JsonObject edited = fixture.deepCopy();
        for (Map.Entry<String, JsonElement> edit : fieldEdits.entrySet()) {
            boolean written;
            try {
                written = Boolean.TRUE.equals(EDITOR_WRITE_PATH.invoke(null, edited, edit.getKey(), edit.getValue()));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("无法调用 RecipeJsonEditor.writePath", e);
            }
            assertTrue(written, "生产 RecipeJsonEditor.writePath 拒绝路径 " + edit.getKey()
                    + "：真实配方 JSON 的形状与规则假设不符");
        }
        return edited;
    }

    /** 只允许改符号的值：新增或删除符号会让 {@code ShapedRecipePattern.unpack} 直接解码失败。 */
    private static void assertSameKeySymbols(JsonObject before, JsonObject after) {
        assertEquals(before.getAsJsonObject("key").keySet(), after.getAsJsonObject("key").keySet(),
                "字段编辑不得新增或删除 key 符号");
    }

    /** 按 pattern + key 展开成 3×3 行优先槽位列表，用于断言布局而不是断言规则字符串。 */
    private static List<String> expandGrid(JsonObject recipe) {
        JsonObject key = recipe.getAsJsonObject("key");
        List<String> slots = new ArrayList<>(9);
        for (JsonElement row : recipe.getAsJsonArray("pattern")) {
            for (char symbol : row.getAsString().toCharArray()) {
                slots.add(symbol == ' ' ? "empty" : describeIngredient(key.getAsJsonObject(String.valueOf(symbol))));
            }
        }
        return slots;
    }

    /** 材料对象的可读形式：物品写 id，标签写 #tag。 */
    private static String describeIngredient(JsonElement ingredient) {
        JsonObject object = ingredient.getAsJsonObject();
        if (object.has("tag")) {
            return "#" + object.get("tag").getAsString();
        }
        if (object.has("item")) {
            return object.get("item").getAsString();
        }
        return "<other:" + object + ">";
    }

    private static void assertResultUnchanged(JsonObject before, JsonObject after) {
        assertEquals(before.get("result"), after.get("result"), "产物 ID、数量与组件必须原样");
    }

    private static void assertOtherKeysUnchanged(JsonObject before, JsonObject after, String... symbols) {
        for (String symbol : symbols) {
            assertEquals(before.getAsJsonObject("key").get(symbol), after.getAsJsonObject("key").get(symbol),
                    "key." + symbol + " 不在本次需求内，不得改写");
        }
    }

    private static JsonObject packResource(String path) throws Exception {
        try (InputStream in = RecipeEditRulesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "缺少随包配方资源：" + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}