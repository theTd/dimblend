package dimblend.experience.mixin.compat.coldsweat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.momosoftworks.coldsweat.common.item.WaterskinItem;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.WaterItemPurity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 缺口 7（装水）：Cold Sweat 水袋从水源/炼药锅装满时记下那一格水的纯度。
 *
 * <p>{@code WaterskinItem#getFilledItem(ItemStack, Level, BlockPos)} 是所有"装水"路径的
 * 产物构造点（{@code handleFillWaterskin} 也走它）。这里包一层：拿到 filled_waterskin 后，
 * 按 {@code pos} 取 {@code WaterPurity.getBlockPurity} 写进物品的 {@code Purity} 组件
 * （水袋不是 YAT 认知的水容器，所以走 custom_data 那条腿，见 {@code WaterItemPurity}）。</p>
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.common.item.WaterskinItem", remap = false)
public abstract class WaterskinPurityMixin {

    @WrapMethod(method = "getFilledItem", remap = false, require = 1, allow = 1)
    private static ItemStack dimblend$stampWaterskinPurity(ItemStack empty, Level level, BlockPos pos,
            Operation<ItemStack> original) {
        ItemStack filled = original.call(empty, level, pos);
        if (!DirtyWaterCompat.purityAvailable() || filled == null || filled.isEmpty() || level == null) {
            return filled;
        }
        if (!WaterItemPurity.has(filled)) {
            WaterItemPurity.write(filled, WaterPurity.getBlockPurity(level, pos));
        }
        return filled;
    }
}
