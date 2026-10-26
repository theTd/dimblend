package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import dimblend.experience.compat.create.TrainHorn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 列车汽笛方块侧挂点（Create 6.0.10 {@code WhistleBlock} 字节码核实）：
 *
 * <ul>
 * <li><b>红石上升沿</b>（2026-10-26 规则 B）：Create 原生 {@code neighborChanged} 只在
 * 信号有无变化时翻转 {@code POWERED}（翻转由 {@code setBlock(..., UPDATE_CLIENTS)} 完成，
 * 不再级联邻居更新），TAIL 处对比翻转前后即得严格边沿：false→true 经
 * {@link TrainHorn#onPoweredEdge} 起播一次；true→false <b>刻意不处理</b>——起播后
 * 片段必须播满全长，断电既不清锁也不下发停止包。持续高电平不产生新上升沿，普通邻接
 * 更新不会重触发。TAIL 处先做一次特殊模式对账（引擎新邻接/移除，
 * {@link TrainHorn#reconcile}），保证起播读到的音高与尺寸已就位。</li>
 * <li><b>扳手循环音高</b>：挂在 {@code useItemOn} HEAD（方块自身的右键交互入口，
 * 与 Create 的「用汽笛物品延长」同一入口）。Create 6.0.10 的扳手点击不走
 * {@code useItemOn}：该方法对非汽笛物品返回 {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}，
 * 由 {@code ServerPlayerGameMode.useItemOn} 继续调 {@code WrenchItem.useOn} →
 * {@code IWrenchable.onWrenched}（{@code WhistleBlock.getRotatedBlockState} 循环 SIZE）。
 * 因此接管时返回 SUCCESS 即可同时挡掉「物品 useOn」这一路，Create 原生扳手动作不会执行；
 * 潜行（原版在进入方块交互前就按蹲姿把点击交给物品）与普通模式返回 false，原样放行。</li>
 * </ul>
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
        // 先对账特殊模式：邻接引擎变化可能在本拍发生，进入转换会重置音高与尺寸
        TrainHorn.reconcile(serverLevel, pos, current);
        BlockState after = level.getBlockState(pos);
        // 只认上升沿：下降沿不清锁、不发停止包（规则 B：起播后播满全长）
        if (!previouslyPowered && after.getValue(WhistleBlock.POWERED)) {
            TrainHorn.onPoweredEdge(serverLevel, pos, after);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$wrenchCyclesPitch(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (TrainHorn.onWrenchUsed(level, pos, state, player, stack)) {
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
        }
    }
}
