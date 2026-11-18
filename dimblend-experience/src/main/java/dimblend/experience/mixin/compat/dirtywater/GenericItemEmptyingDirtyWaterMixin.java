package dimblend.experience.mixin.compat.dirtywater;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import net.createmod.catnip.data.Pair;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 在 Create 通用物品排空入口保留所有 YAT 水容器（水桶 / 水瓶 / 陶碗 / 木碗）的 {@code Purity}。
 *
 * <p>NeoForge 原生 {@code FluidBucketWrapper#getFluid()} 对 {@code minecraft:water_bucket} 只构造
 * 裸 {@code new FluidStack(Fluids.WATER, 1000)}，会把物品上的 {@code Purity} 丢光——导致已煮沸/已过滤的
 * 净水桶手持倒入工作盆或分液池时退化成无标签原水。这里统一在 {@code GenericItemEmptying#emptyItem}
 * 把容器纯度写进产出流体栈，手持倒盆与分液池排空同享同一份纯度。</p>
 */
@Mixin(value = GenericItemEmptying.class, remap = false)
public abstract class GenericItemEmptyingDirtyWaterMixin {

    @WrapMethod(
            method = "emptyItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Z)"
                    + "Lnet/createmod/catnip/data/Pair;",
            require = 1,
            allow = 1)
    private static Pair<FluidStack, ItemStack> dimblend$preserveWaterContainerPurity(
            Level level, ItemStack stack, boolean simulate, Operation<Pair<FluidStack, ItemStack>> original) {
        boolean waterContainer = DirtyWaterCompat.purityAvailable()
                && DirtyWaterPurity.isWaterContainer(stack);
        int purity = waterContainer ? DirtyWaterPurity.itemPurity(stack) : 0;
        Pair<FluidStack, ItemStack> result = original.call(level, stack, simulate);
        if (waterContainer && result != null && result.getFirst().is(Fluids.WATER)) {
            DirtyWaterPurity.stampFluidPurity(result.getFirst(), purity);
        }
        return result;
    }
}
