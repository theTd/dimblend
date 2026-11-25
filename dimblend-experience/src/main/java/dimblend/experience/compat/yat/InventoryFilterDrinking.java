package dimblend.experience.compat.yat;

import java.util.ArrayList;
import java.util.List;

import dev.minhnh.yetanotherthirst.core.advancement.ModAdvancements;
import dev.minhnh.yetanotherthirst.core.item.FilterCoreItem;
import dev.minhnh.yetanotherthirst.core.item.ModItems;
import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 背包滤芯"就地过滤"喝脏水（LOCKED DECISION #8 {@code filter_cost_uniform_1000}）。
 *
 * <p>口径：喝下 <b>Purity 0（肮脏）</b> 的水时，如果玩家背包（{@code inventory.items}
 * 的 {@code 0..35}，即快捷栏 9 格 + 主背包 27 格，不含副手/盔甲/背包内）里有干净的滤芯，
 * 就消耗一枚滤芯的 <b>固定 1000.0 Lifespan</b>，把这一口水的纯度抬到 <b>1（微脏）</b>，
 * 于是既不中毒也不记 {@code DIRTY_WATER} 进度；水本身不受影响。纯度 1/2/3 的水完全不做
 * 任何事，不消耗滤芯。</p>
 *
 * <p>取用顺序（与裁定一致）：</p>
 * <ol>
 *   <li>先用<b>已用过的</b>滤芯（{@code 0 < lifespan < max}），避免把新滤芯拆碎；</li>
 *   <li>否则按 {@code 0..8} 再 {@code 9..35} 找第一枚<b>满耐久</b>滤芯；数量 &gt; 1 时
 *       拆 1 枚出来单独扣耐久，扣完塞回背包（塞不下就掉落）；</li>
 *   <li>{@code newLifespan <= 0} 时换成对应的 {@code clogged_*} 滤芯并给
 *       {@code CLOGGED_FILTER} 进度。</li>
 * </ol>
 *
 * <p>纯判定（{@link #chooseSlot}/{@link #afterDrink}）与 Minecraft 无关，单测覆盖；
 * 只有 {@link #tryFilterDirtyDrink} 碰真实背包。</p>
 */
public final class InventoryFilterDrinking {

    /** 每次过滤固定消耗的 Lifespan（裁定 #8，两种喝法同一价）。 */
    public static final double COST_PER_DRINK = 1000.0;

    /** 快捷栏格数（{@code inventory.items} 的前 9 格）。 */
    public static final int HOTBAR_SLOTS = 9;

    /** 扫描上限（{@code inventory.items} 只有 36 格）。 */
    public static final int MAIN_SLOTS = 36;

    /** 一眼滤芯的纯数据视图（只用于选格，不含任何 Minecraft 类型）。 */
    public record CoreSlot(int index, double lifespan, double maxLifespan) {
        public boolean partiallyUsed() {
            return lifespan > 0.0 && lifespan < maxLifespan;
        }

        public boolean full() {
            return lifespan >= maxLifespan;
        }
    }

    /** 一次过滤的结果：新 Lifespan 与是否堵死。 */
    public record Consumption(double newLifespan, boolean clogged) {
    }

    /** 这一口水的纯度是否需要过滤（只有 0 需要）。 */
    public static boolean needsFilter(int purity) {
        return purity <= WaterSourcePurityRules.MIN_PURITY;
    }

    /** 过滤后喝进去的纯度（固定 1）。 */
    public static int filteredPurity() {
        return 1;
    }

    /** 扣一次固定消耗：{@code newLifespan <= 0} 即堵死。 */
    public static Consumption afterDrink(double lifespan) {
        double remaining = lifespan - COST_PER_DRINK;
        return new Consumption(remaining, remaining <= 0.0);
    }

    /**
     * 选格：先选"已用过的"里最靠前的，否则选满耐久的里最靠前的（调用方按
     * {@code 0..8} 再 {@code 9..35} 的顺序传入）。没有可用滤芯返回 {@code -1}。
     */
    public static int chooseSlot(List<CoreSlot> slots) {
        int firstFull = -1;
        for (CoreSlot slot : slots) {
            if (slot.partiallyUsed()) {
                return slot.index();
            }
            if (firstFull < 0 && slot.full()) {
                firstFull = slot.index();
            }
        }
        return firstFull;
    }

    /** 哪些物品算"干净滤芯"（堵死的不参与过滤）。 */
    public static boolean isCleanFilterCore(Item item) {
        return item == ModItems.FABRIC_FILTER_CORE.get()
                || item == ModItems.SAND_FILTER_CORE.get()
                || item == ModItems.CARBON_FILTER_CORE.get();
    }

    /** 干净滤芯 -> 对应堵死滤芯。 */
    public static Item cloggedVariant(Item clean) {
        if (clean == ModItems.FABRIC_FILTER_CORE.get()) {
            return ModItems.CLOGGED_FABRIC_FILTER.get();
        }
        if (clean == ModItems.SAND_FILTER_CORE.get()) {
            return ModItems.CLOGGED_SAND_FILTER.get();
        }
        if (clean == ModItems.CARBON_FILTER_CORE.get()) {
            return ModItems.CLOGGED_CARBON_FILTER.get();
        }
        return clean;
    }

    /**
     * 真实背包结算：成功消耗一枚滤芯返回 {@code true}（调用方据此把这一口水的纯度抬到 1）。
     * 调用方必须先判 {@code DirtyWaterCompat.purityAvailable()}。
     */
    public static boolean tryFilterDirtyDrink(ServerPlayer player) {
        List<CoreSlot> candidates = new ArrayList<>(MAIN_SLOTS);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.isEmpty() || !isCleanFilterCore(stack.getItem())) {
                continue;
            }
            double max = FilterCoreItem.getMaxLifespan(stack);
            double lifespan = FilterCoreItem.readLifespan(stack, max);
            candidates.add(new CoreSlot(i, lifespan, max));
        }
        int index = chooseSlot(candidates);
        if (index < 0) {
            return false;
        }
        ItemStack stack = player.getInventory().items.get(index);
        double max = FilterCoreItem.getMaxLifespan(stack);
        Consumption consumption = afterDrink(FilterCoreItem.readLifespan(stack, max));
        boolean single = stack.getCount() <= 1;
        ItemStack working = single ? stack : stack.copyWithCount(1);
        if (consumption.clogged()) {
            working = new ItemStack(cloggedVariant(working.getItem()));
            ModAdvancements.award(player, ModAdvancements.CLOGGED_FILTER);
        } else {
            FilterCoreItem.writeLifespan(working, consumption.newLifespan());
        }
        if (single) {
            player.getInventory().items.set(index, working);
        } else {
            stack.shrink(1);
            if (!player.getInventory().add(working)) {
                player.drop(working, false);
            }
        }
        DimBlend.LOGGER.debug("filter core at slot {} consumed {} lifespan ({})",
                index, COST_PER_DRINK, consumption.clogged() ? "clogged" : "ok");
        return true;
    }

    private InventoryFilterDrinking() {
    }
}
