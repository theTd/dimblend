package dimblend.radio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.radio.server.RadioSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 唱片机控制：红石输入（neighborChanged 即时重算、空盘不中继强充能）见
 * {@link JukeboxRedstoneInputMixin}；放置与唱片机自身状态变化走 {@code RadioSync} 的
 * {@code BlockEvent.EntityPlaceEvent/NeighborNotifyEvent} 订阅；空盘 getTicker 返回 null
 * （BE 永不 tick），曲终推进由服务端 tick 兜底。本 mixin 仅保留：
 *
 * <ul>
 *   <li>useItemOn（HEAD）：radio 播中被塞盘 → 先停播（删服务端状态+广播），再让原版继续。</li>
 * </ul>
 *
 * <p>{@code JukeboxBlock.getSignal} 播放中恒 15（各面同值）：radio 播时也会对外供电，
 * 行为与原版唱片机一致，保留不改。</p>
 */
@Mixin(JukeboxBlock.class)
public abstract class JukeboxControlMixin {

    @Inject(method = "useItemOn(Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/world/InteractionHand;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;",
            at = @At("HEAD"))
    private void dimblend$stopRadioOnInsert(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        // 有盘/非空不拦截；空盘 + radio 播中 + 手持可塞盘 → 先删电台状态让位原版（不取消原调用）
        if (level instanceof ServerLevel serverLevel && !state.getValue(JukeboxBlock.HAS_RECORD)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof JukeboxBlockEntity jukebox && jukebox.getTheItem().isEmpty()
                    && dimblend.radio.RadioState.get(
                            serverLevel.dimension().location().toString(), pos).isPresent()
                    && stack.has(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE)) {
                RadioSync.onItemChanged(serverLevel, pos);
            }
        }
    }
}
