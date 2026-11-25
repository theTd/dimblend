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
 * 蓝冰 / 浮冰相关配方的两件事：随包粉碎配方已移除（2026-11-18），
 * 以及 2026-11-25 的「冰升级断源」只删三条（决议 10）。
 *
 * <p>2026-11-25 用户确认「只删 {@code minecraft:packed_ice}、{@code minecraft:blue_ice}、
 * {@code create:splashing/ice} 三步升级配方，10 条 1:1 冷冻配方全部保留」。这 10 条分属
 * 5 个模组（fluid / create_dragons_plus / dndesires / kaleidoscope_chinesefood / aether
 * 各 2 条，逐 jar 枚举见 report-w3 §11.5–11.9），它们同样产出浮冰 / 蓝冰——
 * 所以本规则<b>只能按配方 ID</b>，任何「按产物删浮冰/蓝冰」的写法都会把这 10 条一起杀掉。
 * 本类把这条硬约束钉死。</p>
 *
 * <p>JSON 通过类加载器读取（与 {@link FilterFrameStonecuttingTest} 一致），资源残留或误删
 * 在单测阶段就失败，而不是等到进游戏才发现。</p>
 */
class IceCrushingRecipesTest {

    private static final String BLUE_ICE_RECIPE = "data/dimblend_craft/recipe/blue_ice_crushing.json";
    private static final String PACKED_ICE_RECIPE = "data/dimblend_craft/recipe/packed_ice_crushing.json";

    /** 2026-11-25 决议 10：这三条（也只有这三条）冰升级配方要删。 */
    private static final Set<ResourceLocation> DELETED_ICE_UPGRADES = Set.of(
            ResourceLocation.parse("minecraft:packed_ice"),
            ResourceLocation.parse("minecraft:blue_ice"),
            ResourceLocation.parse("create:splashing/ice"));

    /** 必须保留的 10 条 1:1 冷冻 / 冰柜 / 冷冻机配方（逐 jar 枚举）。 */
    private static final Set<ResourceLocation> KEPT_ONE_TO_ONE_FREEZING = Set.of(
            ResourceLocation.parse("fluid:freezing/packed_ice_from_ice"),
            ResourceLocation.parse("fluid:freezing/blue_ice_from_packed_ice"),
            ResourceLocation.parse("create_dragons_plus:freezing/packed_ice_from_ice"),
            ResourceLocation.parse("create_dragons_plus:freezing/blue_ice_from_packed_ice"),
            ResourceLocation.parse("dndesires:freezing/packed_ice_from_ice"),
            ResourceLocation.parse("dndesires:freezing/blue_ice_from_packed_ice"),
            ResourceLocation.parse("kaleidoscope_chinesefood:freezer/freezing/packed_ice"),
            ResourceLocation.parse("kaleidoscope_chinesefood:freezer/freezing/blue_ice"),
            ResourceLocation.parse("aether:packed_ice_freezing"),
            ResourceLocation.parse("aether:blue_ice_freezing"));

    /** 冷冻配方与升级配方共有的产物：按产物 ID 删除一律算误伤。 */
    private static final Set<ResourceLocation> SHARED_ICE_OUTPUTS = Set.of(
            ResourceLocation.parse("minecraft:ice"),
            ResourceLocation.parse("minecraft:packed_ice"),
            ResourceLocation.parse("minecraft:blue_ice"));

    /** 随包资源必须已删除：游戏内不再有 1 蓝冰/浮冰 -> 3 的粉碎配方。 */
    @Test
    void packNoLongerProvidesIceCrushingRecipes() {
        assertFalse(packResourceExists(BLUE_ICE_RECIPE), "blue_ice_crushing.json 应已从随包资源移除");
        assertFalse(packResourceExists(PACKED_ICE_RECIPE), "packed_ice_crushing.json 应已从随包资源移除");
    }

    /**
     * 2026-11-14：删除 Create Recycle Everything 的蓝冰/浮冰 1->9 拆解粉碎配方；
     * 2026-11-18 移除随包 1->3 后，该防御性规则继续保留。
     */
    @Test
    void upstreamRecycleEverythingIceCrushingRecipesAreDefensivelyDeleted() {
        RecipeEdit.DeleteByRecipeId found = null;
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete
                    && "蓝冰与浮冰原拆解粉碎配方".equals(delete.label())) {
                found = delete;
                break;
            }
        }
        assertNotNull(found, "缺少删除蓝冰/浮冰上游拆解粉碎配方的规则");
        assertEquals(Set.of(
                ResourceLocation.parse("create_recycle_everything:crushing/vanilla/scrap_blue_ice"),
                ResourceLocation.parse("create_recycle_everything:crushing/vanilla/scrap_packed_ice"),
                ResourceLocation.parse("create:crushing/misc/scrap_blue_ice"),
                ResourceLocation.parse("create:crushing/misc/scrap_packed_ice")),
                found.recipeIds(),
                "必须覆盖 create_recycle_everything 1.1 与 1.0.x 两条同源拆解粉碎配方 ID");
        assertTrue(found.defensive(),
                "开发实例未装 create_recycle_everything，必须标 defensive = true 避免假 WARN");
    }

    /**
     * 2026-11-25 决议 10：冰升级断源只删三条，10 条 1:1 冷冻配方一条都不许碰。
     */
    @Test
    void onlyThreeIceUpgradeRecipesAreDeletedAndOneToOneFreezingStays() {
        RecipeEdit.DeleteByRecipeId iceUpgrades = null;
        for (RecipeEdit edit : RecipeEditRules.edits()) {
            if (edit instanceof RecipeEdit.DeleteByRecipeId delete
                    && "冰升级（压缩 / 洗涤）配方".equals(delete.label())) {
                iceUpgrades = delete;
                break;
            }
        }
        assertNotNull(iceUpgrades, "缺少规则「冰升级（压缩 / 洗涤）配方」");
        assertEquals(DELETED_ICE_UPGRADES, iceUpgrades.recipeIds(),
                "决议 10 只允许删浮冰压缩、蓝冰压缩与 Create 洗涤冰三条，实际 " + iceUpgrades.recipeIds());
        assertFalse(iceUpgrades.defensive(),
                "三条都是原版 / Create 本体的配方，今天必然存在，不属于防御性规则");

        for (List<RecipeEdit> phase : List.of(
                RecipeEditRules.edits(), RecipeEditRules.ingredientEdits(), RecipeEditRules.lateEdits())) {
            for (RecipeEdit edit : phase) {
                if (edit instanceof RecipeEdit.DeleteByRecipeId delete) {
                    for (ResourceLocation keep : KEPT_ONE_TO_ONE_FREEZING) {
                        assertFalse(delete.recipeIds().contains(keep),
                                "规则「" + delete.label() + "」误删了必须保留的 1:1 冷冻配方 " + keep);
                    }
                }
                if (edit instanceof RecipeEdit.DeleteByResultId delete) {
                    for (ResourceLocation result : SHARED_ICE_OUTPUTS) {
                        assertFalse(delete.resultIds().matches(result),
                                "规则「" + delete.label() + "」按产物 " + result + " 删除："
                                        + "10 条冷冻配方产出同样的物品，会被一起杀掉，必须改按配方 ID");
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
