package dimblend.experience.gametest;

import com.crystaelix.simurail.content.SimurailBlocks;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlock;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyMenu;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.experience.Config;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native server interaction tests; run with -PwithChassis and its physics dependencies. */
@PrefixGameTestTemplate(false)
public final class SimurailBogeyInteractionGameTests {
    private static final BlockPos MID = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void emptyHandAndOffhandSneakFollowBogeyMenuRule(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            BlockPos pos = helper.absolutePos(MID);
            for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                ServerPlayer player = player(helper, mode, new AtomicInteger());
                for (boolean inverted : new boolean[] {false, true}) {
                    helper.setBlock(MID, bogey(inverted));
                    for (boolean secondary : new boolean[] {false, true}) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                        player.setShiftKeyDown(secondary);
                        useBothHandsLikeClient(player, pos, Direction.UP);
                        helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                                "both hands empty must open PhysicsBogeyMenu: mode=" + mode
                                        + ", inverted=" + inverted
                                        + ", secondary=" + secondary);
                        player.closeContainer();
                    }
                    // Non-wrench non-placeable item in MAIN_HAND + empty OFF_HAND must NOT open menu
                    // even after vanilla's MAIN_HAND -> OFF_HAND fallthrough!
                    player.setShiftKeyDown(false);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    useBothHandsLikeClient(player, pos, Direction.UP);
                    helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                            "non-wrench items such as stick must bypass PhysicsBogeyMenu across both hands");
                }
            }

            // Offhand placement edge cases: empty main hand + bricks in offhand BOTH standing AND sneaking!
            ServerPlayer survival = player(helper, GameType.SURVIVAL, new AtomicInteger());
            for (boolean sneaking : new boolean[] {false, true}) {
                helper.setBlock(MID, bogey(false));
                helper.setBlock(MID.above(), Blocks.AIR.defaultBlockState());
                survival.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                survival.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BRICKS, 2));
                survival.setShiftKeyDown(sneaking);
                InteractionResult result = useBothHandsLikeClient(survival, pos, Direction.UP);
                helper.assertTrue(result.consumesAction(),
                        "offhand brick placement must succeed (sneaking=" + sneaking + ")");
                helper.assertTrue(!(survival.containerMenu instanceof PhysicsBogeyMenu),
                        "offhand brick placement must not open PhysicsBogeyMenu (sneaking=" + sneaking + ")");
                helper.assertTrue(levelState(survival, pos.above()).is(Blocks.BRICKS),
                        "offhand brick must place above the bogey (sneaking=" + sneaking + ")");
                helper.assertTrue(survival.getOffhandItem().getCount() == 1,
                        "survival offhand stack must decrement by 1 (sneaking=" + sneaking + ")");
                helper.setBlock(MID.above(), Blocks.AIR.defaultBlockState());
            }
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void survivalSettingsAndPlacementWorkOnAssembledVehicle(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        var level = helper.getLevel();
        var world = helper.absolutePos(MID);
        helper.setBlock(MID, bogey(false));
        var vehicle = SubLevelAssemblyHelper.assembleBlocks(
                level, world, List.of(world), new BoundingBox3i(world, world));
        helper.assertTrue(vehicle != null, "bogey fixture must assemble into a Sable vehicle");
        try {
            Config.SIMURAIL_PROTECT.set(true);
            BlockPos plot = BlockPos.containing(
                    vehicle.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
            helper.assertTrue(level.getBlockState(plot).is(SimurailBlocks.PHYSICS_BOGEY.get()),
                    "vehicle bogey must be loaded in its plot");
            ServerPlayer player = player(helper, GameType.SURVIVAL, new AtomicInteger());
            player.setPos(plot.getX() + 0.5, plot.getY() + 3.5, plot.getZ() + 0.5);

            use(player, plot, InteractionHand.MAIN_HAND, Direction.UP);
            helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                    "empty hand must open settings for the vehicle's plot-space bogey");
            player.closeContainer();

            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            player.setShiftKeyDown(true);
            use(player, plot, InteractionHand.MAIN_HAND, Direction.UP);
            helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                    "sneak-wrenching must also open settings on the vehicle's plot-space bogey");
            helper.assertTrue(level.getBlockState(plot).is(SimurailBlocks.PHYSICS_BOGEY.get()),
                    "plot-space bogey must remain intact after sneak-wrenching");
            player.closeContainer();

            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRICKS, 2));
            use(player, plot, InteractionHand.MAIN_HAND, Direction.UP);
            helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                    "placing bricks on a plot-space bogey must not open PhysicsBogeyMenu");
            helper.assertTrue(level.getBlockState(plot.above()).is(Blocks.BRICKS),
                    "bricks must place onto the vehicle's plot-space bogey");
            helper.assertTrue(player.getMainHandItem().getCount() == 1,
                    "survival brick count must decrement cleanly on vehicle bogey");
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
            vehicle.markRemoved();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void wrenchOpensMenuWithoutRotatingOrDismantlingInBothModes(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            BlockPos pos = helper.absolutePos(MID);
            for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                ServerPlayer player = player(helper, mode, new AtomicInteger());
                for (boolean inverted : new boolean[] {false, true}) {
                    BlockState initial = bogey(inverted);
                    helper.setBlock(MID, initial);
                    for (InteractionHand hand : InteractionHand.values()) {
                        for (boolean secondary : new boolean[] {false, true}) {
                            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                            player.setItemInHand(hand, AllItems.WRENCH.asStack());
                            player.setShiftKeyDown(secondary);
                            use(player, pos, hand, Direction.UP);
                            helper.assertTrue(player.containerMenu instanceof PhysicsBogeyMenu,
                                    "wrench must open PhysicsBogeyMenu: mode=" + mode
                                            + ", inverted=" + inverted + ", hand=" + hand
                                            + ", secondary=" + secondary);
                            BlockState after = levelState(player, pos);
                            helper.assertTrue(after.is(SimurailBlocks.PHYSICS_BOGEY.get()),
                                    "bogey must stay in place when wrenched: mode=" + mode
                                            + ", secondary=" + secondary);
                            helper.assertTrue(
                                    after.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST
                                            && after.getValue(PhysicsBogeyBlock.INVERTED) == inverted,
                                    "bogey facing and inverted flag must remain unchanged");
                            player.closeContainer();
                        }
                    }
                }
            }

            // Direct IWrenchable calls (e.g. Deployer) must also PASS without rotating or dismantling.
            BlockState expected = bogey(false);
            helper.setBlock(MID, expected);
            AtomicInteger syncPackets = new AtomicInteger();
            ServerPlayer survival = player(helper, GameType.SURVIVAL, syncPackets);
            survival.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            UseOnContext ctx = new UseOnContext(
                    survival, InteractionHand.MAIN_HAND, hit(pos, Direction.UP));
            PhysicsBogeyBlock bogeyBlock = (PhysicsBogeyBlock) SimurailBlocks.PHYSICS_BOGEY.get();
            helper.assertTrue(bogeyBlock.onWrenched(expected, ctx) == InteractionResult.PASS,
                    "direct onWrenched must return PASS");
            helper.assertTrue(bogeyBlock.onSneakWrenched(expected, ctx) == InteractionResult.PASS,
                    "direct onSneakWrenched must return PASS");
            helper.assertTrue(levelState(survival, pos).equals(expected),
                    "direct wrench hooks must leave bogey unrotated and intact");

            int beforeBreakSync = syncPackets.get();
            helper.assertTrue(!survival.gameMode.destroyBlock(pos),
                    "survival breaking must still be rejected");
            helper.assertTrue(levelState(survival, pos).is(SimurailBlocks.PHYSICS_BOGEY.get()),
                    "protected bogey must survive the break attempt");
            helper.assertTrue(syncPackets.get() > beforeBreakSync,
                    "canceled BreakEvent must resync ServerPlayer inventoryMenu");
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void blocksAndAxialShaftPlaceOnBogeyWithoutOpeningMenu(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            BlockPos pos = helper.absolutePos(MID);
            helper.setBlock(MID, bogey(false));
            helper.setBlock(MID.above(), Blocks.AIR.defaultBlockState());
            helper.setBlock(MID.east(), Blocks.AIR.defaultBlockState());
            ServerPlayer player = player(helper, GameType.SURVIVAL, new AtomicInteger());

            // Standing bricks placement on top of bogey.
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRICKS, 4));
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            use(player, pos, InteractionHand.MAIN_HAND, Direction.UP);
            helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                    "standing bricks placement must not open PhysicsBogeyMenu");
            helper.assertTrue(levelState(player, pos.above()).is(Blocks.BRICKS),
                    "standing bricks must place above bogey");
            helper.assertTrue(player.getMainHandItem().getCount() == 3,
                    "standing bricks stack must decrement by 1");
            helper.setBlock(MID.above(), Blocks.AIR.defaultBlockState());

            // Sneaking stone placement on top of bogey.
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 4));
            use(player, pos, InteractionHand.MAIN_HAND, Direction.UP);
            helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                    "sneaking stone placement must not open PhysicsBogeyMenu");
            helper.assertTrue(levelState(player, pos.above()).is(Blocks.STONE),
                    "sneaking stone must place above bogey");
            helper.assertTrue(player.getMainHandItem().getCount() == 3,
                    "sneaking stone stack must decrement by 1");
            helper.setBlock(MID.above(), Blocks.AIR.defaultBlockState());

            // Axial Create shaft placement on shaft-facing side (EAST for EAST-facing bogey).
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AllBlocks.SHAFT.get(), 4));
            use(player, pos, InteractionHand.MAIN_HAND, Direction.EAST);
            helper.assertTrue(!(player.containerMenu instanceof PhysicsBogeyMenu),
                    "axial shaft placement must not open PhysicsBogeyMenu");
            BlockState shaftState = levelState(player, pos.east());
            helper.assertTrue(AllBlocks.SHAFT.has(shaftState),
                    "shaft must place on the shaft-facing side of the bogey");
            helper.assertTrue(shaftState.getValue(ShaftBlock.AXIS) == Direction.Axis.X,
                    "placed shaft must align axially with the X-facing bogey");
            helper.assertTrue(player.getMainHandItem().getCount() == 3,
                    "shaft stack must decrement by 1");
            helper.setBlock(MID.east(), Blocks.AIR.defaultBlockState());
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void adventureSpectatorAndCouplerRestrictionsRemainInForce(GameTestHelper helper) {
        boolean protect = Config.SIMURAIL_PROTECT.get();
        boolean coupler = Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.get();
        try {
            Config.SIMURAIL_PROTECT.set(true);
            helper.setBlock(MID, bogey(false));
            BlockPos pos = helper.absolutePos(MID);
            for (GameType mode : new GameType[] {
                    GameType.ADVENTURE, GameType.SPECTATOR, GameType.SURVIVAL, GameType.CREATIVE}) {
                AtomicInteger syncs = new AtomicInteger();
                var player = player(helper, mode, syncs);
                var click = new PlayerInteractEvent.RightClickBlock(
                        player, InteractionHand.MAIN_HAND, pos, hit(pos, Direction.UP));
                NeoForge.EVENT_BUS.post(click);
                boolean shouldCancel = mode == GameType.ADVENTURE || mode == GameType.SPECTATOR;
                helper.assertTrue(click.isCanceled() == shouldCancel,
                        "only creative and survival may configure bogeys, mode=" + mode);
                if (shouldCancel) {
                    helper.assertTrue(syncs.get() > 0,
                            "canceled right-click must resync inventory, mode=" + mode);
                }
            }
            helper.setBlock(MID, SimurailBlocks.AUTOMATIC_COUPLER.getDefaultState());
            AtomicInteger couplerSyncs = new AtomicInteger();
            var player = player(helper, GameType.SURVIVAL, couplerSyncs);
            for (boolean allowed : new boolean[] {false, true}) {
                Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(allowed);
                int before = couplerSyncs.get();
                var click = new PlayerInteractEvent.RightClickBlock(
                        player, InteractionHand.MAIN_HAND, pos, hit(pos, Direction.UP));
                NeoForge.EVENT_BUS.post(click);
                helper.assertTrue(click.isCanceled() != allowed,
                        "couplers must retain their independent setting");
                if (!allowed) {
                    helper.assertTrue(couplerSyncs.get() > before,
                            "canceled coupler right-click must resync inventory");
                }
            }
            helper.succeed();
        } finally {
            Config.SIMURAIL_PROTECT.set(protect);
            Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.set(coupler);
        }
    }

    private static BlockState bogey(boolean inverted) {
        return SimurailBlocks.PHYSICS_BOGEY.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(PhysicsBogeyBlock.INVERTED, inverted);
    }

    private static BlockState levelState(ServerPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos);
    }

    private static InteractionResult useBothHandsLikeClient(
            ServerPlayer player, BlockPos pos, Direction face) {
        for (InteractionHand hand : InteractionHand.values()) {
            InteractionResult result = use(player, pos, hand, face);
            if (result.consumesAction()) {
                return result;
            }
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult use(
            ServerPlayer player, BlockPos pos, InteractionHand hand, Direction face) {
        return player.gameMode.useItemOn(
                player, player.level(), player.getItemInHand(hand), hand, hit(pos, face));
    }

    private static BlockHitResult hit(BlockPos pos, Direction face) {
        Vec3 clickPoint = pos.getCenter().add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(clickPoint, face, pos, false);
    }

    private static ServerPlayer player(
            GameTestHelper helper, GameType mode, AtomicInteger inventorySyncCount) {
        var level = helper.getLevel();
        var player = new ServerPlayer(
                level.getServer(),
                level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "bogey-test"),
                ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(
                level.getServer(),
                new Connection(PacketFlow.SERVERBOUND),
                player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundContainerSetContentPacket) {
                    inventorySyncCount.incrementAndGet();
                }
            }
        };
        player.initInventoryMenu();
        player.gameMode.changeGameModeForPlayer(mode);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(MID.above(3))));
        return player;
    }

    private SimurailBogeyInteractionGameTests() {
    }
}
