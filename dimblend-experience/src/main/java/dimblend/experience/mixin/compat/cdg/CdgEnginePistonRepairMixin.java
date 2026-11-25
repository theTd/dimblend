package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.CDGItems;
import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineDamage;
import dimblend.experience.compat.cdg.CdgEngineState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 柴油机"活塞损坏"的维修入口（2026-11-25 口径，三机共用）。
 *
 * <ul>
 * <li>手里是 {@code createdieselgenerators:engine_piston}：消耗 1 个，状态 {@link CdgEngineState#repairPiston()}，
 * 标脏 + {@code notifyUpdate}（客户端护目镜红字随之消失），动作条绿字</li>
 * <li>手里不是活塞（含扳手）：{@code FAIL} + 动作条红字——顺手把"扳手拆除"也挡在
 * {@code useItemOn} 这一层，避免先转再拆的路径被用来洗掉损坏</li>
 * <li>未损坏：完全不干预，原版 {@code useItemOn}（升级件/扳手）照常</li>
 * </ul>
 *
 * <p>三机的 {@code useItemOn} 都是 {@code protected ItemInteractionResult}，签名一致
 * （{@code javap -p} 已核）。巨型机 3×3 每格各有自己的 BE 与油箱，
 * 哪一格烧坏的就在哪一格换活塞（{@link IEngine#self()} 拿的就是该格 BE）。</p>
 */
@Mixin({DieselEngineBlock.class, ModularDieselEngineBlock.class, HugeDieselEngineBlock.class})
public abstract class CdgEnginePistonRepairMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$pistonRepairOrDeny(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (level.isClientSide()) {
            // 客户端不预判：判定与消耗都在服务端，客户端等同步结果
            return;
        }
        IEngine engine = dimblend$resolveEngine(level, pos);
        if (engine == null) {
            return;
        }
        CdgEngineState engineState = engine.self().getData(CdgAttachments.ENGINE_STATE);
        if (!engineState.pistonDamaged) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (stack.is(CDGItems.ENGINE_PISTON.get())) {
            if (engineState.repairPiston()) {
                engine.self().setChanged();
                // 动力机（普通/组合式）要重算产出转速；巨型机的轴登记由它自己的 tick 恢复
                if (engine.self() instanceof GeneratingKineticBlockEntity generating) {
                    generating.updateGeneratedRotation();
                }
                level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                engine.self().notifyUpdate();
                if (!serverPlayer.isCreative()) {
                    stack.shrink(1);
                }
                CdgEngineDamage.actionbar(serverPlayer, CdgEngineDamage.REPAIRED_KEY, ChatFormatting.GREEN);
            }
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
            return;
        }
        CdgEngineDamage.actionbar(serverPlayer, CdgEngineDamage.HINT_KEY, ChatFormatting.RED);
        cir.setReturnValue(ItemInteractionResult.FAIL);
    }

    /**
     * 组合式非 controller 部件被点击时，损坏状态在 controller 上——统一解析到 controller。
     */
    private static IEngine dimblend$resolveEngine(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ModularDieselEngineBlockEntity modular && !modular.isController()) {
            ModularDieselEngineBlockEntity controller = modular.getControllerBE();
            return controller;
        }
        return be instanceof IEngine engine ? engine : null;
    }
}
