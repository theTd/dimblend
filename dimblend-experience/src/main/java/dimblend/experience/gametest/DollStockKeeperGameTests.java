package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllMenuTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem;
import com.simibubi.create.content.logistics.tableCloth.ShoppingListItem.ShoppingList;
import com.simibubi.create.foundation.gui.menu.MenuBase;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.create.stockkeeper.DollSeatPlacement;
import dimblend.experience.compat.create.stockkeeper.DollStockKeeperCompat;
import dimblend.experience.compat.dolls.DollKeeperSeatBlock;
import dimblend.experience.compat.dolls.DollKeeperSeatBlockEntity;
import dimblend.experience.compat.dolls.DollStockKeeperInteraction;
import dimblend.experience.compat.dolls.VanillaDollItems;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Both keeper models are covered here after the merge:
 * the upstream proxy keeper block entity (vanilla doll items) and the real DollEntity seated on an
 * ordinary Create seat (doll_entity_item). Needs the real Kaleidoscope Doll tag/models (1.4.1),
 * Create and Sable; no synthetic tag mutation. Per-test WARN skip keeps the suite runnable when
 * the doll mod is absent.
 */
@PrefixGameTestTemplate(false)
public final class DollStockKeeperGameTests {
    // Proxy keeper fixture (upstream path): an ordinary Create seat replaced by a keeper seat variant.
    private static final BlockPos SEAT = new BlockPos(1, 2, 1);
    // Real DollEntity fixture (doll_entity_item path), on the copycat_obsidian template floor.
    private static final BlockPos REAL_SEAT = new BlockPos(1, 1, 1);
    private static final BlockPos REAL_TICKER = new BlockPos(2, 1, 1);

