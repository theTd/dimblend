package dimblend.experience.compat.disenchantingtable;

import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

/**
 * 附魔台（disenchanting_table）禁令：带诅咒的装备与带诅咒的附魔书不得被洗掉附魔。
 *
 * <p>用户口径（2026-11-25）：诅咒只能靠"把整件东西毁掉"（岩浆/消失诅咒/绑定诅咒）处理，
 * 不能靠附魔台把诅咒单独抠下来当商品卖——否则"绑定诅咒装备脱不掉"这条规则被
 * {@code 附魔台 → 拿走诅咒书 → 洗白装备} 一步绕过。</p>
 *
 * <p>两个组件都要看：</p>
 * <ul>
 *   <li>{@link DataComponents#ENCHANTMENTS}——装备上正常生效的附魔；</li>
 *   <li>{@link DataComponents#STORED_ENCHANTMENTS}——附魔书（以及已被挖下来的附魔）的库存。
 *       原版 {@code EnchantmentHelper#hasTag} <b>只</b>遍历前者（1.21.1 字节码核实），
 *       所以附魔书必须自己再查一遍，否则"诅咒书"逃过禁令。</li>
 * </ul>
 *
 * <p>兜底：{@code PREVENT_ARMOR_CHANGE}（绑定诅咒）与 {@code PREVENT_EQUIPMENT_DROP}（消失诅咒）
 * 是两条诅咒附魔的效果组件；即使某个整合包把诅咒附魔的标签改空、或加了不在
 * {@code #minecraft:curse} 里的自定义诅咒，效果组件这条路径仍然认得出来。走
 * {@code EnchantmentHelper#has}（它遍历 {@code runIterationOnItem}，两个组件都覆盖）。</p>
 */
public final class DisenchantingCurseRules {

    /** 组合口径（纯函数，单测钉真值表）：任一来源为真即禁止洗附魔。 */
    public static boolean shouldBlock(boolean enchantmentsCurse, boolean storedCurse,
                                      boolean armorChangeLock, boolean equipmentDropLock) {
        return enchantmentsCurse || storedCurse || armorChangeLock || equipmentDropLock;
    }

    /** 该物品栈是否带任何形式的诅咒（为 true 时附魔台必须拒绝）。 */
    public static boolean hasAnyCurse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return shouldBlock(
                hasCurse(stack.get(DataComponents.ENCHANTMENTS)),
                hasCurse(stack.get(DataComponents.STORED_ENCHANTMENTS)),
                EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE),
                EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP));
    }

    /** {@code ItemEnchantments} 里是否有 {@code #minecraft:curse} 标签的附魔。 */
    static boolean hasCurse(@Nullable ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return false;
        }
        Set<Holder<Enchantment>> keys = enchantments.keySet();
        for (Holder<Enchantment> enchantment : keys) {
            if (enchantment.is(EnchantmentTags.CURSE)) {
                return true;
            }
        }
        return false;
    }

    private DisenchantingCurseRules() {
    }
}
