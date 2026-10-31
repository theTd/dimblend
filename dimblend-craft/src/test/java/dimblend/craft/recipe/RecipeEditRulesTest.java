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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

/**
 * 规则表自检：规则之间不得互相矛盾。
 *
 * <p>背景（2026-10-31）：黄铜改为「仅超级加热可产出」，删除 {@code create:mixing/brass_ingot} 之后，
 * 原本为它写的「产量 2 -&gt; 1」字段修改与「超热版排在其前」的排序规则都还在指向它。
 * 执行器先删后改，这两条规则只会各打一条 WARN 日志然后空转，游戏里看不出任何异常。
 * 这类矛盾在运行期只留下告警，所以把不变量固定在单测里。</p>
 */
class RecipeEditRulesTest {

    private static final ResourceLocation HEATED_BRASS = ResourceLocation.parse("create:mixing/brass_ingot");
    private static final ResourceLocation SUPERHEATED_BRASS =
            ResourceLocation.parse("dimblend_craft:brass_ingot_from_superheated_mixing");

    /** 需求：黄铜只允许超级加热搅拌产出，Create 自带的加热版必须删除。 */
    @Test
    void heatedBrassMixingIsDeleted() {
        assertTrue(deliberatelyDeleted(RecipeEditRules.edits()).contains(HEATED_BRASS),
                "Create 自带加热搅拌 create:mixing/brass_ingot 未被删除，加热火下仍能产出黄铜");
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

    /** 超热黄铜配方不再依赖排序，且随包数据仍声明超级加热与 x2。 */
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
        assertEquals(2, result.get("count").getAsInt(), "超热版维持 x2，删除加热版不改变超热版产量");
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