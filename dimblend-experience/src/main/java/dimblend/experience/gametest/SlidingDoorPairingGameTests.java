package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlockEntity;
import dimblend.experience.compat.create.SlidingDoorPairing;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * End-to-end GameTests for single-cell centered 2-wide Create sliding door pairing.
 *
 * <p>No {@code @GameTestHolder}: references Create classes and is registered conditionally in
 * {@code DimBlend.java} when {@code create} is loaded. Uses the 7x5x7 {@code density_chamber}
 * template so all coordinates stay strictly within plot bounds.</p>
 */
@PrefixGameTestTemplate(false)
public final class SlidingDoorPairingGameTests {
    private SlidingDoorPairingGameTests() {
    }

    @GameTest(template = "density_chamber", templateNamespace = "dimblend_experience", timeoutTicks = 60)
    public static void pairsAllFourTargetDoorsInSingleCellAndRejectsFramedGlass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = survivalPlayer(level);

        Block[] targets = {
                AllBlocks.ANDESITE_DOOR.get(),
                AllBlocks.BRASS_DOOR.get(),
                AllBlocks.COPPER_DOOR.get(),
                AllBlocks.TRAIN_DOOR.get()
        };

        BlockPos absLower = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int i = 0; i < targets.length; i++) {
            Block doorBlock = targets[i];
            DoorHingeSide hinge = (i % 2 == 0) ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            placeSingleDoor(level, absLower, doorBlock, Direction.SOUTH, hinge);

            // Occupy an adjacent block on one iteration to prove pairing stays 1 logical block
            if (i == 1) {
                level.setBlock(absLower.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }

            ItemStack held = new ItemStack(doorBlock.asItem(), 3);
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            player.setShiftKeyDown(false);

            BlockPos clicked = (i % 2 == 0) ? absLower : absLower.above();
            PlayerInteractEvent.RightClickBlock pairClick = rightClick(player, clicked);
            NeoForge.EVENT_BUS.post(pairClick);

            helper.assertTrue(pairClick.isCanceled(), "same-door right-click must cancel placement for " + doorBlock);
            helper.assertTrue(held.getCount() == 2, "pairing in survival must consume 1 door item for " + doorBlock);

            BlockState lowerState = level.getBlockState(absLower);
            BlockState upperState = level.getBlockState(absLower.above());
            helper.assertTrue(SlidingDoorPairing.isPaired(lowerState), "LOWER must be PAIRED=true");
            helper.assertTrue(SlidingDoorPairing.isPaired(upperState), "UPPER must be PAIRED=true");
            helper.assertFalse(lowerState.getValue(SlidingDoorBlock.VISIBLE), "paired LOWER must keep VISIBLE=false");
            helper.assertFalse(upperState.getValue(SlidingDoorBlock.VISIBLE), "paired UPPER must keep VISIBLE=false");

            if (i != 1) {
                for (Direction horizontal : Direction.Plane.HORIZONTAL) {
                    helper.assertTrue(
                            level.getBlockState(absLower.relative(horizontal)).isAir(),
                            "adjacent cell must remain untouched AIR");
                }
            }

            // Right-clicking an already-paired door with the same door item toggles open/closed cleanly
            PlayerInteractEvent.RightClickBlock toggleOpen = rightClick(player, absLower);
            NeoForge.EVENT_BUS.post(toggleOpen);
            helper.assertTrue(toggleOpen.isCanceled(), "clicking paired door with same item must cancel placement");
            helper.assertTrue(held.getCount() == 2, "toggling paired door must not consume door item");
            helper.assertTrue(level.getBlockState(absLower).getValue(DoorBlock.OPEN), "paired door must toggle open");
            helper.assertTrue(SlidingDoorPairing.isPaired(level.getBlockState(absLower)), "PAIRED must persist");

            PlayerInteractEvent.RightClickBlock toggleClose = rightClick(player, absLower.above());
            NeoForge.EVENT_BUS.post(toggleClose);
            helper.assertFalse(level.getBlockState(absLower).getValue(DoorBlock.OPEN), "paired door must toggle closed");
            helper.assertTrue(held.getCount() == 2, "closing paired door must not consume door item");

            // Clean up before next iteration so adjacent-AIR check stays isolated
            level.setBlock(absLower.above(), Blocks.AIR.defaultBlockState(), 50);
            level.setBlock(absLower, Blocks.AIR.defaultBlockState(), 50);
            if (i == 1) {
                level.setBlock(absLower.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        // Framed glass door must be excluded from pairing
        BlockPos glassLower = helper.absolutePos(new BlockPos(3, 1, 3));
        placeSingleDoor(level, glassLower, AllBlocks.FRAMED_GLASS_DOOR.get(), Direction.SOUTH, DoorHingeSide.LEFT);
        helper.assertFalse(
                SlidingDoorPairing.isTargetDoor(level.getBlockState(glassLower)),
                "framed_glass_door must not be a target door");
        ItemStack glassHeld = new ItemStack(AllBlocks.FRAMED_GLASS_DOOR.asItem(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, glassHeld);
        PlayerInteractEvent.RightClickBlock glassClick = rightClick(player, glassLower);
        NeoForge.EVENT_BUS.post(glassClick);
        helper.assertFalse(glassClick.isCanceled(), "framed_glass_door right-click must not be intercepted");
        helper.assertFalse(SlidingDoorPairing.isPaired(level.getBlockState(glassLower)), "framed_glass_door stays unpaired");
        helper.assertTrue(glassHeld.getCount() == 2, "framed_glass_door stack must remain untouched");

        helper.succeed();
    }

    @GameTest(template = "density_chamber", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void pairedCollisionAndOutlineShapesFollowClosedAndOpenRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos brassPos = helper.absolutePos(new BlockPos(3, 1, 3));
        placeSingleDoor(level, brassPos, AllBlocks.BRASS_DOOR.get(), Direction.SOUTH, DoorHingeSide.LEFT);
        helper.assertTrue(SlidingDoorPairing.pair(level, brassPos), "brass_door must pair");

        BlockState closedState = level.getBlockState(brassPos);
        helper.assertTrue(closedState.hasLargeCollisionShape(), "closed paired door must cache largeCollisionShape=true");
        VoxelShape closedCollision = closedState.getCollisionShape(level, brassPos);
        helper.assertFalse(closedCollision.isEmpty(), "closed paired collision shape must be non-empty");
        helper.assertTrue(Math.abs(closedCollision.min(Direction.Axis.X) - (-0.5)) < 1.0e-6, "closed minX == -0.5");
        helper.assertTrue(Math.abs(closedCollision.max(Direction.Axis.X) - 1.5) < 1.0e-6, "closed maxX == 1.5");

        ((DoorBlock) closedState.getBlock()).setOpen(null, level, closedState, brassPos, true);
        BlockState openState = level.getBlockState(brassPos);
        helper.assertTrue(openState.getValue(DoorBlock.OPEN), "door must be OPEN");
        helper.assertTrue(SlidingDoorPairing.isPaired(openState), "PAIRED persists when opened");
        helper.assertTrue(
                openState.getCollisionShape(level, brassPos).isEmpty(),
                "open paired door must have empty collision shape");

        VoxelShape openOutline = openState.getShape(level, brassPos);
        helper.assertFalse(openOutline.isEmpty(), "open paired door outline shape must be non-empty");
        helper.assertTrue(openOutline.toAabbs().size() == 2, "open paired door outline must contain 2 leaves");
        helper.assertTrue(Math.abs(openOutline.min(Direction.Axis.X) - (-21.0 / 16.0)) < 1.0e-6, "open sliding minX");
        helper.assertTrue(Math.abs(openOutline.max(Direction.Axis.X) - (37.0 / 16.0)) < 1.0e-6, "open sliding maxX");

        helper.succeed();
    }

    @GameTest(template = "density_chamber", templateNamespace = "dimblend_experience", timeoutTicks = 60)
    public static void pairedRedstoneMatchesSingleDoorAndNeverCouplesWithAdjacentDoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pairedPos = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos neighborDoorPos = pairedPos.west();

        placeSingleDoor(level, pairedPos, AllBlocks.BRASS_DOOR.get(), Direction.SOUTH, DoorHingeSide.LEFT);
        placeSingleDoor(level, neighborDoorPos, AllBlocks.BRASS_DOOR.get(), Direction.SOUTH, DoorHingeSide.RIGHT);
        helper.assertTrue(SlidingDoorPairing.pair(level, pairedPos), "paired door setup");

        // Clear initial deferUpdate tick on both BEs before redstone transitions
        if (level.getBlockEntity(pairedPos) instanceof SlidingDoorBlockEntity be) {
            be.tick();
        }
        if (level.getBlockEntity(neighborDoorPos) instanceof SlidingDoorBlockEntity be) {
            be.tick();
        }

        // Power only the paired door: it must open while the adjacent single door stays closed
        BlockPos powerPos = pairedPos.east();
        level.setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);

        BlockState poweredPaired = level.getBlockState(pairedPos);
        helper.assertTrue(poweredPaired.getValue(DoorBlock.OPEN), "redstone must open paired door");
        helper.assertTrue(poweredPaired.getValue(DoorBlock.POWERED), "paired door must set POWERED=true");
        helper.assertTrue(SlidingDoorPairing.isPaired(poweredPaired), "PAIRED=true must persist on LOWER");
        helper.assertTrue(SlidingDoorPairing.isPaired(level.getBlockState(pairedPos.above())), "PAIRED=true on UPPER");
        helper.assertFalse(
                level.getBlockState(neighborDoorPos).getValue(DoorBlock.OPEN),
                "adjacent single door must not couple with paired door");

        // Unpower the paired door and tick BE past animation settle: VISIBLE must remain false forever
        level.setBlock(powerPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pairedPos) instanceof SlidingDoorBlockEntity be) {
            for (int t = 0; t < 12; t++) {
                be.tick();
            }
        }
        BlockState closedPaired = level.getBlockState(pairedPos);
        helper.assertFalse(closedPaired.getValue(DoorBlock.OPEN), "paired door must close when unpowered");
        helper.assertTrue(SlidingDoorPairing.isPaired(closedPaired), "PAIRED=true must persist after close");
        helper.assertFalse(closedPaired.getValue(SlidingDoorBlock.VISIBLE), "VISIBLE=false must persist after settle");
        helper.assertFalse(
                level.getBlockState(pairedPos.above()).getValue(SlidingDoorBlock.VISIBLE),
                "UPPER VISIBLE=false must persist after settle");

        // Power only the adjacent single door: paired door must stay closed
        BlockPos singlePowerPos = neighborDoorPos.west();
        level.setBlock(singlePowerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(level.getBlockState(neighborDoorPos).getValue(DoorBlock.OPEN), "adjacent single door opens");
        helper.assertFalse(level.getBlockState(pairedPos).getValue(DoorBlock.OPEN), "paired door ignores neighbor power");
        level.setBlock(singlePowerPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        helper.succeed();
    }

    @GameTest(template = "density_chamber", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void normalWrenchIsDisabledAndSneakWrenchDismantlesPairIntoTwoDoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = survivalPlayer(level);
        ItemStack wrench = AllItems.WRENCH.asStack();
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);

        // 1. Normal wrench does nothing on single OR paired target doors
        BlockPos singlePos = helper.absolutePos(new BlockPos(1, 1, 3));
        placeSingleDoor(level, singlePos, AllBlocks.COPPER_DOOR.get(), Direction.SOUTH, DoorHingeSide.LEFT);
        BlockPos pairedPos = helper.absolutePos(new BlockPos(4, 1, 3));
        placeSingleDoor(level, pairedPos, AllBlocks.BRASS_DOOR.get(), Direction.EAST, DoorHingeSide.RIGHT);
        helper.assertTrue(SlidingDoorPairing.pair(level, pairedPos), "brass_door pair setup");

        player.setShiftKeyDown(false);
        for (BlockPos target : List.of(singlePos, pairedPos)) {
            Direction beforeFacing = level.getBlockState(target).getValue(DoorBlock.FACING);
            boolean beforeOpen = level.getBlockState(target).getValue(DoorBlock.OPEN);

            PlayerInteractEvent.RightClickBlock event = rightClick(player, target);
            NeoForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "non-sneak wrench RightClickBlock must be canceled");
            helper.assertTrue(
                    event.getCancellationResult() == InteractionResult.PASS,
                    "non-sneak wrench must cancel with PASS");

            BlockHitResult hit = new BlockHitResult(target.getCenter(), Direction.UP, target, false);
            InteractionResult directResult = wrench.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(directResult == InteractionResult.PASS, "onWrenched must return PASS");
            helper.assertTrue(
                    level.getBlockState(target).getValue(DoorBlock.FACING) == beforeFacing,
                    "normal wrench must not rotate door");
            helper.assertTrue(
                    level.getBlockState(target).getValue(DoorBlock.OPEN) == beforeOpen,
                    "normal wrench must not toggle door");
        }

        // 2. Sneak-wrench on UPPER of paired door removes both halves and gives 2 doors
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        player.setShiftKeyDown(true);

        List<ItemStack> strayDrops = captureDropsAround(level, pairedPos, () -> {
            BlockHitResult hitUpper = new BlockHitResult(
                    pairedPos.above().getCenter(), Direction.NORTH, pairedPos.above(), false);
            InteractionResult res = wrench.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hitUpper));
            helper.assertTrue(res == InteractionResult.SUCCESS, "sneak-wrench on paired door must succeed");
        });

        helper.assertTrue(level.getBlockState(pairedPos).isAir(), "sneak-wrench must remove LOWER");
        helper.assertTrue(level.getBlockState(pairedPos.above()).isAir(), "sneak-wrench must remove UPPER");
        helper.assertTrue(strayDrops.isEmpty(), "sneak-wrench must not trigger extra loot drops");
        helper.assertTrue(
                player.getInventory().countItem(AllBlocks.BRASS_DOOR.asItem()) == 2,
                "sneak-wrench must return exactly 2 brass doors");

        helper.succeed();
    }

    @GameTest(template = "density_chamber", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void survivalBreakOnLowerOrUpperDropsExactlyTwoDoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = survivalPlayer(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));

        // Break LOWER in Survival
        BlockPos lowerBreakPos = helper.absolutePos(new BlockPos(1, 1, 3));
        placeSingleDoor(level, lowerBreakPos, AllBlocks.TRAIN_DOOR.get(), Direction.SOUTH, DoorHingeSide.LEFT);
        helper.assertTrue(SlidingDoorPairing.pair(level, lowerBreakPos), "pair train_door for LOWER break");

        List<ItemStack> lowerDrops = captureDropsAround(level, lowerBreakPos, () -> {
            boolean broke = player.gameMode.destroyBlock(lowerBreakPos);
            helper.assertTrue(broke, "survival destroyBlock(LOWER) must succeed");
        });
        helper.assertTrue(level.getBlockState(lowerBreakPos).isAir(), "LOWER must be air");
        helper.assertTrue(level.getBlockState(lowerBreakPos.above()).isAir(), "UPPER must be air");
        assertExactItemCount(helper, lowerDrops, AllBlocks.TRAIN_DOOR.asItem(), 2, "breaking LOWER");

        // Break UPPER in Survival
        BlockPos upperBreakBase = helper.absolutePos(new BlockPos(4, 1, 3));
        placeSingleDoor(level, upperBreakBase, AllBlocks.ANDESITE_DOOR.get(), Direction.NORTH, DoorHingeSide.RIGHT);
        helper.assertTrue(SlidingDoorPairing.pair(level, upperBreakBase), "pair andesite_door for UPPER break");

        List<ItemStack> upperDrops = captureDropsAround(level, upperBreakBase, () -> {
            boolean broke = player.gameMode.destroyBlock(upperBreakBase.above());
            helper.assertTrue(broke, "survival destroyBlock(UPPER) must succeed");
        });
        helper.assertTrue(level.getBlockState(upperBreakBase).isAir(), "LOWER must be air after UPPER break");
        helper.assertTrue(level.getBlockState(upperBreakBase.above()).isAir(), "UPPER must be air after UPPER break");
        assertExactItemCount(helper, upperDrops, AllBlocks.ANDESITE_DOOR.asItem(), 2, "breaking UPPER");

        helper.succeed();
    }

    private static void placeSingleDoor(
            ServerLevel level, BlockPos lowerPos, Block doorBlock, Direction facing, DoorHingeSide hinge) {
        level.setBlock(lowerPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState lower = doorBlock.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false);
        BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        level.setBlock(lowerPos, lower, Block.UPDATE_ALL);
        level.setBlock(lowerPos.above(), upper, Block.UPDATE_ALL);
    }

    private static List<ItemStack> captureDropsAround(ServerLevel level, BlockPos lowerPos, Runnable action) {
        List<ItemStack> captured = new ArrayList<>();
        BlockPos upperPos = lowerPos.above();
        Consumer<BlockDropsEvent> listener = event -> {
            if (event.getLevel() == level
                    && (event.getPos().equals(lowerPos) || event.getPos().equals(upperPos))) {
                event.getDrops().forEach(drop -> captured.add(drop.getItem().copy()));
                event.getDrops().clear();
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockDropsEvent.class, listener);
        try {
            action.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return captured;
    }

    private static void assertExactItemCount(
            GameTestHelper helper, List<ItemStack> drops, Item expectedItem, int expectedCount, String context) {
        helper.assertTrue(
                drops.stream().allMatch(stack -> stack.is(expectedItem)),
                context + " must drop only " + expectedItem + ", got: " + drops);
        int total = drops.stream().mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(
                total == expectedCount,
                context + " expected " + expectedCount + " items, got " + total + " (" + drops + ")");
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(
                player,
                InteractionHand.MAIN_HAND,
                pos,
                new BlockHitResult(pos.getCenter(), Direction.NORTH, pos, false));
    }

    private static ServerPlayer survivalPlayer(ServerLevel level) {
        ServerPlayer player = new ServerPlayer(
                level.getServer(),
                level,
                new GameProfile(UUID.randomUUID(), "door-pair-test"),
                ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(
                level.getServer(),
                new Connection(PacketFlow.SERVERBOUND),
                player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
            }
        };
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
}
