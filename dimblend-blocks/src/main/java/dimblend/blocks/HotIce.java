package dimblend.blocks;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 热冰（{@code dimblend_blocks:hot_ice}）：可再生燃料。
 *
 * <p>玩法定位：只作为熔炉燃料存在，燃烧时长 1600 tick（= 8 个物品，介于煤炭 1600
 * 与岩浆桶 20000 之间），与 {@code data/neoforge/data_maps/item/furnace_fuels.json}
 * 里的同一数值互为兜底。</p>
 *
 * <p><b>刻意零物品标签</b>：不进 {@code #minecraft:coals}、{@code #c:gems} 等任何标签，
 * 于是原版与各模组"接受煤炭/可燃物标签"的配方一律不认它——热冰只能是燃料，不能变相
 * 变成煤的替代合成材料。</p>
 */
public final class HotIce {

    /** 燃烧时长 1600 tick：与 data map 声明一致（任一来源生效即可）。 */
    public static final int BURN_TIME = 1600;

    public static final DeferredItem<Item> HOT_ICE =
            DimBlendBlocks.ITEMS.register("hot_ice", () -> new BurnableItem(new Item.Properties()));

    /** 触发类加载（BLOCKS/ITEMS 为主类静态字段，注册发生在类初始化时）。 */
    public static void init() {
    }

    /**
     * 覆盖 NeoForge 的 {@code Item#getBurnTime}：不依赖 data map 也能烧。
     */
    static final class BurnableItem extends Item {

        BurnableItem(Properties properties) {
            super(properties);
        }

        @Override
        public int getBurnTime(ItemStack stack, @Nullable RecipeType<?> recipeType) {
            return BURN_TIME;
        }
    }

    private HotIce() {
    }
}
