package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * 蓝冰 / 浮冰 的 Create 粉碎配方（随包资源已移除 + 上游拆解粉碎配方防御性删除）。
 *
 * <p>2026-11-18 用户要求移除本 mod 随包的两条 {@code 1 -> 3} 粉碎配方
 * （{@code dimblend_craft:blue_ice_crushing} / {@code packed_ice_crushing}）。
 * 玩家端 PCL 整合包安装的 {@code Create Recycle Everything (Continued) 1.1} 另有
 * {@code 1 -> 9} 拆解粉碎配方（本机开发实例未装该 jar），仍由
 * {@link RecipeEditRules#edits()} 按精确配方 ID 防御性删除。</p>
 *
 * <p>JSON 通过类加载器读取（与 {@link FilterFrameStonecuttingTest} 一致），资源残留或误删
 * 在单测阶段就失败，而不是等到进游戏才发现。</p>
 */
class IceCrushingRecipesTest {

    private static final String BLUE_ICE_RECIPE = "data/dimblend_craft/recipe/blue_ice_crushing.json";
    private static final String PACKED_ICE_RECIPE = "data/dimblend_craft/recipe/packed_ice_crushing.json";

    /** 随包资源必须已删除：游戏内不再有 1 蓝冰/浮冰 -> 3 的粉碎配方。 */
    @Test
    void packNoLongerProvidesIceCrushingRecipes() {
        assertFalse(packResourceExists(BLUE_ICE_RECIPE), "blue_ice_crushing.json 应已从随包资源移除");
        assertFalse(packResourceExists(PACKED_ICE_RECIPE), "packed_ice_crushing.json 应已从随包资源移除");
    }

    /**
     * 2026-11-14：删除 Create Recycle Everything 的蓝冰/浮冰 1->9 拆解粉碎配方；
     * 2026-11-18 移除随包 1->3 后，该防御性规则继续保留，且不得误删原版压缩与 Create 洗涤配方。
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

    private static boolean packResourceExists(String path) {
        try (InputStream in = IceCrushingRecipesTest.class.getClassLoader().getResourceAsStream(path)) {
            return in != null;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
