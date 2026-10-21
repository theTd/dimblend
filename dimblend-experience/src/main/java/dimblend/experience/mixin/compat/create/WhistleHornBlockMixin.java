package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import dimblend.experience.compat.create.TrainHorn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车汽笛红石触发：Create 原生 {@code neighborChanged} 只在信号有无变化时翻转
 * {@code POWERED}（翻转由 {@code setBlock(..., UPDATE_CLIENTS)} 完成，不再级联邻居更新），
 * TAIL 处对比翻转前后即得严格的 POWERED false→true 上升沿——播放期锁定与
 * 「播完须断电再上电」语义都由 {@link TrainHorn#onPoweredEdge} 与该沿检测承担，
 * 信号无变化时本钩子恒不触发。
 */
@Mixin(WhistleBlock.class)
public abstract class WhistleHornBlockMixin {

    @Inject(method = "neighborChanged", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$hornOnRisingEdge(BlockState state, Level level, BlockPos pos, Block block,
            BlockPos fromPos, boolean isMoving, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean previouslyPowered = state.getValue(WhistleBlock.POWERED);
        BlockState current = level.getBlockState(pos);
        if (!(current.getBlock() instanceof WhistleBlock)) {
            return;
        }
        if (!previouslyPowered && current.getValue(WhistleBlock.POWERED)) {
            TrainHorn.onPoweredEdge(serverLevel, pos, current);
        }
    }
}
