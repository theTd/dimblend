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
 * 柴油机"活塞损坏"的维修入口（2026-11-25 口径，三机共用；右键提示 2026-11-27 改口径）。
 *
 * <ul>
 * <li>手里是 {@code createdieselgenerators:engine_piston}：消耗 1 个，状态 {@link CdgEngineState#repairPiston()}，
 * 标脏 + {@code notifyUpdate}（客户端护目镜红字随之消失），动作条绿字</li>
 * <li>手里是扳手（{@code AllItems.WRENCH} 或 {@code c:tools/wrench} 标签）：{@code CONSUME} + 动作条
 * {@link CdgEngineDamage#WRENCH_DENIED_KEY}（"坏掉了 要先修好"）——非潜行扳手右键也挡，
 * 免得"先转再拆"把损坏洗掉（组合式的转动还能把多格结构拧散）；
 * 潜行扳手拆的那条路见 {@code CdgEngineDamageEvents}</li>
 * <li>手里是别的物品：{@code FAIL} + 动作条 {@link CdgEngineDamage#HINT_KEY}（"已损坏 需更换活塞"）</li>
 * <li>未损坏：完全不干预，原版 {@code useItemOn}（升级件/扳手）照常</li>
 * </ul>
 *
 * <p><b>为什么扳手给 {@code CONSUME} 而不是 {@code FAIL}</b>：{@code ServerPlayerGameMode#useItemOn}
 * （{@code mcsrc-1.21.1} {@code ServerPlayerGameMode.java:325-349}）只在
 * {@code iteminteractionresult.consumesAction()} 为真时提前 return，而
 * {@code InteractionResult#consumesAction()}（{@code InteractionResult.java:11}）只认
 * SUCCESS/CONSUME/CONSUME_PARTIAL/SUCCESS_NO_ITEM_USED——{@code FAIL} 不消耗，会继续走
 * {@code p_9268_.useOn(...)}，于是 Create 的 {@code WrenchItem#useOn}（字节码 {@code 86-111}：
 * 潜行 → {@code onSneakWrenched}，否则 {@code onWrenched}）照拧。其它物品保留 {@code FAIL}
 * 是刻意的：这样"拿着方块往机器旁边贴"这类正常放置不被误挡。</p>
 *
 * <p>路由本身是 {@link CdgEngineDamage#decideRightClick} 纯函数（单测直接覆盖），这里只做副作用。
 * 三机的 {@code useItemOn} 都是 {@code protected ItemInteractionResult}，签名一致
 * （{@code javap -p} 已核）；三机超类各不相同（{@code DirectionalKineticBlock} /
 * {@code HorizontalKineticBlock} / {@code Block}），所以本 mixin 不 extends 任何一方。
 * 巨型机 3×3 每格各有自己的 BE 与油箱，哪一格烧坏的就在哪一格换活塞
 * （{@link IEngine#self()} 拿的就是该格 BE）。</p>
 */
@Mixin({DieselEngineBlock.class, ModularDieselEngineBlock.class, HugeDieselEngineBlock.class})
public abstract class CdgEnginePistonRepairMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$pistonRepairOrDeny(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        IEngine engine = dimblend$resolveEngine(level, pos);
        if (engine == null) {
            return;
        }
        CdgEngineState engineState = engine.self().getData(CdgAttachments.ENGINE_STATE);
        CdgEngineDamage.RightClickAction action = CdgEngineDamage.decideRightClick(
                engineState.pistonDamaged,
                stack.is(CDGItems.ENGINE_PISTON.get()),
                CdgEngineDamage.isWrench(stack));
        if (action == CdgEngineDamage.RightClickAction.IGNORE) {
            // 未损坏：交给原版 useItemOn
            return;
        }
        if (level.isClientSide()) {
            // 客户端不预判副作用（提示与消耗都在服务端），但必须回同一个"吞掉"的结果：
            // 本地也走 MultiPlayerGameMode#useItemOn → ItemStack#useOn，不吞就会先本地拧一下再被服务端纠正
            cir.setReturnValue(dimblend$interactionResult(action));
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 刻意用 if 链而不是 switch：mixin 里对枚举做 switch 会让 javac 生成
        // CdgEnginePistonRepairMixin$1（$SwitchMap 持有类），在 mixin 包里多出一个非 mixin 类，
        // 且 Mixin 对 mixin 类的内部类有额外校验（见 AGENTS.md「mixin 包只放 mixin 类」）。
        if (action == CdgEngineDamage.RightClickAction.REPAIR) {
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
        } else if (action == CdgEngineDamage.RightClickAction.DENY_WRENCH) {
            CdgEngineDamage.actionbar(serverPlayer, CdgEngineDamage.WRENCH_DENIED_KEY, ChatFormatting.RED);
        } else {
            // DENY_OTHER（IGNORE 已在上面 return）
            CdgEngineDamage.actionbar(serverPlayer, CdgEngineDamage.HINT_KEY, ChatFormatting.RED);
        }
        cir.setReturnValue(dimblend$interactionResult(action));
    }

    /** 决策 → 原版 {@code useItemOn} 的返回值（扳手必须消耗，理由见类注释）。 */
    private static ItemInteractionResult dimblend$interactionResult(CdgEngineDamage.RightClickAction action) {
        if (action == CdgEngineDamage.RightClickAction.REPAIR) {
            return ItemInteractionResult.SUCCESS;
        }
        if (action == CdgEngineDamage.RightClickAction.DENY_WRENCH) {
            return ItemInteractionResult.CONSUME;
        }
        // DENY_OTHER 保留 FAIL：不消耗，方块放置一类的正常交互还能用
        return ItemInteractionResult.FAIL;
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
