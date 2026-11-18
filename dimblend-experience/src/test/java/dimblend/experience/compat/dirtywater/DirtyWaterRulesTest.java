package dimblend.experience.compat.dirtywater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 脏水纯度阈值与分类的纯函数单测（不碰注册表、不碰 YAT）。
 *
 * <p>阈值口径：只拒 Purity 0；Purity 1 "略脏的水" 起放行（与
 * {@code ThirstFilterBalance} 把滤芯上限压到 1 对齐——过滤一次即可用于食物）。
 */
class DirtyWaterRulesTest {

    private static final ResourceLocation WATER = ResourceLocation.fromNamespaceAndPath("minecraft", "water");
    private static final ResourceLocation MILK = ResourceLocation.fromNamespaceAndPath("minecraft", "milk");
    private static final ResourceLocation OIL = ResourceLocation.fromNamespaceAndPath("create", "oil");

    @Test
    void onlyPurityZeroCountsAsDirty() {
        assertTrue(DirtyWaterRules.isDirtyPurity(0));
        assertFalse(DirtyWaterRules.isDirtyPurity(1));
        assertFalse(DirtyWaterRules.isDirtyPurity(2));
        assertFalse(DirtyWaterRules.isDirtyPurity(3));
        // 低于 0 的非法值按脏水处理（保守）
        assertTrue(DirtyWaterRules.isDirtyPurity(-1));
    }

    @Test
    void untaggedPipeFluidDefaultsToZeroEvenWhenItemDefaultPurityIsTwo() {
        int rawPipePurity = DirtyWaterRules.effectiveFluidPurity(false, 2);
        assertTrue(DirtyWaterRules.isDirtyWater(rawPipePurity, WATER));

        int filteredPipePurity = DirtyWaterRules.effectiveFluidPurity(true, 1);
        assertFalse(DirtyWaterRules.isDirtyWater(filteredPipePurity, WATER));

        int explicitDirtyPurity = DirtyWaterRules.effectiveFluidPurity(true, 0);
        assertTrue(DirtyWaterRules.isDirtyWater(explicitDirtyPurity, WATER));
    }

    @Test
    void dirtyWaterRequiresMinecraftWaterId() {
        assertTrue(DirtyWaterRules.isDirtyWater(0, WATER));
        assertFalse(DirtyWaterRules.isDirtyWater(1, WATER));
        // 非水流体不是"脏水"：牛奶/油/药水/茶汤都不该被拒
        assertFalse(DirtyWaterRules.isDirtyWater(0, MILK));
        assertFalse(DirtyWaterRules.isDirtyWater(0, OIL));
        assertFalse(DirtyWaterRules.isDirtyWater(0, null));
    }

    @Test
    void foodOrDrinkIdsCoverTheReportedCraftingRecipes() {
        for (String id : new String[] {
                "create:dough",
                "kaleidoscope_cookery:raw_dough",
                "kaleidoscope_cookery:raw_steamed_rice_rolls",
                "kaleidoscope_sculk:sculk_dough",
                "deeperdarker:sculk_dough",
                "twilightforest:moss_soup",
                "kaleidoscope_world_liquor:cola",
                "kaleidoscope_world_liquor:tonic_water"}) {
            assertTrue(DirtyWaterRules.isFoodOrDrinkId(parse(id)), id);
        }
    }

    @Test
    void industrialRecipeOutputsAreNotFoodOrDrink() {
        // 这些配方同样吃水桶/水瓶，但输出不是食物：脏水必须继续可用
        for (String id : new String[] {
                "fluid:copper_sink",
                "create_dragons_plus:splashing_sail",
                "twilightforest:rainy_cloud",
                "yet_another_thirst:clean_fabric_filter"}) {
            assertFalse(DirtyWaterRules.isFoodOrDrinkId(parse(id)), id);
        }
    }

    @Test
    void foodLikeCoversBasinBrewingIngredientsByPath() {
        assertTrue(DirtyWaterRules.isFoodLikeId(parse("minecraft:sugar")));
        assertTrue(DirtyWaterRules.isFoodLikeId(parse("kaleidoscope_cookery:flour")));
        assertTrue(DirtyWaterRules.isFoodLikeId(parse("create:builders_tea")));
        assertTrue(DirtyWaterRules.isFoodLikeId(parse("create:wheat_flour")));
        // 工业料保持工业
        assertFalse(DirtyWaterRules.isFoodLikeId(parse("minecraft:concrete_powder")));
        assertFalse(DirtyWaterRules.isFoodLikeId(parse("create:crushed_raw_iron")));
        assertFalse(DirtyWaterRules.isFoodLikeId(null));
    }

    private static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }
}
