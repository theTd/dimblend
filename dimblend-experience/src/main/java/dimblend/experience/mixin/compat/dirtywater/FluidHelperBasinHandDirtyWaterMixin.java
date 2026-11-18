package dimblend.experience.mixin.compat.dirtywater;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import dimblend.experience.compat.dirtywater.BasinDirtyWaterPolicy;
import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterFeedback;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家手持 Purity 0 的脏水容器右键工作盆（{@code create:basin}）且当前盆意图不接受脏水时，
 * 弹出 hotbar 提示"太脏了 至少先过滤一下"并拒绝注入（双端一致返回 {@code false}，不播假倒水动画）。
 */
@Mixin(value = FluidHelper.class, remap = false)
public abstract class FluidHelperBasinHandDirtyWaterMixin {

    @Inject(
            method = "tryEmptyItemIntoBE(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1)
    private static void dimblend$rejectDirtyWaterHandFillOnBasin(
            Level level, Player player, InteractionHand hand, ItemStack heldItem, SmartBlockEntity be,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(be instanceof BasinBlockEntity basin) || !DirtyWaterCompat.purityAvailable()) {
            return;
        }
        if (!DirtyWaterPurity.isDirtyWaterItem(heldItem) || BasinDirtyWaterPolicy.allowsDirtyWater(basin)) {
            return;
        }
        if (!level.isClientSide) {
            DirtyWaterFeedback.notifyNow(player);
        }
        cir.setReturnValue(false);
    }
}