    // ---------------------------------------------------------------- proxy keeper (vanilla dolls)

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void survivalInstallationPersistsFullItemColorWaterAndFacing(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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
    public static void offhandDollNeverInstallsButStillConsumesTheSeatClick(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.GRAY, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.OFF_HAND, doll());
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.OFF_HAND, pos,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
        NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled(), "an offhand doll click on a keeper seat must consume the interaction");
        helper.assertTrue(player.getOffhandItem().getCount() == 1, "offhand placement must not consume the doll");
        helper.assertTrue(level.getBlockState(pos).is(AllBlocks.SEATS.get(DyeColor.GRAY).get()),
                "offhand placement must leave the ordinary seat untouched");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void proxyKeeperSeatCountsAsPresentTickerForNativeBlockInteraction(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.ORANGE, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, doll());
        use(player, pos);
        StockTickerBlockEntity ticker = (StockTickerBlockEntity) level.getBlockEntity(pos.east());
        for (var seat : level.getEntitiesOfClass(SeatEntity.class, new AABB(pos))) {
            seat.discard();
        }
        helper.assertTrue(ticker.isKeeperPresent(),
                "Create's own keeper test must accept the proxy keeper block entity");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(pos.east().getCenter(), Direction.UP, pos.east(), false));
        helper.assertTrue(player.containerMenu.getType() == AllMenuTypes.STOCK_KEEPER_CATEGORY.get(),
                "native ticker block interaction must open Create's category menu for a proxy keeper");
        player.closeContainer();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch", timeoutTicks = 100)
    public static void proxyKeeperSqueaksOnceOnlyForAMainHandMenuOpen(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        ServerLevel level = helper.getLevel();
        BlockPos pos = fixture(helper, DyeColor.MAGENTA, false);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, doll());
        use(player, pos);
        StockTickerBlockEntity ticker = (StockTickerBlockEntity) level.getBlockEntity(pos.east());
        UUID tickerFrequency = ticker.behaviour.freqId;
        LogisticsNetwork previousNetwork = Create.LOGISTICS.logisticsNetworks.get(tickerFrequency);
        int[] squeaks = {0};
        Consumer<PlayLevelSoundEvent.AtPosition> capture = event -> {
            if (event.getLevel() == level && event.getSound() != null
                    && DollStockKeeperCompat.SQUEAK.equals(event.getSound().value().getLocation())) squeaks[0]++;
        };
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, capture);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            PlayerInteractEvent.RightClickBlock main = clickPos(player, pos, InteractionHand.MAIN_HAND);
            helper.assertTrue(main.isCanceled(), "a keeper seat click must be consumed by the doll handler");
            helper.assertTrue(player.containerMenu.getType() == AllMenuTypes.STOCK_KEEPER_REQUEST.get(),
                    "a proxy keeper must reach Create's native request menu");
            helper.assertTrue(squeaks[0] == 1, "a successful main-hand open must squeak exactly once");
            player.closeContainer();

            PlayerInteractEvent.RightClickBlock offhand = clickPos(player, pos, InteractionHand.OFF_HAND);
            helper.assertTrue(offhand.isCanceled(), "an off-hand keeper click stays consumed");
            helper.assertTrue(squeaks[0] == 1, "the off-hand open must not replay the squeak");
            player.closeContainer();

            LogisticsNetwork locked = new LogisticsNetwork(tickerFrequency);
            locked.owner = UUID.randomUUID();
            locked.locked = true;
            Create.LOGISTICS.logisticsNetworks.put(tickerFrequency, locked);
            AbstractContainerMenu before = player.containerMenu;
            clickPos(player, pos, InteractionHand.MAIN_HAND);
            helper.assertTrue(player.containerMenu == before, "a locked network must deny a non-owner");
            helper.assertTrue(squeaks[0] == 1, "permission denial must stay silent");
        } finally {
            player.closeContainer();
            player.discard();
            if (previousNetwork == null) Create.LOGISTICS.logisticsNetworks.remove(tickerFrequency);
            else Create.LOGISTICS.logisticsNetworks.put(tickerFrequency, previousNetwork);
            NeoForge.EVENT_BUS.unregister(capture);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_compat_batch")
    public static void normalBreakDropsOriginalSeatAndOneCompleteDoll(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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
        if (skipWithoutDoll(helper)) return;
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

    // ---------------------------------------------------------------- real DollEntity (doll_entity_item)

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void dollRequiresSeatAndExactlyOneTicker(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        try (RealDollFixture fixture = realDollFixture(helper)) {
            Entity doll = fixture.doll();
            BlockPos tickerPos = fixture.ticker().getBlockPos();
            helper.assertTrue(tickerPos.equals(StockTickerInteractionHandler.getStockTickerPosition(doll)),
                    "seated doll must pass the real Create interaction predicate");
            helper.assertTrue(fixture.ticker().isKeeperPresent(), "Create already accepts an occupied seat");
            helper.assertTrue(DollStockKeeperCompat.findKeeper(fixture.ticker()) == doll,
                    "screen lookup must resolve the same doll and ticker");
            BlockPos second = REAL_SEAT.south();
            helper.setBlock(second, AllBlocks.STOCK_TICKER.getDefaultState());
            helper.assertTrue(StockTickerInteractionHandler.getStockTickerPosition(doll) == null,
                    "two adjacent tickers must remain ambiguous");
            helper.assertTrue(DollStockKeeperCompat.findKeeper(fixture.ticker()) == null,
                    "screen must not retain a doll with an ambiguous ticker");
            helper.setBlock(second, Blocks.AIR);
            doll.stopRiding();
            helper.assertTrue(StockTickerInteractionHandler.getStockTickerPosition(doll) == null,
                    "an unseated doll is not a stock keeper");
            helper.assertTrue(!DollStockKeeperCompat.isValidKeeper(doll, fixture.seat(), tickerPos),
                    "dismount must release the screen-close suppression");

            Entity pig = EntityType.PIG.create(helper.getLevel());
            Entity box = AllEntityTypes.PACKAGE.get().create(helper.getLevel());
            helper.assertTrue(pig != null && box != null, "vanilla and package fixtures must exist");
            try {
                pig.setPos(doll.position());
                pig.startRiding(fixture.seat(), true);
                helper.assertTrue(tickerPos.equals(StockTickerInteractionHandler.getStockTickerPosition(pig)),
                        "existing living stock keepers must keep working");
                pig.stopRiding();
                box.setPos(doll.position());
                box.startRiding(fixture.seat(), true);
                helper.assertTrue(StockTickerInteractionHandler.getStockTickerPosition(box) == null,
                        "Create's explicit package exclusion must remain intact");
            } finally {
                pig.discard();
                box.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void removedOrReseatedDollDoesNotKeepMenuAlive(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        try (RealDollFixture fixture = realDollFixture(helper)) {
            BlockPos tickerPos = fixture.ticker().getBlockPos();
            helper.assertTrue(DollStockKeeperCompat.isValidKeeper(fixture.doll(), fixture.seat(), tickerPos),
                    "live doll on the original seat must retain its menu");
            SeatEntity replacement = new SeatEntity(helper.getLevel());
            replacement.setPos(fixture.seat().position());
            helper.getLevel().addFreshEntity(replacement);
            try {
                fixture.doll().stopRiding();
                fixture.doll().startRiding(replacement, true);
                helper.assertTrue(!DollStockKeeperCompat.isValidKeeper(fixture.doll(), fixture.seat(), tickerPos),
                        "moving the same doll to another seat must close the old menu");
                fixture.doll().discard();
                helper.assertTrue(!DollStockKeeperCompat.isValidKeeper(fixture.doll(), replacement, tickerPos),
                        "a removed doll must not retain a screen through its weak reference");
            } finally {
                replacement.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void successfulMainHandMenuSqueaksOnceAndDeniedMenuStaysSilent(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        try (RealDollFixture fixture = realDollFixture(helper)) {
            ServerPlayer player = realDollPlayer(helper);
            int[] squeaks = {0};
            Consumer<PlayLevelSoundEvent.AtPosition> capture = event -> {
                if (event.getLevel() == helper.getLevel() && event.getSound() != null
                        && DollStockKeeperCompat.SQUEAK.equals(event.getSound().value().getLocation())) squeaks[0]++;
            };
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, capture);
            UUID networkId = fixture.ticker().behaviour.freqId;
            LogisticsNetwork previousNetwork = Create.LOGISTICS.logisticsNetworks.get(networkId);
            try {
                AbstractContainerMenu before = player.containerMenu;
                interactWithTicker(player, fixture.doll(), InteractionHand.MAIN_HAND);
                helper.assertTrue(player.containerMenu != before && player.containerMenu instanceof MenuBase<?> menu
                                && menu.contentHolder == fixture.ticker(),
                        "the original server openMenu must install a real menu for this ticker");
                helper.assertTrue(squeaks[0] == 1, "successful main-hand open must broadcast exactly one squeak");
                player.closeContainer();
                interactWithTicker(player, fixture.doll(), InteractionHand.OFF_HAND);
                helper.assertTrue(squeaks[0] == 1, "offhand fallback must not replay the sound");
                player.closeContainer();

                player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.SHOPPING_LIST.asStack());
                before = player.containerMenu;
                interactWithTicker(player, fixture.doll(), InteractionHand.MAIN_HAND);
                helper.assertTrue(player.containerMenu == before && squeaks[0] == 1,
                        "shopping-list handling is not a successful menu open");
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

                LogisticsNetwork locked = new LogisticsNetwork(networkId);
                locked.owner = UUID.randomUUID();
                locked.locked = true;
                Create.LOGISTICS.logisticsNetworks.put(networkId, locked);
                before = player.containerMenu;
                PlayerInteractEvent.EntityInteractSpecific denied = interactWithTicker(player, fixture.doll(),
                        InteractionHand.MAIN_HAND);
                helper.assertTrue(denied.isCanceled(), "Create consumes even a denied interaction");
                helper.assertTrue(player.containerMenu == before && squeaks[0] == 1,
                        "permission denial must neither open a menu nor squeak");
            } finally {
                player.closeContainer();
                player.discard();
                if (previousNetwork == null) Create.LOGISTICS.logisticsNetworks.remove(networkId);
                else Create.LOGISTICS.logisticsNetworks.put(networkId, previousNetwork);
                NeoForge.EVENT_BUS.unregister(capture);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void heldDollUsesOriginalItemOnceAndNeverEjectsAnOccupant(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        helper.setBlock(REAL_SEAT.below(), Blocks.STONE);
        helper.setBlock(REAL_TICKER.below(), Blocks.STONE);
        helper.setBlock(REAL_SEAT, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "white_seat")));
        helper.setBlock(REAL_TICKER, AllBlocks.STOCK_TICKER.getDefaultState());
        ServerPlayer player = realDollPlayer(helper);
        Entity source = BuiltInRegistries.ENTITY_TYPE.get(DollStockKeeperCompat.DOLL).create(helper.getLevel());
        helper.assertTrue(source != null, "source doll must exist");
        CompoundTag entityData = new CompoundTag();
        entityData.put("doll_block_state", NbtUtils.writeBlockState(Blocks.PINK_WOOL.defaultBlockState()));
        source.load(entityData);
        ItemStack held = source.getPickResult();
        source.discard();
        helper.assertTrue(held != null && !held.isEmpty(), "upstream pick result must contain the original doll data");
        held.setCount(2);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockPos seatPos = helper.absolutePos(REAL_SEAT);
        // A side hit still places the doll above/on the seat, preserving upstream permission checks.
        BlockHitResult hit = new BlockHitResult(seatPos.getCenter(), Direction.NORTH, seatPos, false);
        AABB area = new AABB(seatPos).inflate(2);
        try {
            var result = player.gameMode.useItemOn(player, helper.getLevel(), held, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(result.consumesAction(), "seat must delegate the actual item placement");
            var dolls = helper.getLevel().getEntities((Entity) null, area, DollStockKeeperCompat::isDoll);
            helper.assertTrue(dolls.size() == 1, "one placement must create exactly one DollEntity");
            Entity doll = dolls.getFirst();
            helper.assertTrue(doll.getVehicle() instanceof SeatEntity && !player.isPassenger(),
                    "the doll, not the player, must occupy the Create seat");
            helper.assertTrue(held.getCount() == 1, "the original item must consume exactly one doll");
            helper.assertTrue(helper.absolutePos(REAL_TICKER).equals(StockTickerInteractionHandler.getStockTickerPosition(doll)),
                    "placed doll must immediately resolve its unique stock ticker");
            CompoundTag saved = new CompoundTag();
            doll.saveWithoutId(saved);
            helper.assertTrue("minecraft:pink_wool".equals(saved.getCompound("doll_block_state").getString("Name")),
                    "upstream doll data must survive the seating bridge");
            Entity originalSeat = doll.getVehicle();
            player.gameMode.useItemOn(player, helper.getLevel(), held, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(held.getCount() == 1 && doll.getVehicle() == originalSeat && !player.isPassenger(),
                    "clicking an occupied seat must not consume, eject or seat the player");
            ItemStack offhand = new ItemStack(BuiltInRegistries.ITEM.get(DollSeatPlacement.DOLL_ITEM), 2);
            player.setItemInHand(InteractionHand.OFF_HAND, offhand);
            player.gameMode.useItemOn(player, helper.getLevel(), offhand, InteractionHand.OFF_HAND, hit);
            helper.assertTrue(offhand.getCount() == 2
                            && helper.getLevel().getEntities((Entity) null, area, DollStockKeeperCompat::isDoll).size() == 1,
                    "offhand fallback must not create or consume another doll");
        } finally {
            for (Entity entity : helper.getLevel().getEntities((Entity) null, area,
                    e -> DollStockKeeperCompat.isDoll(e) || e instanceof SeatEntity)) entity.discard();
            player.discard();
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- shared helpers

    private static boolean skipWithoutDoll(GameTestHelper helper) {
        if (ModList.get().isLoaded("kaleidoscope_doll")) return false;
        LogUtils.getLogger().warn("[doll-stockkeeper-test] SKIPPED: kaleidoscope_doll is absent; no runtime coverage");
        helper.succeed();
        return true;
    }

    private static PlayerInteractEvent.EntityInteractSpecific interactWithTicker(ServerPlayer player, Entity target,
                                                                                InteractionHand hand) {
        var event = new PlayerInteractEvent.EntityInteractSpecific(player, hand, target, Vec3.ZERO);
        StockTickerInteractionHandler.interactWithLogisticsManager(event);
        return event;
    }

    private static RealDollFixture realDollFixture(GameTestHelper helper) {
        helper.setBlock(REAL_SEAT.below(), Blocks.STONE);
        helper.setBlock(REAL_TICKER.below(), Blocks.STONE);
        helper.setBlock(REAL_SEAT, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "white_seat")));
        helper.setBlock(REAL_TICKER, AllBlocks.STOCK_TICKER.getDefaultState());
        SeatEntity seat = new SeatEntity(helper.getLevel());
        seat.setPos(helper.absolutePos(REAL_SEAT).getCenter());
        helper.getLevel().addFreshEntity(seat);
        Entity doll = BuiltInRegistries.ENTITY_TYPE.get(DollStockKeeperCompat.DOLL).create(helper.getLevel());
        helper.assertTrue(doll != null && DollStockKeeperCompat.isDoll(doll), "registered DollEntity must exist");
        doll.setPos(seat.position());
        helper.getLevel().addFreshEntity(doll);
        helper.assertTrue(doll.startRiding(seat, true), "doll must mount the Create seat");
        return new RealDollFixture(doll, seat, (StockTickerBlockEntity) helper.getBlockEntity(REAL_TICKER));
    }

    private static ServerPlayer realDollPlayer(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "doll-keeper-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) { }
        };
        player.setPos(helper.absolutePos(REAL_SEAT).getCenter().add(0, 0, 2));
        return player;
    }

    private record RealDollFixture(Entity doll, SeatEntity seat, StockTickerBlockEntity ticker) implements AutoCloseable {
        @Override public void close() {
            doll.discard();
            seat.discard();
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

    private static PlayerInteractEvent.RightClickBlock clickPos(ServerPlayer player, BlockPos pos,
                                                               InteractionHand hand) {
        BlockHitResult hit = new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
        PlayerInteractEvent.RightClickBlock event =
                new PlayerInteractEvent.RightClickBlock(player, hand, pos, hit);
        NeoForge.EVENT_BUS.post(event);
        return event;
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
