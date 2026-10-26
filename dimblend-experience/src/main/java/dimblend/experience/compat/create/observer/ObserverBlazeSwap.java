package dimblend.experience.compat.create.observer;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.headlight.MarkedLavaTracker;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Enter and leave the observer blaze mode on a blaze burner that sits on a Sable vehicle. Both paths work on the
 * server only and keep the burner's state across the swap: the block state (so the heat level and facing come
 * back) and the block entity's full NBT (so fuel, remaining burn time, goggles and the creative flag come back).
 *
 * <p>Order matters in both directions: on the way in the burner's NBT is captured first, the block is swapped, and
 * the new block entity stores the capture, because a block entity can only be written once it exists -- a failed
 * swap refunds the spyglass. On the way out the burner is restored first and the spyglass is handed back only after
 * the restore succeeded, so a restore that a block, a border or another mod refuses cannot mint spyglasses.</p>
 */
public final class ObserverBlazeSwap {
    /** Shared entry condition: a spyglass in the interacting hand, build permission, and a burner on a vehicle. */
    public static boolean canEnter(Level level, BlockPos pos, Player player, ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.SPYGLASS)) {
            return false;
        }
        if (player.isSpectator() || !player.getAbilities().mayBuild) {
            return false;
        }
        return isVehicleBurner(level, pos);
    }

    /**
     * Shared exit condition: sneak-right-click an observer blaze with an empty main hand. Deliberately silent about
     * the offhand -- vanilla's sneaking branch would skip the block use for any item in either hand, which is why
     * the exit runs from the interaction event instead ({@code ObserverBlazeInteraction}).
     */
    public static boolean canLeave(Level level, BlockPos pos, Player player) {
        return !player.isSpectator()
                && player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty()
                && level.getBlockState(pos).is(ObserverBlazeBlocks.OBSERVER_BLAZE.get());
    }

    /** Create's burner (not the lit variant, not the empty state, which has no block entity) on a Sable vehicle. */
    public static boolean isVehicleBurner(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).is(AllBlocks.BLAZE_BURNER.get())) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity)) {
            return false;
        }
        // Both sides must agree, so the interaction is cancelled on the client too (no spyglass zoom). Sable keeps
        // separate server and client lookups and only the matching one answers for that side.
        return level.isClientSide
                ? Sable.HELPER.getContainingClient(pos) != null
                : Sable.HELPER.getContaining(level, pos) != null;
    }

    /** Server side of the entry: capture, swap, store, charge and play the level-up sound at the world position. */
    public static void enter(ServerLevel level, BlockPos pos, Player player, InteractionHand hand) {
        BlockState burnerState = level.getBlockState(pos);
        BlockEntity burner = level.getBlockEntity(pos);
        if (!(burner instanceof BlazeBurnerBlockEntity) || !burnerState.is(AllBlocks.BLAZE_BURNER.get())) {
            return;
        }
        CompoundTag captured = burner.saveWithFullMetadata(level.registryAccess());
        ItemStack held = player.getItemInHand(hand);
        boolean creative = player.getAbilities().instabuild;
        if (!creative) {
            held.shrink(1);
        }
        if (!level.setBlock(pos, ObserverBlazeBlocks.OBSERVER_BLAZE.get().defaultBlockState(), Block.UPDATE_ALL)) {
            if (!creative) {
                held.grow(1);
            }
            return;
        }
        if (level.getBlockEntity(pos) instanceof ObserverBlazeBlockEntity observer) {
            observer.storeReplacedBurner(burnerState, captured);
        }
        // The burner lives in a distant Sable plot; the feedback belongs at its world position.
        Vec3 center = MarkedLavaTracker.worldCenter(level, pos);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /**
     * Server side of the exit: restore the burner and its NBT, refresh a basin above it, and only then hand the
     * spyglass back. If the restore fails the observer stays an observer and the spyglass with it, so emptying the
     * hand and clicking again can never farm spyglasses.
     */
    public static void leave(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof ObserverBlazeBlockEntity observer)) {
            return;
        }
        BlockState restored = observer.originalState();
        CompoundTag data = observer.originalData();
        if (restored == null || !level.setBlock(pos, restored, Block.UPDATE_ALL)) {
            return;
        }
        if (data != null && level.getBlockEntity(pos) instanceof BlockEntity burner) {
            burner.loadWithComponents(data, level.registryAccess());
            burner.setChanged();
            level.sendBlockUpdated(pos, restored, restored, Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(pos.above()) instanceof BasinBlockEntity basin) {
            basin.notifyChangeOfContents();
        }
        returnSpyglass(player);
    }

    /** The main hand is empty by construction (that is the exit trigger), so the spyglass goes there first. */
    private static void returnSpyglass(ServerPlayer player) {
        ItemStack spyglass = new ItemStack(Items.SPYGLASS);
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, spyglass);
        } else if (!player.getInventory().add(spyglass)) {
            player.drop(spyglass, false);
        }
        player.containerMenu.sendAllDataToRemote();
    }

    private ObserverBlazeSwap() {
    }
}
