package dimblend.experience.compat.gunsmith;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 「枪匠」系列写在枪械 {@code minecraft:custom_data} 里的两个自有键：
 * <b>不改动 TaCZ 的 {@code GunId}/{@code Attachment*} 或原版 {@code maxDamage}/{@code damage} 语义</b>，
 * 缺席即视为 0。
 *
 * <ul>
 *   <li>{@link #OVER_DURABILITY_KEY}：第二行蓝色过度维修耐久（{@code 0..maxDamage}）。
 *       与原耐久正交，开火优先扣除本键，因此原版卡壳率与低耐久衰减自动不受蓝色条干扰。</li>
 *   <li>{@link #LEVEL_KEY}：枪械经验等级（{@code 0..50}，新枪为 0；只在 TaCZ 原生 tooltip 的「等级」
 *       行显示出来，见 {@link GunsmithLevelText}，任何倍率/加成数值一律不显示）。</li>
 * </ul>
 */
public final class GunsmithData {

    /** 第二行蓝色过度维修耐久键。 */
    public static final String OVER_DURABILITY_KEY = "DimBlendOverDurability";

    /** 枪械经验等级键（0–50，缺席即 0 级）。 */
    public static final String LEVEL_KEY = "DimBlendGunsmithLevel";

    // ---- 蓝色过度维修耐久 ----------------------------------------------------

    public static int getOverDurability(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        return Math.max(0, data.copyTag().getInt(OVER_DURABILITY_KEY));
    }

    public static boolean hasOverDurability(ItemStack stack) {
        return getOverDurability(stack) > 0;
    }

    /** 写回蓝色过度维修耐久；{@code <= 0} 时删键（保持"无键即 0"不变式）。 */
    public static void setOverDurability(ItemStack stack, int value) {
        int clamped = Math.max(0, value);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (clamped > 0) {
                tag.putInt(OVER_DURABILITY_KEY, clamped);
            } else {
                tag.remove(OVER_DURABILITY_KEY);
            }
        });
    }

    /** 蓝色过度维修耐久减一（不低于 0）；返回扣减后的剩余值。 */
    public static int decrementOverDurability(ItemStack stack) {
        int remaining = Math.max(0, getOverDurability(stack) - 1);
        setOverDurability(stack, remaining);
        return remaining;
    }

    // ---- 枪械经验等级（0..50） ------------------------------------------------

    public static int getLevel(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        return GunsmithMath.clampLevel(data.copyTag().getInt(LEVEL_KEY));
    }

    public static void setLevel(ItemStack stack, int level) {
        int clamped = GunsmithMath.clampLevel(level);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (clamped > 0) {
                tag.putInt(LEVEL_KEY, clamped);
            } else {
                tag.remove(LEVEL_KEY);
            }
        });
    }

    public static boolean hasLevel(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(LEVEL_KEY);
    }

    // ---- 判定 ---------------------------------------------------------------

    /**
     * 是否是受 gundb 耐久系统接管的 TaCZ 枪械。
     *
     * <p>gundb 本身不注册独立物品，而是通过 {@code ModernKineticGunMixin#getMaxDamage}
     * 给 {@code tacz:modern_kinetic_gun} 赋予最大耐久（无配置修饰器时返回 0），
     * 因此 {@code isGun && getMaxDamage > 0} 即精确命中所有有耐久的枪械。</p>
     */
    public static boolean isGundbGun(ItemStack stack) {
        return !stack.isEmpty() && GunsmithAccess.isGun(stack) && stack.getMaxDamage() > 0;
    }

    /** 只读访问底层 custom_data 的副本。 */
    public static CompoundTag customDataCopy(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private GunsmithData() {
    }
}
