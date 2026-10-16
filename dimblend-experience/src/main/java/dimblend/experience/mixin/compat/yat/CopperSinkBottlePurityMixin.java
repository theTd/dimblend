package dimblend.experience.mixin.compat.yat;

import com.adonis.fluid.block.CopperSink.CopperSinkBlock;
import com.adonis.fluid.block.CopperSink.CopperSinkBlockEntity;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import dimblend.experience.compat.yat.CopperSinkBottleInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CopperSinkBlock.class)
public abstract class CopperSinkBottlePurityMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$exchangeWaterBottle(ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (level.getBlockEntity(pos) instanceof CopperSinkBlockEntity sink
                && (WaterPurity.isWaterBottle(held)
                || held.is(Items.GLASS_BOTTLE) && sink.getTank().getFluid().is(Fluids.WATER))) {
            cir.setReturnValue(CopperSinkBottleInteraction.use(held, sink, player, hand, level));
        }
    }
}
