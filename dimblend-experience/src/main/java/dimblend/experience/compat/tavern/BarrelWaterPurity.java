package dimblend.experience.compat.tavern;

import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterItemPurity;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 酒桶"过滤水"口径（LOCKED DECISION：酒桶只能装水，桶里与取出来的水固定 {@code Purity: 1}）。
 *
 * <p>纯度档位与整合包 {@code dimblend-craft} 的 {@code zh_cn.json} 映射一致：
 * {@code 0 = 肮脏 / 1 = 过滤 / 2 = 杀菌 / 3 = 纯净}。酒桶水统一取 <b>1 = 过滤</b>：
 * 它正好是"能进食物链"的最低档（{@code DirtyWaterRules.MIN_ACCEPTABLE_PURITY = 1}），
 * 取出来不会被脏水拒收，也不假装已经杀菌/纯净。</p>
 *
 * <p>四件事集中在这里，避免 tavern mixin 各自散落常量：</p>
 * <ol>
 *   <li>{@link #stampTank}：倒进桶里的水（水桶/壶/管道都一样）一律<b>覆写</b>成 {@code Purity: 1}，
 *       旧存档里残留的 {@code 2}/{@code 3} 也在这里被统一；</li>
 *   <li>{@link #stampContainer}：从桶里取水时给装出来的容器一律<b>覆写</b> {@code Purity: 1}
 *       （原版水桶会被 {@code FluidBucketWrapper} 剥掉组件，必须显式补；容器自带旧纯度也照覆写）；</li>
 *   <li>{@link #withinBarrelTake} / {@link #isTakingBarrelWater}：强制覆写只在酒桶
 *       {@code removeFluid} 的调用栈内生效（ThreadLocal），其它同样调用
 *       {@code FluidUtils.fillItem} 的方块（榨汁桶 {@code PressingTubBlockEntity}）完全不受影响；</li>
 *   <li>{@link #BARREL_WATER_PURITY}：唯一取值口。</li>
 * </ol>
 *
 * <p>本类引用 YAT 类型，{@link #stampTank}/{@link #stampContainer} 只在
 * {@code DirtyWaterCompat.purityAvailable()} 为真时调用；{@link #withinBarrelTake} /
 * {@link #isTakingBarrelWater} 不碰 YAT，单测可以直接驱动。</p>
 */
public final class BarrelWaterPurity {

    /** 酒桶水的固定纯度：1 = 过滤（0 肮脏 / 1 过滤 / 2 杀菌 / 3 纯净）。 */
    public static final int BARREL_WATER_PURITY = 1;

    /** 只在酒桶 {@code removeFluid} 的调用栈内为真；异常退出也会复位。 */
    private static final ThreadLocal<Boolean> TAKING_BARREL_WATER = new ThreadLocal<>();

    /** 是否是水（非空且 {@code Fluids.WATER}）。 */
    public static boolean isWater(FluidStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Fluids.WATER);
    }

    /** 把桶里的水栈覆写成固定纯度（水以外的流体不动，原地改组件）。 */
    public static void stampTank(FluidStack stack) {
        if (isWater(stack)) {
            DirtyWaterPurity.stampFluidPurity(stack, BARREL_WATER_PURITY);
        }
    }

    /** 给"已装水的容器物品"覆写固定纯度（只在水容器上生效；容器原有的纯度不保留）。 */
    public static void stampContainer(ItemStack stack) {
        if (stack != null && !stack.isEmpty() && DirtyWaterPurity.isWaterContainer(stack)) {
            WaterItemPurity.write(stack, BARREL_WATER_PURITY);
        }
    }

    /** 当前线程是否正处在"从酒桶取水"的作用域内（{@code removeFluid → FluidUtils.fillItem}）。 */
    public static boolean isTakingBarrelWater() {
        return Boolean.TRUE.equals(TAKING_BARREL_WATER.get());
    }

    /** 在"从酒桶取水"作用域内执行；正常返回与抛异常都会复位。 */
    public static <T> T withinBarrelTake(Supplier<T> action) {
        TAKING_BARREL_WATER.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            TAKING_BARREL_WATER.remove();
        }
    }

    private BarrelWaterPurity() {
    }
}
