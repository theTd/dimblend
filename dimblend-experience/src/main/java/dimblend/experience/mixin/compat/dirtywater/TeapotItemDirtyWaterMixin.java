package dimblend.experience.mixin.compat.dirtywater;

import dimblend.experience.compat.dirtywater.DirtyWaterFeedback;
import dimblend.experience.compat.dirtywater.DirtyWaterSourceRules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 手持森罗物语水壶（{@code TeapotItem}）从世界水源装水时拒收 Purity 0 的水。
 *
 * <p>原版 {@code use} 先用 {@code ClipContext.Fluid.SOURCE_ONLY} 射线找方块，再调
 * {@code BucketPickup#pickupBlock} <b>真的把水源抽走</b>，最后才 {@code fillFluid}——事后再拒
 * 水已经没了。所以 HEAD 先把同一个射线做一遍（{@link DirtyWaterSourceRules}），脏水直接
 * {@code fail} 并不进入抽取。两侧都取消（否则客户端会预测出一次取水动画），提示只在服务端发。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.item.TeapotItem", remap = false)
public abstract class TeapotItemDirtyWaterMixin {

    @Inject(
            method = "use(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;)"
                    + "Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1
    )
    private void dimblend$rejectDirtyWorldWater(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (!DirtyWaterSourceRules.isDirtyWaterInReach(level, player)) {
            return;
        }
        if (!level.isClientSide) {
            DirtyWaterFeedback.notifyNow(player);
        }
        cir.setReturnValue(InteractionResultHolder.fail(player.getItemInHand(hand)));
    }
}
