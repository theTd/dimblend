package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.compat.copycat.CopycatObsidianHardness;
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
 * 伪装方块挖掘硬度统一黑曜石（原版 {@code BlockBehaviour.BlockStateBase} 目标，无条件应用）。
 *
 * <p>伪装方块重写 {@code Block#getDestroyProgress} 转发贴图材质且从不调 {@code super}，
 * {@code Block} 层 mixin 拦截不到；拦截 {@link BlockBehaviour.BlockStateBase} 则跑在
 * 该虚分发之前。
 *
 * <p>守卫保持廉价：先读 {@code copycatObsidianHardness} 开关（关闭即透传原版），
 * 再走 {@link CopycatObsidianHardness#isCopycat} 的按方块缓存；本方法在玩家挖掘时
 * 每 tick 双端各调一次。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class CopycatBlockStateBaseMixin {

    @Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
    private void dimblend$copycatObsidianDestroyProgress(
            Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (!Config.COPYCAT_OBSIDIAN_HARDNESS.get()) {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(Blocks.OBSIDIAN.defaultBlockState().getDestroyProgress(player, level, pos));
        }
    }

    @Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
    private void dimblend$copycatObsidianDestroySpeed(
            BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (!Config.COPYCAT_OBSIDIAN_HARDNESS.get()) {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(CopycatObsidianHardness.obsidianDestroyTime());
        }
    }
}
