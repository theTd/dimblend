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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
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
            BlockPos spawnChest = lamp.above();
            helper.assertTrue(level.getBlockState(spawnChest).isAir(), "spawn chest cell is left empty");
            helper.assertTrue(level.getBlockEntity(spawnChest) == null, "no chest block entity survives");
            helper.assertTrue(level.getBlockState(lamp.above(2)).isAir(), "opening above the empty cell stays air");
            helper.assertTrue(level.getBlockState(new BlockPos(-17, 64, 0)).isAir(), "near track removed");
            helper.assertTrue(level.getBlockState(new BlockPos(-256, 64, 0)).isAir(), "far track removed");
            helper.assertTrue(CorridorRails.isGeneratedTrack(level, new BlockPos(-257, 64, 0)), "track beyond gap remains");
            for (int z = -7; z <= 7; z++) for (int y = 64; y < 71; y++) {
                if (z == 0 && y <= 66) continue;
                helper.assertTrue(level.getBlockState(new BlockPos(-16, y, z)).is(Blocks.BEDROCK), "15x7 wall");
            }
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(level.getBlockState(spawnChest).isAir()
                        && level.getBlockEntity(spawnChest) == null, "depot must not put a chest back");
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
                ChassisBlueprint blueprint = car.logicalPose().position().x() < 224
                        ? ChassisBlueprint.POWER : ChassisBlueprint.CARRIAGE;
                BlockPos plotCenter = car.getPlot().getCenterBlock();
                for (var cell : blueprint.cells()) {
                    if (cell.symbol() != 'P' && cell.symbol() != 'N') continue;
                    BlockPos panelPos = plotCenter.offset(cell.x(), 0, cell.z());
                    var panel = level.getBlockState(panelPos);
                    if (cell.symbol() == 'N') {
                        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(panel.getBlock()).equals(
                                ResourceLocation.parse("dimblend_blocks:creative_clean_copycat_slab")),
                                "every clean carriage cell must be a creative clean copycat slab");
                        helper.assertTrue(panel.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y
                                && panel.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP,
                                "clean carriage slabs must be horizontal top slabs");
                    }
                    double expectedBottom = cell.symbol() == 'N' ? 0.5D : 13.0D / 16;
                    var shape = panel.getShape(level, panelPos);
                    var collision = panel.getCollisionShape(level, panelPos);
                    helper.assertTrue(Math.abs(shape.min(Direction.Axis.Y) - expectedBottom) < 1e-6
                            && Math.abs(shape.max(Direction.Axis.Y) - 1) < 1e-6,
                            "panels occupy the top three pixels; clean slabs occupy the upper half");
                    helper.assertTrue(Math.abs(collision.min(Direction.Axis.Y) - expectedBottom) < 1e-6
                            && Math.abs(collision.max(Direction.Axis.Y) - 1) < 1e-6,
                            "panel collision must align with the top of the chassis deck");
                }
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

    @GameTest(
            template = "empty",
            templateNamespace = "dimblend_chassis",
            batch = "depot_unloaded_no_sync_load",
            timeoutTicks = 100)
    public static void unloadedDepotSitesNeverForceSyncChunkLoads(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        final int fittedBoundary = 20480;
        final int halfBuiltBoundary = 24576;
        BlockPos fittedCenter = dimblend.experience.chassis.StationDepotAssemblyData.sidingCenter(fittedBoundary);
        BlockPos fittedMachine = dimblend.experience.chassis.StationDepotSwitch.machine(fittedBoundary);
        BlockPos staleMachine = new BlockPos(fittedBoundary + 40,
                dimblend.api.StationDepotSites.CHASSIS_Y - 1, 7);
        BlockPos halfBuiltCenter = dimblend.experience.chassis.StationDepotAssemblyData.sidingCenter(halfBuiltBoundary);

        var chunkSource = level.getChunkSource();
        helper.assertTrue(chunkSource.getChunkNow(fittedMachine.getX() >> 4, fittedMachine.getZ() >> 4) == null,
                "precondition: fitted depot switch chunk must not be loaded");
        helper.assertTrue(chunkSource.getChunkNow(staleMachine.getX() >> 4, staleMachine.getZ() >> 4) == null,
                "precondition: stale switch chunk must not be loaded");
        helper.assertTrue(chunkSource.getChunkNow(halfBuiltCenter.getX() >> 4, halfBuiltCenter.getZ() >> 4) == null,
                "precondition: half-built depot center chunk must not be loaded");

        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag sitesTag = new net.minecraft.nbt.ListTag();

        net.minecraft.nbt.CompoundTag fittedEntry = new net.minecraft.nbt.CompoundTag();
        fittedEntry.putLong("center", fittedCenter.asLong());
        fittedEntry.putString("kind", dimblend.experience.chassis.ChassisBlueprint.CARRIAGE.name());
        fittedEntry.putBoolean("built", true);
        fittedEntry.putBoolean("assembled", true);
        fittedEntry.putInt("attempts", 0);
        sitesTag.add(fittedEntry);

        net.minecraft.nbt.CompoundTag halfBuiltEntry = new net.minecraft.nbt.CompoundTag();
        halfBuiltEntry.putLong("center", halfBuiltCenter.asLong());
        halfBuiltEntry.putString("kind", dimblend.experience.chassis.ChassisBlueprint.CARRIAGE.name());
        halfBuiltEntry.putBoolean("built", true);
        halfBuiltEntry.putBoolean("assembled", false);
        halfBuiltEntry.putInt("attempts", 0);
        sitesTag.add(halfBuiltEntry);

        tag.put("sites", sitesTag);
        var curves = dimblend.api.StationDepotCurves.all(fittedBoundary);
        long[] curveKeys = new long[curves.size()];
        for (int i = 0; i < curves.size(); i++) {
            var curve = curves.get(i);
            curveKeys[i] = new BlockPos(curve.fromX(), dimblend.api.StationDepotSites.CHASSIS_Y - 1, curve.fromZ())
                    .asLong();
        }
        tag.putLongArray("curves", curveKeys);
        tag.putLongArray("switchMachines", new long[] {fittedMachine.asLong()});
        tag.putLongArray("switchMachinesReported", new long[] {staleMachine.asLong()});

        level.getDataStorage().set("dimblend_station_depot_assembly",
                dimblend.experience.chassis.StationDepotAssemblyData.load(tag));
        try {
            for (int tick = 0; tick < 3; tick++) {
                dimblend.experience.chassis.StationDepotAssembly.tick(level);
            }
            helper.assertTrue(chunkSource.getChunkNow(fittedMachine.getX() >> 4, fittedMachine.getZ() >> 4) == null,
                    "ticking unloaded fitted depot must never force sync chunk load at switch machine");
            helper.assertTrue(chunkSource.getChunkNow(staleMachine.getX() >> 4, staleMachine.getZ() >> 4) == null,
                    "ticking unloaded fitted depot must never force sync chunk load at stale switch");
            helper.assertTrue(chunkSource.getChunkNow(halfBuiltCenter.getX() >> 4, halfBuiltCenter.getZ() >> 4) == null,
                    "ticking unloaded half-built depot must never force sync chunk load at center");
            helper.assertTrue(dimblend.experience.chassis.StationDepotAssemblyData.get(level).switchFitted(fittedMachine),
                    "fitted switch record must remain intact when chunks are unloaded");
        } finally {
            level.getDataStorage().set("dimblend_station_depot_assembly",
                    dimblend.experience.chassis.StationDepotAssemblyData.load(new net.minecraft.nbt.CompoundTag()));
        }
        helper.succeed();
    }
}
