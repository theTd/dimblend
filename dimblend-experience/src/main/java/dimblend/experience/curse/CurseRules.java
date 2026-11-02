package dimblend.experience.curse;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * 两条诅咒的纯逻辑：只接收调用方已经取好的事实（槽位、空栈、是否带诅咒、掷骰值、按键码），
 * 不接触实体与世界，单测直接钉边界。
 *
 * <p>诅咒判据本身走原版 {@code EnchantmentHelper.has(stack, EnchantmentEffectComponents.*)}，
 * 这里不做重复解释——1.21.1 的 PREVENT_ARMOR_CHANGE / PREVENT_EQUIPMENT_DROP 是
 * 附魔效果组件，不是物品栈上的独立数据组件。</p>
 */
public final class CurseRules {

    /** 消失诅咒：每次左/右键对每件带消失诅咒的装备独立掷骰的消失概率（0.05%）。 */
    public static final double VANISH_CHANCE_PER_CLICK = 0.0005D;

    /** GLFW_PRESS。 */
    public static final int ACTION_PRESS = 1;
    /** GLFW_MOUSE_BUTTON_LEFT / GLFW_MOUSE_BUTTON_RIGHT。 */
    public static final int BUTTON_LEFT = 0;
    public static final int BUTTON_RIGHT = 1;

    /**
     * 绑定诅咒：槽位从有到空、离开的那件带绑定诅咒、且身上没有别的绑定诅咒装备时，才挂上效果。
     * 任一条不成立就是不触发（换装、脱普通装备、身上还穿着另一件诅咒装备）。
     */
    public static boolean shouldApplyBindingCurseOnRemoval(
            boolean fromEmpty, boolean toEmpty, boolean fromHasBindingCurse, boolean wearsOtherBindingCurse) {
        return !fromEmpty && toEmpty && fromHasBindingCurse && !wearsOtherBindingCurse;
    }

    /** 绑定诅咒：换上的那件带绑定诅咒时解除效果（重新穿上即解）。 */
    public static boolean shouldClearBindingCurseOnEquip(boolean toEmpty, boolean toHasBindingCurse) {
        return !toEmpty && toHasBindingCurse;
    }

    /** 消失诅咒的检查范围：四盔甲槽 + 双手。动物鞍甲（BODY）不属于玩家。 */
    public static boolean isCursedEquipmentSlot(EquipmentSlot slot) {
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                || slot.getType() == EquipmentSlot.Type.HAND;
    }

    /**
     * 消失诅咒：非创造模式、该件带消失诅咒，且这一次掷骰落在 [0, {@link #VANISH_CHANCE_PER_CLICK}) 内。
     */
    public static boolean shouldVanishOnClick(boolean creative, boolean hasVanishingCurse, double roll) {
        return !creative && hasVanishingCurse && roll < VANISH_CHANCE_PER_CLICK;
    }

    /**
     * 客户端 {@code MouseHandler#onPress} 里算数的按键：左键 0 / 右键 1 的按下。
     * 故意不看当前有没有打开界面、先不先看方块——用户要的就是"每一次物理左/右键都算"。
     */
    public static boolean isTrackedMouseClick(int button, int action) {
        return action == ACTION_PRESS && (button == BUTTON_LEFT || button == BUTTON_RIGHT);
    }

    private CurseRules() {
    }
}
