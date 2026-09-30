package dimblend.experience.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.exploration.IcePlacementRules;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * G3 配套冰放置禁令的拦截点：原版 {@code BlockItem#place(BlockPlaceContext)} 入口。
 * 玩家右键与 Create 机械手最终都经 {@code BlockItem#useOn} 进入此方法
 * （机械手点空气时不发 {@code RightClickBlock}，事件层拦不全）。判定见
 * {@link IcePlacementRules}；命中返回 {@link InteractionResult#FAIL}，不写方块、不扣物品。
 */
@Mixin(BlockItem.class)
public abstract class IcePlacementBanMixin {

    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true)
    private void dimblend$banIcePlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (IcePlacementRules.shouldBlock((BlockItem) (Object) this, context)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
