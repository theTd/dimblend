package dimblend.experience.mixin;

import dimblend.experience.water.SurvivalWaterlogRules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 生存/冒险模式下手持水桶右键可含水方块时静默中止：不创建含水方块、不消耗水、无挥手/音效/提示。
 */
@Mixin(BucketItem.class)
public abstract class SurvivalWaterlogBlockMixin extends Item {

    private SurvivalWaterlogBlockMixin(Properties properties) {
        super(properties);
    }

    @Inject(
            method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1
    )
    private void dimblend$blockSurvivalWaterlog(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir
    ) {
        BucketItem self = (BucketItem) (Object) this;
        if (player.hasInfiniteMaterials()) {
            return;
        }
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (SurvivalWaterlogRules.shouldAbortBucketUseOnBlock(self, player, level, hit.getBlockPos())) {
            cir.setReturnValue(InteractionResultHolder.pass(player.getItemInHand(hand)));
        }
    }
}
