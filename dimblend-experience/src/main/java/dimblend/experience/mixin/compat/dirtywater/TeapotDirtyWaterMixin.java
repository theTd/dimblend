package dimblend.experience.mixin.compat.dirtywater;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterFeedback;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 森罗物语茶壶方块（{@code kaleidoscope_cookery:teapot}）：手持流体容器注水时拒收 Purity 0 的水。
 *
 * <p>原版 {@code addTeaFluid} 用 {@code FluidUtils.emptyItem} 把水转移进一个一次性
 * {@code FluidTank}，只把流体 id 写进 {@code teaFluidId}，纯度整条链路都会丢。HEAD 拒收
 * 不改壶状态、不扣物品。茶壶同样没有方块流体能力，自动注水只有铜龙头一条路。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity", remap = false)
public abstract class TeapotDirtyWaterMixin {

    @Inject(
            method = "addTeaFluid(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1
    )
    private void dimblend$rejectDirtyWater(Level level, LivingEntity user, ItemStack stack,
            CallbackInfoReturnable<Boolean> cir) {
        if (!DirtyWaterCompat.purityAvailable() || !DirtyWaterPurity.isDirtyWaterItem(stack)) {
            return;
        }
        if (!level.isClientSide) {
            DirtyWaterFeedback.notify(user);
        }
        cir.setReturnValue(false);
    }
}
