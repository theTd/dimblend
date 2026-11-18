package dimblend.experience.compat.dirtywater;

import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 脏水规则的纯判断层：纯度阈值、物品/流体的"食物或饮品"分类。
 *
 * <p>本类的纯度判定与 id 分类不碰注册表、不碰 YAT 类型，单测直接调
 * {@link #isDirtyPurity(int)} / {@link #isFoodOrDrinkId(ResourceLocation)} /
 * {@link #isFoodLikeId(ResourceLocation)}；只有带 {@link ItemStack}/{@link FluidStack} 的重载
 * 才需要运行期的注册表与 YAT（纯度的实际读取在 {@link DirtyWaterPurity}）。</p>
 *
 * <p>阈值口径（用户拍板）：只拒 <b>Purity == 0</b>（"肮脏的水"）。Purity 1 "略脏的水" 起放行；
 * 与本仓 {@code ThirstFilterBalance} 把三种滤芯上限都压到 1 一致——过滤一次即可用于食物。</p>
 */
public final class DirtyWaterRules {

    /** 食物/饮品可接受的最低纯度：0 拒收，1/2/3 放行。 */
    public static final int MIN_ACCEPTABLE_PURITY = 1;

    /** 原版水的流体 id（纯常量，不看注册表）。 */
    public static final ResourceLocation WATER_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "water");

    /**
     * 显式白名单：这些 id 不是"可食用 FOOD 组件"意义上的食物，但处在食物链上游
     * （面团/生坯/汤/调饮），脏水同样不该参与合成。标签 {@code c:dough}/{@code c:foods}
     * 在运行期另判，这里给没有标签的常见配方兜底。
     */
    private static final Set<ResourceLocation> FOOD_INTERMEDIATE_IDS = Set.of(
            ResourceLocation.fromNamespaceAndPath("create", "dough"),
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "raw_dough"),
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "raw_steamed_rice_rolls"),
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_sculk", "sculk_dough"),
            ResourceLocation.fromNamespaceAndPath("deeperdarker", "sculk_dough"),
            ResourceLocation.fromNamespaceAndPath("twilightforest", "moss_soup"),
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_world_liquor", "cola"),
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_world_liquor", "tonic_water"));

    /** 工作盆"食品意图"侧的路径关键词（面粉/茶/糖/可可/香草等酿造与烹饪原料）。 */
    private static final Set<String> FOOD_LIKE_PATH_TOKENS = Set.of(
            "dough", "flour", "tea", "sugar", "cocoa", "herb", "crop", "batter", "rice", "soup", "juice");

    /** 纯净度是否属于"脏水"（只拒 0）。 */
    public static boolean isDirtyPurity(int purity) {
        return purity < MIN_ACCEPTABLE_PURITY;
    }

    /**
     * 管路/储罐内 {@link FluidStack} 的有效纯度：YAT {@code common.toml} 的
     * {@code defaultPurity = 2} 面向无标签创造物品水桶，而 Create 水泵/集水器/铜水槽抽出的原水不带
     * {@code Purity} 组件——与 YAT 自身 {@code MixinFluidTank}、{@code FilterFrameBlockEntity$PurityBlendingTank}
     * 及 {@code CopperSinkBottleInteraction} 一致，无显式 {@code Purity} 标签的流体水一律按 0（肮脏的水）计。
     */
    public static int effectiveFluidPurity(boolean hasPurityTag, int tagOrDefaultPurity) {
        return hasPurityTag ? tagOrDefaultPurity : 0;
    }

    /** 流体 id + 纯度是否构成脏水（非水一律 false：牛奶/油/药水/茶汤不是脏水）。 */
    public static boolean isDirtyWater(int purity, ResourceLocation fluidId) {
        return WATER_ID.equals(fluidId) && isDirtyPurity(purity);
    }

    /** 显式食物中间体 id 集合。 */
    public static boolean isFoodOrDrinkId(ResourceLocation id) {
        return id != null && FOOD_INTERMEDIATE_IDS.contains(id);
    }

    /** 工作盆用的更宽"食品意图"分类：食物中间体 + 路径关键词。 */
    public static boolean isFoodLikeId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        if (isFoodOrDrinkId(id)) {
            return true;
        }
        String path = id.getPath();
        for (String token : FOOD_LIKE_PATH_TOKENS) {
            if (path.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 物品是否是食物/饮品（食物中间体、FOOD 组件、EAT/DRINK 使用动画、食物/饮品/面团标签）。 */
    public static boolean isFoodOrDrink(ItemStack stack) {
        if (stack.isEmpty() || isPotion(stack)) {
            return false;
        }
        if (stack.has(DataComponents.FOOD)) {
            return true;
        }
        UseAnim animation = stack.getItem().getUseAnimation(stack);
        if (animation == UseAnim.EAT || animation == UseAnim.DRINK) {
            return true;
        }
        if (stack.is(Tags.FOODS) || stack.is(Tags.DRINKS) || stack.is(Tags.DOUGH)) {
            return true;
        }
        return isFoodOrDrinkId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 工作盆用的更宽分类（额外认路径关键词）。 */
    public static boolean isFoodLike(ItemStack stack) {
        return isFoodOrDrink(stack) || isFoodLikeId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 药水（含喷溅/滞留）：药水酿造/药水输出不走脏水拒收。 */
    public static boolean isPotion(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
    }

    /** 饮品流体输出（{@code c:tea} 等）——工作盆/配方级保底只认标签，非饮品一律 false。 */
    public static boolean isDrinkFluid(FluidStack stack) {
        return !stack.isEmpty() && (stack.is(Tags.TEA_FLUID) || stack.is(Tags.DRINK_FLUIDS));
    }

    private DirtyWaterRules() {
    }

    /** 标签常量延迟加载：纯判定路径不触发 {@code Registries}/{@code DataComponents} 类初始化。 */
    private static final class Tags {
        private static final TagKey<Item> FOODS = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("c", "foods"));
        private static final TagKey<Item> DRINKS = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("c", "drinks"));
        private static final TagKey<Item> DOUGH = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("c", "dough"));
        private static final TagKey<net.minecraft.world.level.material.Fluid> TEA_FLUID = TagKey.create(Registries.FLUID,
                ResourceLocation.fromNamespaceAndPath("c", "tea"));
        private static final TagKey<net.minecraft.world.level.material.Fluid> DRINK_FLUIDS = TagKey.create(Registries.FLUID,
                ResourceLocation.fromNamespaceAndPath("c", "drinks"));

        private Tags() {
        }
    }
}
