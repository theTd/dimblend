package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.RailwayCasingRecovery;
import dimblend.experience.exploration.RotatingDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RailwayCasingRecoveryGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        if (ChassisEvents.available()) event.register(RailwayCasingRecoveryGameTests.class);
    }

    private static ServerLevel rotating(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(RotatingDimension.key());
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void originalLootIsReclaimedAcrossAllRecipePartsAndBoundaries(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        String[] ids = {"create:controls", "create:track_station", "create:track_signal", "create:track_observer",
                "create:train_door", "create:train_trapdoor", "railways:portable_fuel_interface", "railways:track_coupler"};
        for (int i = 0; i < ids.length; i++) {
            var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(ids[i])).orElseThrow();
            helper.assertTrue(block.defaultBlockState().is(RailwayCasingRecovery.RECOVERABLE), "part is included: " + ids[i]);
            BlockPos pos = new BlockPos(512 + i * 4, 100, 33);
            level.getChunkAt(pos);
            assertDrops(helper, nativeDrops(level, pos, block.defaultBlockState()), AllBlocks.RAILWAY_CASING.asItem(), 1);
        }
        for (int z : new int[] {-33, -32, 32, 33}) {
            BlockPos pos = new BlockPos(560, 100, z);
            level.getChunkAt(pos);
            assertDrops(helper, nativeDrops(level, pos, AllBlocks.TRAIN_CONTROLS.getDefaultState()),
                    Math.abs(z) > 32 ? AllBlocks.RAILWAY_CASING.asItem() : AllBlocks.TRAIN_CONTROLS.asItem(), 1);
        }
        var other = helper.getLevel();
        BlockPos otherPos = new BlockPos(560, 100, 33);
        other.getChunkAt(otherPos);
        assertDrops(helper, nativeDrops(other, otherPos, AllBlocks.TRAIN_CONTROLS.getDefaultState()), AllBlocks.TRAIN_CONTROLS.asItem(), 1);
        BlockPos upper = new BlockPos(568, 100, 33);
        level.getChunkAt(upper);
        var upperDrops = nativeDrops(level, upper, AllBlocks.TRAIN_DOOR.getDefaultState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        helper.assertTrue(upperDrops.isEmpty(), "upper door half must not duplicate a casing");
        helper.succeed();
    }

    private static List<ItemStack> nativeDrops(ServerLevel level, BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        // These chunks have no nearby players: inspect native drop dispatch rather than
        // querying entities that are not yet visible to the level's entity lookup.
        var drops = new ArrayList<ItemStack>();
        java.util.function.Consumer<BlockDropsEvent> capture = event -> {
            if (event.getLevel() == level && event.getPos().equals(pos)) {
                event.getDrops().forEach(drop -> drops.add(drop.getItem().copy()));
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockDropsEvent.class, capture);
        try {
            Block.dropResources(state, level, pos, null, null, new ItemStack(Items.DIAMOND_PICKAXE));
        } finally {
            NeoForge.EVENT_BUS.unregister(capture);
        }
        return drops;
    }

    private static void assertDrops(GameTestHelper helper, List<ItemStack> drops, net.minecraft.world.item.Item expected, int count) {
        helper.assertTrue(drops.stream().allMatch(stack -> stack.is(expected)), "only expected item: " + drops);
        helper.assertTrue(drops.stream().mapToInt(ItemStack::getCount).sum() == count, "exactly one recovery per block: " + drops);
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void recoveryPreservesContentsAndCapsDuplicatePartDrops(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos pos = new BlockPos(580, 100, 33);
        var drops = new ArrayList<ItemEntity>();
        drops.add(new ItemEntity(level, 580.5, 100.5, 33.5, new ItemStack(AllBlocks.TRAIN_CONTROLS.asItem(), 5)));
        drops.add(new ItemEntity(level, 580.5, 100.5, 33.5, new ItemStack(AllBlocks.TRAIN_CONTROLS.asItem())));
        drops.add(new ItemEntity(level, 580.5, 100.5, 33.5, new ItemStack(Items.DIAMOND, 2)));
        var event = new BlockDropsEvent(level, pos, AllBlocks.TRAIN_CONTROLS.getDefaultState(), null, drops, null, ItemStack.EMPTY);
        NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(drops.size() == 2 && drops.stream().filter(drop -> drop.getItem().is(AllBlocks.RAILWAY_CASING.asItem()))
                .mapToInt(drop -> drop.getItem().getCount()).sum() == 1, "duplicate part drops become one casing");
        helper.assertTrue(drops.stream().anyMatch(drop -> drop.getItem().is(Items.DIAMOND) && drop.getItem().getCount() == 2), "unrelated contents remain");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void wrenchPickupIsBlockedButRotationAndSafeStripRemain(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        ServerPlayer player = player(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        for (int z : new int[] {-33, -32, 32, 33}) {
            BlockPos pos = new BlockPos(600, 100, z);
            level.getChunkAt(pos);
            for (var state : List.of(AllBlocks.TRAIN_CONTROLS.getDefaultState(), AllBlocks.TRACK_STATION.getDefaultState())) {
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                player.setShiftKeyDown(true);
                var pickup = click(player, pos);
                NeoForge.EVENT_BUS.post(pickup);
                helper.assertTrue(pickup.isCanceled() == (Math.abs(z) > 32), "wrench pickup boundary at Z=" + z);
                player.setShiftKeyDown(false);
                var rotate = click(player, pos);
                NeoForge.EVENT_BUS.post(rotate);
                helper.assertTrue(!rotate.isCanceled(), "ordinary wrench rotation remains available");
                level.removeBlock(pos, false);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void sablePlotAddressesAreProjectedToTheWorldStrip(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        var cars = new ArrayList<dev.ryanhcode.sable.sublevel.ServerSubLevel>();
        for (int z : new int[] {0, 33}) {
            BlockPos pos = new BlockPos(640, 100, z);
            level.getChunkAt(pos);
            level.setBlockAndUpdate(pos, AllBlocks.TRAIN_CONTROLS.getDefaultState());
            var car = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos), new BoundingBox3i(pos, pos));
            cars.add(car);
            BlockPos plot = car.getPlot().getCenterBlock();
            helper.assertTrue(RailwayCasingRecovery.applies(level, plot, level.getBlockState(plot)) == (z > 32), "use visible world Z instead of plot address");
        }
        cars.forEach(dev.ryanhcode.sable.sublevel.ServerSubLevel::markRemoved);
        helper.runAfterDelay(5, helper::succeed);
    }

    private static PlayerInteractEvent.RightClickBlock click(ServerPlayer player, BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }

    private static ServerPlayer player(ServerLevel level) {
        var player = new ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "recovery-test"),
                net.minecraft.server.level.ClientInformation.createDefault());
        new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        };
        return player;
    }
}
