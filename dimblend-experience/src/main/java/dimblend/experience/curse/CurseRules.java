package dimblend.experience.curse;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * R19/R20 的纯逻辑：只接收调用方已经取好的事实（槽位、空栈、是否带诅咒、掷骰值），
 * 不接触实体与世界，单测直接钉边界。
 *
 * <p>诅咒判据本身走原版 {@code EnchantmentHelper.has(stack, EnchantmentEffectComponents.*)}，
 * 这里不做重复解释——1.21.1 的 PREVENT_ARMOR_CHANGE / PREVENT_EQUIPMENT_DROP 是
 * 附魔效果组件，不是物品栈上的独立数据组件。</p>
 */
public final class CurseRules {

    /** R20：每件装备每次受击独立掷骰的消失概率（0.2%）。 */
    public static final double VANISH_CHANCE_PER_HIT = 0.002D;

    /**
     * R19：绑定诅咒装备「脱下」= 人形盔甲槽从有到无，且离开的那件带绑定诅咒。
     * 绑定诅咒原版只出现在四盔甲槽（slots=armor），双手/动物鞍甲不算。
     */
    public static boolean isBindingCurseArmorRemoval(
            EquipmentSlot slot, boolean fromEmpty, boolean toEmpty, boolean fromHasBindingCurse) {
        return fromHasBindingCurse
                && !fromEmpty
                && toEmpty
                && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    /** R20 的检查范围：四盔甲槽 + 双手。动物鞍甲（BODY）不属于玩家。 */
    public static boolean isCursedEquipmentSlot(EquipmentSlot slot) {
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                || slot.getType() == EquipmentSlot.Type.HAND;
    }

    /**
     * R20：该次受击是否让这一件装备消失。
     * 只有真正造成伤害、非创造模式、该件带消失诅咒，且这一次掷骰落在 [0, 0.2%) 内才成立。
     */
    public static boolean shouldVanishOnHit(
            boolean damageDealt, boolean creative, boolean hasVanishingCurse, double roll) {
        return damageDealt
                && !creative
                && hasVanishingCurse
                && roll < VANISH_CHANCE_PER_HIT;
    }

    /**
     * R19：与 1.21.1 中毒相同的 tick 节奏——每 {@code 25 >> amplifier} tick 一次；
     * amplifier ≥5 时 {@code 25 >> amplifier == 0}，即每 tick 都触发。
     */
    public static boolean cursedPoisonTicksOn(int duration, int amplifier) {
        int interval = 25 >> amplifier;
        return interval > 0 ? duration % interval == 0 : true;
    }

    /** R19：与中毒相同，只在血量 &gt;1 时扣 1 点魔法伤害（留给 1 点血，不会直接毒死）。 */
    public static boolean cursedPoisonDamages(float health) {
        return health > 1.0F;
    }

    private CurseRules() {
    }
}
