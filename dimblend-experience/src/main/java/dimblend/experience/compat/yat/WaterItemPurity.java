package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 物品级水纯度读写（YAT {@code Purity} 组件的统一入口）。
 *
 * <p>YAT 的 {@code WaterPurity} 只认"水容器"（水瓶 / 陶碗 / 水桶），对水袋这类自定义容器
 * 直接忽略。所以这里分两条腿：</p>
 * <ol>
 *   <li>水容器：走 {@code WaterPurity.addPurity}/{@code getPurity}，与 YAT 完全互通；</li>
 *   <li>其它物品（水袋等）：直接读写 {@code minecraft:custom_data} 里的同名 {@code Purity}
 *       整型键——YAT 的 {@code FluidPurityHelper} 用的就是这个键，跨模组读得到。</li>
 * </ol>
 *
 * <p>全部方法必须先在 {@code DirtyWaterCompat.purityAvailable()} 为真时调用（本类
 * 直接引用 YAT 类，YAT 缺席时类不可加载；守卫放在调用方，避免把 YAT import 扩散到
 * 非 YAT 门控的 mixin）。</p>
 */
public final class WaterItemPurity {

    /** 与 YAT {@code FluidPurityHelper} 相同的 NBT 键。 */
    public static final String TAG_PURITY = "Purity";

    /** 写入纯度（水容器走 YAT，其它走 custom_data）。 */
    public static void write(ItemStack stack, int purity) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int clamped = WaterSourcePurityRules.clampPurity(purity);
        if (WaterPurity.isWaterFilledContainer(stack)) {
            WaterPurity.addPurity(stack, clamped);
            return;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(TAG_PURITY, clamped);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 读取纯度；没有组件返回 {@code -1}（调用方自行决定默认值）。 */
    public static int read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        if (WaterPurity.hasPurity(stack)) {
            return WaterPurity.getPurity(stack);
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_PURITY) ? WaterSourcePurityRules.clampPurity(tag.getInt(TAG_PURITY)) : -1;
    }

    /** 读取纯度，缺省按"未打标 = 脏水 0"（与 {@link WaterSourcePurityRules#effectivePurity} 同口径）。 */
    public static int readOrDefault(ItemStack stack) {
        int purity = read(stack);
        return purity < 0 ? WaterSourcePurityRules.INFINITE_SOURCE_PURITY : purity;
    }

    /** 是否已有纯度组件（只补不覆盖用）。 */
    public static boolean has(ItemStack stack) {
        return read(stack) >= 0;
    }

    private WaterItemPurity() {
    }
}
