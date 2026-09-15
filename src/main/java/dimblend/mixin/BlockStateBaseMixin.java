package dimblend.mixin;

import dimblend.compat.CopycatObsidianHardness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Copycat blocks override {@code Block#getDestroyProgress} to use the applied
 * material and never call {@code super}, so {@link BlockBehaviourMixin} cannot
 * retarget mining. Intercepting {@link BlockBehaviour.BlockStateBase} runs
 * before that virtual dispatch.
 *
 * <p>Guards stay cheap: {@link CopycatObsidianHardness#isCopycat} is a
 * per-block cache, and this method is ticked while a player is mining.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void dimblend$copycatObsidianDestroyProgress(
            Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(Blocks.OBSIDIAN.defaultBlockState().getDestroyProgress(player, level, pos));
        }
    }

    @Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
    private void dimblend$copycatObsidianDestroySpeed(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(CopycatObsidianHardness.obsidianDestroyTime());
        }
    }
}
