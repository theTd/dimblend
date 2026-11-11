package dimblend.experience.compat.create;

import com.simibubi.create.AllItems;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/** Server side interaction and removal hooks for the optional Create door pairing. */
public final class SlidingDoorPairingInteraction {
    private SlidingDoorPairingInteraction() {
    }

    public static void register() {
        // Run before Create's HIGH-priority wrench listener. The receiveCanceled flag also
        // prevents a later listener from rotating/removing the second half independently.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true,
                PlayerInteractEvent.RightClickBlock.class, SlidingDoorPairingInteraction::onRightClick);
        NeoForge.EVENT_BUS.addListener(SlidingDoorPairingInteraction::onBreak);
        NeoForge.EVENT_BUS.addListener(SlidingDoorPairingInteraction::onExplosion);
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        BlockState state = level.getBlockState(event.getPos());

        if (player.isShiftKeyDown() && event.getItemStack().is(AllItems.WRENCH)
                && SlidingDoorPairing.isSupported(state)
                && isPaired(level, event.getPos(), state)) {
            cancel(event, level);
            if (level instanceof ServerLevel server) {
                removePairWithDrops(server, event.getPos(), state, player);
            }
            return;
        }

        if (!SlidingDoorPairing.canPair(level, event.getPos(), event.getItemStack())) {
            return;
        }
        cancel(event, level);
        if (level instanceof ServerLevel server && SlidingDoorPairing.pair(server, event.getPos(), event.getItemStack())
                && !player.getAbilities().instabuild) {
            event.getItemStack().shrink(1);
        }
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel server)) {
            return;
        }
        BlockState state = event.getState();
        if (!SlidingDoorPairing.isSupported(state) || !isPaired(server, event.getPos(), state)) {
            return;
        }
        // Take over the whole paired door so an upper-half break also produces exactly two
        // item drops and never leaves an isolated half behind.
        removePairWithDrops(server, event.getPos(), state, event.getPlayer());
        event.setCanceled(true);
    }

    private static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<BlockPos> additions = new HashSet<>();
        for (BlockPos pos : List.copyOf(event.getAffectedBlocks())) {
            BlockState state = level.getBlockState(pos);
            if (!SlidingDoorPairing.isSupported(state) || !isPaired(level, pos, state)) {
                continue;
            }
            BlockPos lower = SlidingDoorPairing.lowerPos(pos, state);
            BlockPos other = SlidingDoorPairing.partnerLowerPos(lower, level.getBlockState(lower));
            additions.add(other);
            additions.add(other.above());
        }
        event.getAffectedBlocks().addAll(additions);
    }

    private static boolean isPaired(Level level, BlockPos clicked, BlockState state) {
        BlockPos lower = SlidingDoorPairing.lowerPos(clicked, state);
        BlockState lowerState = level.getBlockState(lower);
        return SlidingDoorPairing.isSupported(lowerState)
                && SlidingDoorPairing.isAlreadyPaired(level, lower, lowerState);
    }

    private static void removePairWithDrops(ServerLevel level, BlockPos clicked, BlockState clickedState, Player player) {
        BlockPos lower = SlidingDoorPairing.lowerPos(clicked, clickedState);
        BlockState source = level.getBlockState(lower);
        if (!SlidingDoorPairing.isSupported(source)) {
            return;
        }
        BlockPos other = SlidingDoorPairing.partnerLowerPos(lower, source);
        BlockState otherState = level.getBlockState(other);
        if (!otherState.is(source.getBlock())) {
            return;
        }

        if (!player.isCreative()) {
            ItemStack tool = player.getMainHandItem();
            Block.getDrops(source, level, lower, null, player, tool)
                    .forEach(stack -> player.getInventory().placeItemBackInInventory(stack));
            Block.getDrops(otherState, level, other, null, player, tool)
                    .forEach(stack -> player.getInventory().placeItemBackInInventory(stack));
        }

        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS | Block.UPDATE_IMMEDIATE;
        clearDoor(level, lower, flags);
        clearDoor(level, other, flags);
    }

    private static void clearDoor(ServerLevel level, BlockPos lower, int flags) {
        BlockState lowerState = level.getBlockState(lower);
        if (SlidingDoorPairing.isSupported(lowerState)) {
            level.setBlock(lower, Blocks.AIR.defaultBlockState(), flags);
            if (level.getBlockState(lower.above()).is(lowerState.getBlock())) {
                level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), flags);
            }
        }
    }

    private static void cancel(PlayerInteractEvent.RightClickBlock event, Level level) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }
}
