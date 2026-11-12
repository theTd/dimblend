package dimblend.experience.compat.create;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-safe interaction, wrench, and drop hooks for Create single-cell paired sliding doors.
 */
public final class SlidingDoorPairingInteraction {
    private static final TagKey<Item> WRENCHES = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private SlidingDoorPairingInteraction() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGH, false,
                PlayerInteractEvent.RightClickBlock.class,
                SlidingDoorPairingInteraction::onRightClick);
        NeoForge.EVENT_BUS.addListener(
                EventPriority.HIGH, false,
                BlockDropsEvent.class,
                SlidingDoorPairingInteraction::onBlockDrops);
    }

    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof WrenchItem || stack.is(WRENCHES));
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!SlidingDoorPairing.isTargetDoor(state)) {
            return;
        }

        Player player = event.getEntity();
        if (player == null || player.isShiftKeyDown()) {
            return;
        }

        ItemStack stack = event.getItemStack();

        // Disable normal (non-sneaking) wrench on all 4 target doors (single & paired),
        // including preventing empty off-hand fallthrough when main hand holds a wrench.
        if (isWrench(stack)
                || (event.getHand() == InteractionHand.OFF_HAND && isWrench(player.getMainHandItem()))) {
            event.setUseItem(TriState.FALSE);
            event.setUseBlock(TriState.FALSE);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.PASS);
            return;
        }

        if (!SlidingDoorPairing.isSameDoorItem(state, stack)) {
            return;
        }

        // Already paired: toggle open/closed cleanly without consuming an item or placing a block.
        if (SlidingDoorPairing.isPaired(state)) {
            InteractionResult toggleResult = state.useWithoutItem(level, player, event.getHitVec());
            event.setUseItem(TriState.FALSE);
            event.setUseBlock(TriState.FALSE);
            event.setCanceled(true);
            event.setCancellationResult(
                    toggleResult.consumesAction()
                            ? toggleResult
                            : InteractionResult.sidedSuccess(level.isClientSide));
            return;
        }

        // Unpaired target door + same door item: upgrade in place to PAIRED=true.
        if (!SlidingDoorPairing.canPair(level, pos, stack)) {
            return;
        }

        BlockPos lower = SlidingDoorPairing.lowerPos(pos, state);
        if (level.isClientSide) {
            if (level.getBlockEntity(lower) instanceof SlidingDoorAnimationAccess animAccess) {
                animAccess.dimblend$setAnimationValue(state.getValue(DoorBlock.OPEN) ? 1.0f : 0.0f);
            }
        } else if (level instanceof ServerLevel serverLevel) {
            if (SlidingDoorPairing.pair(serverLevel, pos)) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                SoundType soundType = state.getSoundType(serverLevel, pos, player);
                serverLevel.playSound(
                        null,
                        pos,
                        soundType.getPlaceSound(),
                        SoundSource.BLOCKS,
                        (soundType.getVolume() + 1.0f) * 0.5f,
                        soundType.getPitch() * 0.8f);
            }
        }

        event.setUseItem(TriState.FALSE);
        event.setUseBlock(TriState.FALSE);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    /**
     * Ensures harvesting either {@code LOWER} or {@code UPPER} of a paired door in Survival
     * (or explosion / direct {@code destroyBlock(..., true)}) drops 2 doors instead of 1.
     */
    private static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        if (!SlidingDoorPairing.isPaired(state)) {
            return;
        }
        if (!state.hasProperty(DoorBlock.HALF) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        Item doorItem = state.getBlock().asItem();
        int existingCount = 0;
        for (ItemEntity drop : event.getDrops()) {
            if (drop.getItem().is(doorItem)) {
                existingCount += drop.getItem().getCount();
            }
        }
        if (existingCount <= 0 || existingCount >= 2) {
            return;
        }

        ServerLevel level = event.getLevel();
        BlockPos pos = event.getPos();
        ItemEntity extra = new ItemEntity(
                level,
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                new ItemStack(doorItem, 2 - existingCount));
        extra.setDefaultPickUpDelay();
        event.getDrops().add(extra);
    }

    /**
     * Dismantles a paired door via sneak-wrench, removing both halves cleanly without duplicate
     * neighbor-shape drop triggers and returning 2 door items.
     */
    public static InteractionResult onSneakWrenchedPaired(BlockState state, UseOnContext context) {
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        if (!(world instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(world, pos, world.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(breakEvent);
        if (breakEvent.isCanceled()) {
            return InteractionResult.SUCCESS;
        }

        BlockPos lowerPos = SlidingDoorPairing.lowerPos(pos, state);
        BlockPos upperPos = lowerPos.above();

        if (serverLevel.getBlockState(upperPos).is(state.getBlock())) {
            serverLevel.setBlock(
                    upperPos,
                    serverLevel.getFluidState(upperPos).createLegacyBlock(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        }

        state.spawnAfterBreak(serverLevel, lowerPos, ItemStack.EMPTY, true);
        if (serverLevel.getBlockState(lowerPos).is(state.getBlock())) {
            serverLevel.destroyBlock(lowerPos, false, player);
        }
        serverLevel.updateNeighborsAt(upperPos, state.getBlock());

        Item doorItem = state.getBlock().asItem();
        if (player != null) {
            if (!player.isCreative()) {
                player.getInventory().placeItemBackInInventory(new ItemStack(doorItem, 2));
            }
        } else {
            Block.popResource(serverLevel, lowerPos, new ItemStack(doorItem, 2));
        }

        IWrenchable.playRemoveSound(serverLevel, pos);
        return InteractionResult.SUCCESS;
    }
}
