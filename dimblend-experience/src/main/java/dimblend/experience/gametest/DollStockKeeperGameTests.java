package dimblend.experience.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.foundation.gui.menu.MenuBase;
import dimblend.experience.compat.create.stockkeeper.DollStockKeeperCompat;
import dimblend.experience.compat.create.stockkeeper.DollSeatPlacement;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Register only with Create. The installed Doll mod is needed to exercise, not skip, these tests. */
@PrefixGameTestTemplate(false)
public final class DollStockKeeperGameTests {
    private static final BlockPos SEAT = new BlockPos(1, 1, 1);
    private static final BlockPos TICKER = new BlockPos(2, 1, 1);

    private DollStockKeeperGameTests() { }

    @GameTest(template = "copycat_obsidian", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void dollRequiresSeatAndExactlyOneTicker(GameTestHelper helper) {
        if (skipWithoutDoll(helper)) return;
        try (Fixture fixture = fixture(helper)) {
            Entity doll = fixture.doll();
            BlockPos tickerPos = fixture.ticker().getBlockPos();
            helper.assertTrue(tickerPos.equals(StockTickerInteractionHandler.getStockTickerPosition(doll)),
                    "seated doll must pass the real Create interaction predicate");
            helper.assertTrue(fixture.ticker().isKeeperPresent(), "Create already accepts an occupied seat");
            helper.assertTrue(DollStockKeeperCompat.findKeeper(fixture.ticker()) == doll,
                    "screen lookup must resolve the same doll and ticker");
            BlockPos second = SEAT.south();
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
        try (Fixture fixture = fixture(helper)) {
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
        try (Fixture fixture = fixture(helper)) {
            ServerPlayer player = player(helper);
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
                interact(player, fixture.doll(), InteractionHand.MAIN_HAND);
                helper.assertTrue(player.containerMenu != before && player.containerMenu instanceof MenuBase<?> menu
                                && menu.contentHolder == fixture.ticker(),
                        "the original server openMenu must install a real menu for this ticker");
                helper.assertTrue(squeaks[0] == 1, "successful main-hand open must broadcast exactly one squeak");
                player.closeContainer();
                interact(player, fixture.doll(), InteractionHand.OFF_HAND);
                helper.assertTrue(squeaks[0] == 1, "offhand fallback must not replay the sound");
                player.closeContainer();

                player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.SHOPPING_LIST.asStack());
                before = player.containerMenu;
                interact(player, fixture.doll(), InteractionHand.MAIN_HAND);
                helper.assertTrue(player.containerMenu == before && squeaks[0] == 1,
                        "shopping-list handling is not a successful menu open");
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

                LogisticsNetwork locked = new LogisticsNetwork(networkId);
                locked.owner = UUID.randomUUID();
                locked.locked = true;
                Create.LOGISTICS.logisticsNetworks.put(networkId, locked);
                before = player.containerMenu;
                PlayerInteractEvent.EntityInteractSpecific denied = interact(player, fixture.doll(), InteractionHand.MAIN_HAND);
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
        helper.setBlock(SEAT.below(), Blocks.STONE);
        helper.setBlock(TICKER.below(), Blocks.STONE);
        helper.setBlock(SEAT, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "white_seat")));
        helper.setBlock(TICKER, AllBlocks.STOCK_TICKER.getDefaultState());
        ServerPlayer player = player(helper);
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
        BlockPos seatPos = helper.absolutePos(SEAT);
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
            helper.assertTrue(helper.absolutePos(TICKER).equals(StockTickerInteractionHandler.getStockTickerPosition(doll)),
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
    private static PlayerInteractEvent.EntityInteractSpecific interact(ServerPlayer player, Entity target,
                                                                       InteractionHand hand) {
        var event = new PlayerInteractEvent.EntityInteractSpecific(player, hand, target, Vec3.ZERO);
        StockTickerInteractionHandler.interactWithLogisticsManager(event);
        return event;
    }

    private static boolean skipWithoutDoll(GameTestHelper helper) {
        if (ModList.get().isLoaded("kaleidoscope_doll")) return false;
        LogUtils.getLogger().warn("[doll-stockkeeper-test] SKIPPED: kaleidoscope_doll is absent; no runtime coverage");
        helper.succeed();
        return true;
    }

    private static Fixture fixture(GameTestHelper helper) {
        helper.setBlock(SEAT.below(), Blocks.STONE);
        helper.setBlock(TICKER.below(), Blocks.STONE);
        helper.setBlock(SEAT, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "white_seat")));
        helper.setBlock(TICKER, AllBlocks.STOCK_TICKER.getDefaultState());
        SeatEntity seat = new SeatEntity(helper.getLevel());
        seat.setPos(helper.absolutePos(SEAT).getCenter());
        helper.getLevel().addFreshEntity(seat);
        Entity doll = BuiltInRegistries.ENTITY_TYPE.get(DollStockKeeperCompat.DOLL).create(helper.getLevel());
        helper.assertTrue(doll != null && DollStockKeeperCompat.isDoll(doll), "registered DollEntity must exist");
        doll.setPos(seat.position());
        helper.getLevel().addFreshEntity(doll);
        helper.assertTrue(doll.startRiding(seat, true), "doll must mount the Create seat");
        return new Fixture(doll, seat, (StockTickerBlockEntity) helper.getBlockEntity(TICKER));
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "doll-keeper-test"), ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) { }
        };
        player.setPos(helper.absolutePos(SEAT).getCenter().add(0, 0, 2));
        return player;
    }

    private record Fixture(Entity doll, SeatEntity seat, StockTickerBlockEntity ticker) implements AutoCloseable {
        @Override public void close() {
            doll.discard();
            seat.discard();
        }
    }
}