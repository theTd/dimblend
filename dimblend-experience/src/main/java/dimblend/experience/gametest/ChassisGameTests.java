package dimblend.experience.gametest;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.AllBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.api.CorridorRails;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisBlueprint;
import dimblend.experience.chassis.ChassisConstruction;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.ChassisSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Map;

@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        if (ChassisEvents.available()) event.register(ChassisGameTests.class);
    }

    private static ServerLevel rotating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("dimblend:rotating")));
        helper.assertTrue(level != null, "rotating dimension must be loaded");
        return level;
    }

    private static void loadSite(ServerLevel level, BlockPos center) {
        for (int x = (center.getX() - 25) >> 4; x <= (center.getX() + 25) >> 4; x++) {
            level.getChunk(x, -1);
            level.getChunk(x, 0);
        }
        if (CorridorRails.isGeneratedTrack(level, center.below())) {
            com.simibubi.create.content.trains.track.TrackPropagator.onRailAdded(level, center.below(),
                    level.getBlockState(center.below()));
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void newWorldDepotAndTrackGap(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        loadSite(level, new BlockPos(-32, 65, 0));
        level.getChunk(-16, 0);
        level.getChunk(-17, 0);
        helper.runAfterDelay(5, () -> {
            BlockPos lamp = new BlockPos(-16, 64, 0);
            helper.assertTrue(level.getBlockState(lamp).is(AllBlocks.ROSE_QUARTZ_LAMP.get()), "depot lamp");
            helper.assertTrue(level.getBlockState(lamp).getLightEmission(level, lamp) == 15, "lamp is lit");
            helper.assertTrue(level.getBlockState(lamp.above(2)).isAir(), "chest has opening space");
            var chest = (ChestBlockEntity) level.getBlockEntity(lamp.above());
            helper.assertTrue(chest != null && chest.getItem(0).is(AllBlocks.TRAIN_CONTROLS.asItem())
                    && chest.getItem(0).getCount() == 6, "exactly six controllers");
            helper.assertTrue(level.getBlockState(lamp.above()).getValue(ChestBlock.FACING) == Direction.EAST, "chest faces X+");
            helper.assertTrue(level.getBlockState(new BlockPos(-17, 64, 0)).isAir(), "near track removed");
            helper.assertTrue(level.getBlockState(new BlockPos(-256, 64, 0)).isAir(), "far track removed");
            helper.assertTrue(CorridorRails.isGeneratedTrack(level, new BlockPos(-257, 64, 0)), "track beyond gap remains");
            for (int z = -7; z <= 7; z++) for (int y = 64; y < 71; y++) {
                if (z == 0 && y <= 66) continue;
                helper.assertTrue(level.getBlockState(new BlockPos(-16, y, z)).is(Blocks.BEDROCK), "15x7 wall");
            }
            chest.clearContent();
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(chest.isEmpty(), "depot must not refill");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void obstaclesAndMovingStructuresAreRejected(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos center = new BlockPos(96, 65, 0);
        loadSite(level, center);
        helper.assertTrue(ChassisSite.clear(level, center, Map.of(), false), "natural track is excluded from air check");
        BlockPos obstacle = center.offset(24, 1, 3);
        level.setBlockAndUpdate(obstacle, Blocks.STONE.defaultBlockState());
        helper.assertTrue(!ChassisSite.clear(level, center, Map.of(), false), "upper far corner obstructs site");
        level.removeBlock(obstacle, false);
        BlockPos body = center.offset(4, 0, 0);
        level.setBlockAndUpdate(body, Blocks.STONE.defaultBlockState());
        var car = SubLevelAssemblyHelper.assembleBlocks(level, body, java.util.List.of(body), new BoundingBox3i(body, body));
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(!ChassisSite.clear(level, center, Map.of(), false), "Sable stone occupies otherwise empty world space");
            car.markRemoved();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 450)
    public static void bothBlueprintsAssembleAndStayForceLoaded(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos power = new BlockPos(192, 65, 0);
        BlockPos carriage = new BlockPos(256, 65, 0);
        for (var center : java.util.List.of(power, carriage)) {
            loadSite(level, center);
            level.setBlockAndUpdate(center, AllBlocks.TRAIN_CONTROLS.getDefaultState());
        }
        helper.assertTrue(ChassisConstruction.start(level, power, ChassisBlueprint.POWER), "power construction starts");
        helper.assertTrue(!ChassisConstruction.start(level, power, ChassisBlueprint.CARRIAGE), "duplicate confirmation cannot consume twice");
        helper.assertTrue(ChassisConstruction.start(level, carriage, ChassisBlueprint.CARRIAGE), "carriage construction starts");
        helper.runAfterDelay(330, () -> {
            var container = SubLevelContainer.getContainer(level);
            helper.assertTrue(container.collectForceLoadedSubLevels().size() == 2, "two force-loaded completed cars");
            int poweredBogeys = 0;
            int carriageBogeys = 0;
            for (var car : container.collectForceLoadedSubLevels()) {
                int count = 0;
                for (var actor : car.getPlot().getBlockEntityActors()) {
                    if (actor instanceof PhysicsBogeyBlockEntity bogey) {
                        count++;
                        helper.assertTrue(bogey.getFacing().getAxis() == Direction.Axis.X, "bogey faces along line");
                        helper.assertTrue(bogey.hasTrack(), "assembled bogey must actually bind to the track");
                    }
                }
                if (car.logicalPose().position().x() < 224) poweredBogeys = count;
                else carriageBogeys = count;
            }
            helper.assertTrue(poweredBogeys == 6, "power chassis has six configured bogeys, got " + poweredBogeys);
            helper.assertTrue(carriageBogeys == 2, "corrected carriage has two bogeys, got " + carriageBogeys);
            // Exercise real Sable serialization while bodies are valid, then let removals drain
            // before the test server saves again on shutdown.
            level.save(null, false, false);
            for (var car : java.util.List.copyOf(container.collectForceLoadedSubLevels())) {
                container.removeForceLoadTicket(car,
                        dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                        net.minecraft.util.Unit.INSTANCE);
                car.markRemoved();
            }
            helper.runAfterDelay(5, helper::succeed);
        });
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 100)
    public static void placementRefundAndSelectionCannotSkipCoolingOff(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos center = new BlockPos(384, 65, 0);
        loadSite(level, center);
        var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "chassis-test"),
                net.minecraft.server.level.ClientInformation.createDefault());
        player.setPos(center.getX() + 0.5, center.getY() + 1, 0.5);
        var reply = new java.util.concurrent.atomic.AtomicReference<dimblend.experience.chassis.ChassisScreenPayload>();
        new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof dimblend.experience.chassis.ChassisScreenPayload response) reply.set(response);
            }
        };
        BlockPos bad = center.offset(0, 0, 6);
        placedByPlayer(level, player, bad);
        ChassisEvents.onTick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, level.getServer()));
        helper.assertTrue(level.getBlockState(bad).isAir(), "off-track control is removed after placement completes");
        helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(bad).inflate(1)).stream()
                .anyMatch(item -> item.getItem().is(AllBlocks.TRAIN_CONTROLS.asItem()) && item.getItem().getCount() == 1),
                "one controller is dropped for an invalid placement");
        placedByPlayer(level, player, center);
        ChassisEvents.onTick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, level.getServer()));
        var click = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player,
                net.minecraft.world.InteractionHand.MAIN_HAND, center, new net.minecraft.world.phys.BlockHitResult(
                        center.getCenter(), Direction.UP, center, false));
        ChassisEvents.onUse(click);
        helper.assertTrue(click.isCanceled() && reply.get() != null, "right-click is routed to chassis selection");
        var token = reply.get().token();
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(token, 0, false));
        helper.assertTrue(reply.get().waitMillis() == 5000, "server starts a five-second deadline");
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(token, 0, true));
        helper.assertTrue(level.getBlockState(center).is(AllBlocks.TRAIN_CONTROLS.get())
                && !ChassisConstruction.reserved(level, center), "early confirmation cannot consume or build");
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(token, -1, false));
        ChassisEvents.onUse(click);
        var replacement = reply.get().token();
        helper.assertTrue(!replacement.equals(token) && reply.get().selected() == -1, "reopening resets the choice and token");
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(replacement, 1, false));
        helper.assertTrue(reply.get().waitMillis() == 5000 && reply.get().selected() == 1, "reselection restarts all five seconds");
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(token, 0, true));
        helper.assertTrue(!ChassisConstruction.reserved(level, center), "stale session cannot confirm");
        dimblend.experience.chassis.ChassisSelection.choose(player,
                new dimblend.experience.chassis.ChassisChoicePayload(replacement, -1, false));
        level.removeBlock(center, false);
        helper.succeed();
    }

    private static void placedByPlayer(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        var snapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, pos);
        level.setBlockAndUpdate(pos, AllBlocks.TRAIN_CONTROLS.getDefaultState());
        ChassisEvents.onPlace(new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(snapshot,
                level.getBlockState(pos.below()), player));
    }
}
