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
 * 森罗物语汤锅（{@code kaleidoscope_cookery:stockpot}）：手持添加汤底时拒收 Purity 0 的水。
 *
 * <p>{@code addSoupBase} 只认"物品是不是水桶"（{@code FluidSoupBase#isSoupBase}），
 * 纯度在桶的 {@code Purity} 标签上，原版完全不看。HEAD 拒收不扣物品、不改锅状态；
 * 汤锅本身没有方块流体能力，管路本来就不填它，自动加水只有铜龙头一条路
 * （{@code CookeryCopperTapSupport} 另做纯度闸门）。</p>
 *
 * <p>目标类不在编译期 classpath 上，按 {@code @Pseudo + targets} 声明；由 mixin 插件按
 * {@code kaleidoscope_cookery} 在场性门控。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity", remap = false)
public abstract class StockpotDirtyWaterMixin {

    @Inject(
            method = "addSoupBase(Lnet/minecraft/world/level/Level;"
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
