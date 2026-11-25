package dimblend.experience.compat.tavern;

import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterItemPurity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 酒桶"灭菌水"口径（LOCKED DECISION：酒桶只能装水，桶里的水固定 {@code Purity: 2}）。
 *
 * <p>四件事集中在这里，避免 tavern mixin 各自散落常量：</p>
 * <ol>
 *   <li>{@link #stampTank}：倒进桶里的水（桶/壶/管道都一样）一律打 {@code Purity: 2}；</li>
 *   <li>{@link #stampContainer}：从桶里取水时给装出来的容器补 {@code Purity: 2}
 *       （原版水桶会被 {@code FluidBucketWrapper} 剥掉组件，必须显式补）；</li>
 *   <li>{@link #hasPurity}/{@link #isWater}：判定；</li>
 *   <li>{@link #BARREL_WATER_PURITY}：唯一取值口。</li>
 * </ol>
 *
 * <p>本类引用 YAT 类型，只在 {@code DirtyWaterCompat.purityAvailable()} 为真时调用。</p>
 */
public final class BarrelWaterPurity {

    /** 酒桶水的固定纯度（0 脏 / 1 微脏 / 2 可接受 / 3 纯净）。 */
    public static final int BARREL_WATER_PURITY = 2;

    /** 是否是水（非空且 {@code Fluids.WATER}）。 */
    public static boolean isWater(FluidStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Fluids.WATER);
    }

    /** 把桶里的水栈打上固定纯度（水以外的流体不动，原地改组件）。 */
    public static void stampTank(FluidStack stack) {
        if (isWater(stack)) {
            DirtyWaterPurity.stampFluidPurity(stack, BARREL_WATER_PURITY);
        }
    }

    /** 给"已装水的容器物品"打固定纯度（只在水容器上生效）。 */
    public static void stampContainer(ItemStack stack, int purity) {
        if (stack != null && !stack.isEmpty() && DirtyWaterPurity.isWaterContainer(stack)) {
            WaterItemPurity.write(stack, purity);
        }
    }

    /** 容器是否已带 Purity 组件（只补不覆盖）。 */
    public static boolean hasPurity(ItemStack stack) {
        return WaterItemPurity.has(stack);
    }

    private BarrelWaterPurity() {
    }
}
