package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllMenuTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem.ShoppingList;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.dolls.DollKeeperSeatBlock;
import dimblend.experience.compat.dolls.DollKeeperSeatBlockEntity;
import dimblend.experience.compat.dolls.DollStockKeeperInteraction;
import dimblend.experience.compat.dolls.VanillaDollItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Needs the real Kaleidoscope Doll tag/models (1.4.1), Create and Sable; no synthetic tag mutation. */
@PrefixGameTestTemplate(false)
public final class DollStockKeeperGameTests {
    private static final BlockPos SEAT = new BlockPos(1, 2, 1);

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void survivalInstallationPersistsFullItemColorWaterAndFacing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.BLUE, true);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItemStack original = doll();
        original.setCount(3);
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
        use(player, pos);
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "survival must consume exactly one doll");
        DollKeeperSeatBlockEntity keeper = keeper(level, pos);
        helper.assertTrue(((SeatBlock) keeper.getBlockState().getBlock()).getColor() == DyeColor.BLUE,
                "the original seat colour must survive");
        helper.assertTrue(keeper.getBlockState().getValue(SeatBlock.WATERLOGGED), "waterlogging must survive");
        helper.assertTrue(keeper.getBlockState().getValue(DollKeeperSeatBlock.FACING) == Direction.EAST,
                "the doll must face the paired ticker");
        assertDoll(helper, keeper.doll(), original);
        CompoundTag saved = keeper.saveWithFullMetadata(level.registryAccess());
        DollKeeperSeatBlockEntity restored = new DollKeeperSeatBlockEntity(pos, keeper.getBlockState());
        restored.loadWithComponents(saved, level.registryAccess());
        assertDoll(helper, restored.doll(), original);
        DollKeeperSeatBlockEntity client = new DollKeeperSeatBlockEntity(pos, keeper.getBlockState());
        client.loadWithComponents(keeper.getUpdateTag(level.registryAccess()), level.registryAccess());
        assertDoll(helper, client.doll(), original);
        helper.assertTrue(keeper.getUpdatePacket() != null, "installation must provide a block entity update packet");
        // No duplicate installation, dyeing or sneaking extraction.
        player.setShiftKeyDown(true);
        use(player, pos);
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "a second use must not consume another doll");
        helper.assertTrue(level.getBlockEntity(pos) == keeper, "sneak-use must not extract or replace the keeper");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void onlyTaggedDollsAndExactlyOneSameHeightTickerCanInstall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.WHITE, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 3));
        helper.assertTrue(!DollStockKeeperInteraction.install(level, pos, player, InteractionHand.MAIN_HAND),
                "untagged items must not become keepers");
        player.setItemInHand(InteractionHand.MAIN_HAND, doll());
        level.setBlockAndUpdate(pos.east(), Blocks.AIR.defaultBlockState());
        assertRejected(helper, pos, player, "zero tickers");
        level.setBlockAndUpdate(pos.east().above(), AllBlocks.STOCK_TICKER.getDefaultState());
        assertRejected(helper, pos, player, "ticker one block above");
        level.setBlockAndUpdate(pos.east(), AllBlocks.STOCK_TICKER.getDefaultState());
        level.setBlockAndUpdate(pos.west(), AllBlocks.STOCK_TICKER.getDefaultState());
        assertRejected(helper, pos, player, "two horizontal tickers");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void normalBreakDropsOriginalSeatAndOneCompleteDoll(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.RED, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItemStack original = doll();
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
        use(player, pos);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the installed doll leaves the hand");
        helper.assertTrue(player.gameMode.destroyBlock(pos), "survival player must break the seat");
        assertDrops(helper, pos, original, DyeColor.RED, 1);
        level.destroyBlock(pos, true);
        assertDrops(helper, pos, original, DyeColor.RED, 1);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void tickerRemovalBreaksSeatOnceAndInvalidatesKeeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.LIME, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItemStack original = doll();
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
        use(player, pos);
        DollKeeperSeatBlockEntity oldKeeper = keeper(level, pos);
        helper.assertTrue(DollStockKeeperInteraction.keeperFor(level, pos.east()) == oldKeeper,
                "the paired ticker must resolve the installed keeper");
        level.destroyBlock(pos.east(), false);
        helper.assertTrue(level.getBlockState(pos).isAir(), "removing the ticker must also break its seat");
        oldKeeper.checkTicker(level);
        level.destroyBlock(pos, true);
        helper.assertTrue(DollStockKeeperInteraction.keeperFor(level, pos.east()) == null,
                "the removed seat must not keep a menu alive");
        assertDrops(helper, pos, original, DyeColor.LIME, 1);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void creativeInstallationDoesNotConsumeAndBreakReturnsDoll(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.BLACK, false);
        ServerPlayer player = player(helper, GameType.CREATIVE);
        ItemStack original = doll();
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copyWithCount(2));
        use(player, pos);
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "creative installation must not consume");
        helper.assertTrue(player.gameMode.destroyBlock(pos), "creative must break the keeper");
        // Original Create seats do not drop in creative; the installed doll must still be recoverable.
        assertDrops(helper, pos, original, DyeColor.BLACK, 0);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void keeperUsesNativeRequestMenuAndCannotSeatPeopleOrMobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.YELLOW, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, doll());
        use(player, pos);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        use(player, pos);
        // Create 6.0.10 constructs a StockKeeperCategoryMenu on the server with the request MenuType.
        helper.assertTrue(player.containerMenu.getType() == AllMenuTypes.STOCK_KEEPER_REQUEST.get(),
                "the keeper must open Create's native request menu type");
        helper.assertTrue(!player.isPassenger(), "right-click must never seat the player");
        Pig pig = new Pig(EntityType.PIG, level);
        pig.setPos(pos.getCenter());
        level.getBlockState(pos).getBlock().updateEntityAfterFallOn(level, pig);
        helper.assertTrue(!pig.isPassenger(), "falling mobs must not be seated");
        SeatBlock.sitDown(level, pos, pig);
        SeatBlock.sitDown(level, pos, player);
        helper.assertTrue(!pig.isPassenger() && !player.isPassenger(),
                "even direct Create sitDown calls must not mount a keeper seat");
        for (SeatEntity seat : level.getEntitiesOfClass(SeatEntity.class, new AABB(pos))) {
            seat.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void nativeNetworkLockAndShoppingListBranchesRemainIntact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.PURPLE, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, doll());
        use(player, pos);
        StockTickerBlockEntity ticker = (StockTickerBlockEntity) level.getBlockEntity(pos.east());
        UUID originalFrequency = ticker.behaviour.freqId;
        UUID testFrequency = UUID.randomUUID();
        LogisticsNetwork network = new LogisticsNetwork(testFrequency);
        network.owner = UUID.randomUUID();
        network.locked = true;
        ticker.behaviour.freqId = testFrequency;
        Create.LOGISTICS.logisticsNetworks.put(testFrequency, network);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(player, pos);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "a locked network must deny a non-owner");
            network.owner = player.getUUID();
            use(player, pos);
            helper.assertTrue(player.containerMenu.getType() == AllMenuTypes.STOCK_KEEPER_REQUEST.get(),
                    "the network owner must reach the native request menu");
            player.closeContainer();
            ItemStack list = ShoppingListItem.saveList(AllItems.SHOPPING_LIST.asStack(),
                    new ShoppingList(List.of(), player.getUUID(), testFrequency), "doll-test");
            player.setItemInHand(InteractionHand.MAIN_HAND, list);
            use(player, pos);
            helper.assertTrue(player.getMainHandItem().isEmpty(),
                    "Create's native shopping-list branch must process and consume its empty order");
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "shopping-list interaction must not be replaced with a request-menu open");
            helper.succeed();
        } finally {
            ticker.behaviour.freqId = originalFrequency;
            Create.LOGISTICS.logisticsNetworks.remove(testFrequency);
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch", timeoutTicks = 200)
    public static void sableAssemblyKeepsRelativePairAndFullDollData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos world = fixture(helper, DyeColor.CYAN, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItemStack original = doll();
        player.setItemInHand(InteractionHand.MAIN_HAND, original.copy());
        use(player, world);
        ServerSubLevel vehicle = SubLevelAssemblyHelper.assembleBlocks(level, world,
                List.of(world, world.east()), new BoundingBox3i(world, world.east()));
        helper.assertTrue(vehicle != null, "Sable assembly must succeed");
        try {
            BlockPos plot = BlockPos.containing(vehicle.logicalPose().transformPositionInverse(world.getCenter()));
            DollKeeperSeatBlockEntity keeper = keeper(level, plot);
            assertDoll(helper, keeper.doll(), original);
            helper.assertTrue(keeper.isValidKeeperFor(plot.east()),
                    "pairing must be relative to plot coordinates after assembly");
            keeper.checkTicker(level);
            helper.assertTrue(level.getBlockEntity(plot) == keeper, "an assembled ticker must keep its seat alive");
            List<ItemStack> drops = Block.getDrops(keeper.getBlockState(), level, plot, keeper);
            helper.assertTrue(drops.size() == 2, "Sable's standard Block.getDrops path must include seat and doll");
            assertDoll(helper, drops.get(1), original);
            helper.succeed();
        } finally {
            if (vehicle != null) {
                vehicle.markRemoved();
            }
        }
    }

    private static BlockPos fixture(GameTestHelper helper, DyeColor color, boolean waterlogged) {
        BlockPos pos = helper.absolutePos(SEAT);
        ServerLevel level = helper.getLevel();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            level.setBlockAndUpdate(pos.relative(direction), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.relative(direction).above(), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(pos, AllBlocks.SEATS.get(color).getDefaultState().setValue(SeatBlock.WATERLOGGED, waterlogged));
        level.setBlockAndUpdate(pos.east(), AllBlocks.STOCK_TICKER.getDefaultState());
        return pos;
    }

    private static ItemStack doll() {
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (VanillaDollItems.contains(stack)) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("keeper component round trip"));
                CompoundTag data = new CompoundTag();
                data.putString("testMarker", UUID.randomUUID().toString());
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                return stack;
            }
        }
        throw new IllegalStateException("DollStockKeeperGameTests require kaleidoscope_doll:vanilla_dolls from the real mod");
    }

    private static DollKeeperSeatBlockEntity keeper(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DollKeeperSeatBlockEntity keeper) {
            return keeper;
        }
        throw new IllegalStateException("No installed doll keeper at " + pos + ": " + level.getBlockState(pos));
    }

    private static void assertDoll(GameTestHelper helper, ItemStack actual, ItemStack original) {
        helper.assertTrue(actual.getCount() == 1 && ItemStack.isSameItemSameComponents(actual, original),
                "exactly one doll with all original components must survive");
    }

    private static void assertRejected(GameTestHelper helper, BlockPos pos, ServerPlayer player, String reason) {
        helper.assertTrue(!DollStockKeeperInteraction.install(helper.getLevel(), pos, player, InteractionHand.MAIN_HAND),
                "installation must reject " + reason);
        helper.assertTrue(player.getMainHandItem().getCount() == 1, "rejection must not consume the doll");
        helper.assertTrue(helper.getLevel().getBlockState(pos).is(AllBlocks.SEATS.get(DyeColor.WHITE).get()),
                "rejection must preserve the ordinary seat");
    }

    private static void assertDrops(GameTestHelper helper, BlockPos pos, ItemStack original, DyeColor color, int seats) {
        List<ItemStack> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1))
                .stream().map(ItemEntity::getItem).toList();
        int dolls = 0;
        int cushions = 0;
        for (ItemStack stack : drops) {
            if (ItemStack.isSameItemSameComponents(stack, original)) {
                dolls += stack.getCount();
            } else if (stack.is(AllBlocks.SEATS.get(color).asItem())) {
                cushions += stack.getCount();
            } else {
                throw new IllegalStateException("Unexpected keeper drop: " + stack);
            }
        }
        helper.assertTrue(dolls == 1 && cushions == seats,
                "expected one full doll and " + seats + " original seats, got " + drops);
    }

    private static void use(ServerPlayer player, BlockPos pos) {
        player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "doll-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {}
        };
        player.gameMode.changeGameModeForPlayer(mode);
        player.setPos(helper.absolutePos(SEAT.above(2)).getCenter());
        return player;
    }

    private DollStockKeeperGameTests() {}
}
