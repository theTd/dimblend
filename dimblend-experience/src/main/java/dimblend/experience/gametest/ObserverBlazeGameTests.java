package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags;
import com.simibubi.create.api.boiler.BoilerHeater;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.create.observer.ObserverBlazeBlock;
import dimblend.experience.compat.create.observer.ObserverBlazeBlockEntity;
import dimblend.experience.compat.create.observer.ObserverBlazeBlocks;
import dimblend.experience.compat.create.observer.ObserverBlazeLayout;
import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The observer blaze end to end: spyglass entry on a burner that sits on a Sable vehicle, sneak + empty main hand
 * exit with the burner's fuel back (also with an item in the offhand), the loot table, the redstone signal driven by
 * a searchlight-marked lava pool -- including one lit by a lamp placed in the world -- the shared scan answering two
 * observers with their own radii, and the absence of any heat path.
 *
 * <p>Run with {@code -PwithHeadlights -PheadlightDecorJar=<the installed DnDecor jar>} (the namespace brings Create's
 * burner and DnDecor's frontlights, Sable is a local runtime).</p>
 */
@PrefixGameTestTemplate(false)
public final class ObserverBlazeGameTests {
    /** The one block the "empty" template offers; blocks around it are placed directly. */
    private static final BlockPos MID = new BlockPos(1, 2, 1);
    /** A lamp placed in the world (never assembled into a vehicle), high enough to shine down the fixtures below. */
    private static final BlockPos WORLD_LAMP = new BlockPos(1, 5, 1);
    private static final int BURN_TIME = 1234;

    // ---------------------------------------------------------------- entry

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 400)
    public static void spyglassSwapsAVehicleBurnerAndKeepsItsState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevel vehicle = null;
        try {
            BlockPos world = helper.absolutePos(MID);
            helper.setBlock(MID, burner(HeatLevel.SMOULDERING));
            loadFuel(level, world, BURN_TIME, true);
            BlockState before = level.getBlockState(world);

            vehicle = assemble(level, world);
            BlockPos plot = plotOf(vehicle, world);
            helper.assertTrue(level.getBlockState(plot).is(AllBlocks.BLAZE_BURNER.get()),
                    "the burner must survive assembly, got " + level.getBlockState(plot));

            ServerPlayer player = player(helper, GameType.SURVIVAL);
            // Quartz is neither a fuel nor goggles: Create's burner leaves it alone, and so must we.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.QUARTZ));
            use(player, plot, InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getBlockState(plot).is(AllBlocks.BLAZE_BURNER.get()),
                    "an unrelated item must not swap the burner");
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "an unrelated item must not be consumed");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS, 2));
            use(player, plot, InteractionHand.MAIN_HAND);
            BlockState swapped = level.getBlockState(plot);
            helper.assertTrue(swapped.is(ObserverBlazeBlocks.OBSERVER_BLAZE.get()),
                    "the spyglass must swap the burner, got " + swapped);
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "entering consumes exactly one spyglass");

            BlockEntity blockEntity = level.getBlockEntity(plot);
            helper.assertTrue(blockEntity instanceof ObserverBlazeBlockEntity, "the observer block entity must exist");
            ObserverBlazeBlockEntity observer = (ObserverBlazeBlockEntity) blockEntity;
            helper.assertTrue(before.equals(observer.originalState()),
                    "the burner state must be stored, got " + observer.originalState());
            CompoundTag stored = observer.originalData();
            helper.assertTrue(stored != null && stored.getInt("burnTimeRemaining") == BURN_TIME,
                    "the burner fuel NBT must be stored, got " + stored);
            helper.succeed();
        } finally {
            if (vehicle != null) {
                vehicle.markRemoved();
            }
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 200)
    public static void spyglassOnAWorldBurnerOrPlainBlockChangesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MID);
        ServerPlayer player = player(helper, GameType.SURVIVAL);

        helper.setBlock(MID, burner(HeatLevel.SMOULDERING));
        loadFuel(level, pos, BURN_TIME, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS, 2));
        use(player, pos, InteractionHand.MAIN_HAND);
        helper.assertTrue(level.getBlockState(pos).is(AllBlocks.BLAZE_BURNER.get()),
                "a burner in the world must not be swapped");
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "a world burner must not consume the spyglass");

        helper.setBlock(MID, Blocks.STONE.defaultBlockState());
        use(player, pos, InteractionHand.MAIN_HAND);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "a plain block must not be swapped");
        helper.assertTrue(player.getMainHandItem().getCount() == 2, "a plain block must not consume the spyglass");
        helper.succeed();
    }

    // ---------------------------------------------------------------- exit and drops

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 400)
    public static void sneakWithAnEmptyHandRestoresTheBurnerAndItsFuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MarkedLavaFixture fixture = new MarkedLavaFixture(level, saveConfig());
        try {
            BlockPos world = helper.absolutePos(MID);
            helper.setBlock(MID, burner(HeatLevel.SMOULDERING));
            loadFuel(level, world, BURN_TIME, true);
            BlockState before = level.getBlockState(world);
            ServerSubLevel assembled = assemble(level, world);
            fixture.attach(assembled);
            BlockPos plot = plotOf(assembled, world);

            ServerPlayer player = player(helper, GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS, 2));
            use(player, plot, InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getBlockState(plot).is(ObserverBlazeBlocks.OBSERVER_BLAZE.get()), "entry must happen first");

            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(player, plot, InteractionHand.MAIN_HAND);

            BlockState restored = level.getBlockState(plot);
            helper.assertTrue(restored.is(AllBlocks.BLAZE_BURNER.get()),
                    "sneak with an empty hand must restore the burner, got " + restored);
            helper.assertTrue(restored.equals(before), "the restored state must equal the original, got " + restored);
            BlockEntity blockEntity = level.getBlockEntity(plot);
            helper.assertTrue(blockEntity != null, "the restored burner must have its block entity");
            CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(tag.getInt("burnTimeRemaining") == BURN_TIME,
                    "the remaining burn time must come back, got " + tag.getInt("burnTimeRemaining"));
            helper.assertTrue(tag.contains("Goggles"), "the goggles must come back");
            helper.assertTrue(player.getMainHandItem().is(Items.SPYGLASS), "the spyglass must return to the empty hand");
            helper.succeed();
        } finally {
            fixture.cleanup();
        }
    }

    /**
     * Vanilla skips the block use for a sneaking player who holds anything in either hand, so the exit has to run in
     * the interaction event. A torch in the offhand must neither block the exit nor be used, and the spyglass must
     * still come back to the empty main hand.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 400)
    public static void sneakExitsWithAnItemInTheOffhand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MarkedLavaFixture fixture = new MarkedLavaFixture(level, saveConfig());
        try {
            BlockPos world = helper.absolutePos(MID);
            helper.setBlock(MID, burner(HeatLevel.SMOULDERING));
            loadFuel(level, world, BURN_TIME, false);
            BlockState before = level.getBlockState(world);
            ServerSubLevel assembled = assemble(level, world);
            fixture.attach(assembled);
            BlockPos plot = plotOf(assembled, world);

            ServerPlayer player = player(helper, GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SPYGLASS, 2));
            use(player, plot, InteractionHand.MAIN_HAND);
            helper.assertTrue(level.getBlockState(plot).is(ObserverBlazeBlocks.OBSERVER_BLAZE.get()), "entry must happen first");

            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH, 3));
            use(player, plot, InteractionHand.MAIN_HAND);

            helper.assertTrue(level.getBlockState(plot).equals(before),
                    "sneak must exit even with an item in the offhand, got " + level.getBlockState(plot));
            helper.assertTrue(player.getMainHandItem().is(Items.SPYGLASS),
                    "the spyglass must return to the empty main hand");
            helper.assertTrue(player.getOffhandItem().is(Items.TORCH) && player.getOffhandItem().getCount() == 3,
                    "the offhand item must be neither used nor consumed");
            helper.succeed();
        } finally {
            fixture.cleanup();
        }
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 200)
    public static void breakingDropsTheBurnerAndTheSpyglass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MID);
        helper.setBlock(MID, ObserverBlazeBlocks.OBSERVER_BLAZE.get().defaultBlockState());
        BlockState state = level.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos));
        helper.assertTrue(drops.size() == 2, "the observer must drop exactly the burner and the spyglass, got " + drops);
        helper.assertTrue(drops.stream().anyMatch(stack -> stack.is(AllBlocks.BLAZE_BURNER.asItem())),
                "the burner must be dropped, got " + drops);
        helper.assertTrue(drops.stream().anyMatch(stack -> stack.is(Items.SPYGLASS)),
                "the spyglass must be dropped, got " + drops);
        ItemStack clone = state.getBlock().getCloneItemStack(level, pos, state);
        helper.assertTrue(clone.is(AllBlocks.BLAZE_BURNER.asItem()), "pick block must give the burner, got " + clone);
        helper.succeed();
    }

    /**
     * The half-block cushion is part of the block's selection box (its collision shape stays Create's heater shape,
     * which is what a vehicle needs). A cushion the player cannot point at would be a visual-only element nobody can
     * reason about, so the bottom half must be inside the selection shape.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 200)
    public static void cushionIsInsideTheSelectionButNotTheCollision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MID);
        helper.setBlock(MID, ObserverBlazeBlocks.OBSERVER_BLAZE.get().defaultBlockState());
        BlockState state = level.getBlockState(pos);
        double cushionHeight = ObserverBlazeLayout.CUSHION_HEIGHT;
        helper.assertTrue(
                state.getShape(level, pos).toAabbs().stream()
                        .anyMatch(box -> box.minY <= 0.0D && box.maxY >= cushionHeight),
                "the cushion's half block must be inside the selection shape, got " + state.getShape(level, pos));
        helper.assertTrue(
                state.getShape(level, pos).toAabbs().stream()
                        .anyMatch(box -> box.minX == 0.0D && box.maxX == 1.0D && box.maxY >= cushionHeight),
                "the cushion must fill the whole block footprint, got " + state.getShape(level, pos));
        helper.assertTrue(
                state.getCollisionShape(level, pos).toAabbs().stream()
                        .allMatch(box -> box.maxY <= 0.25D + 1.0E-6D),
                "the normal collision shape must stay Create's low heater shape, got "
                        + state.getCollisionShape(level, pos));
        helper.succeed();
    }

    // ---------------------------------------------------------------- redstone from marked lava

    /**
     * A searchlight on a vehicle aimed at an exposed lava pool; two observers, one close enough for every radius and
     * one only inside the default one. Sub-phases: marked, lamp switched off, lamp unlit, radius shrunk, highlight
     * option off. Every delayed phase cleans the tune values and the sub-level up before a failure propagates.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", batch = "observerBlazeVehicleLava",
            timeoutTicks = 400)
    public static void markedLavaPowersTheObserverAndItsNeighbours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MarkedLavaFixture fixture = new MarkedLavaFixture(level, saveConfig());
        BlockPos lampRel = new BlockPos(1, 5, 1);
        try {
            applyMarkedLavaDefaults();
            BlockPos lampWorld = helper.absolutePos(lampRel);
            helper.setBlock(lampRel, lamp(Direction.EAST, true, true));
            ServerSubLevel assembled = assemble(level, lampWorld);
            fixture.attach(assembled);
            BlockPos lampPlot = plotOf(assembled, lampWorld);
            helper.assertTrue(FrontlightSpotlight.isEnabled(level.getBlockState(lampPlot)),
                    "the assembled lamp must keep its searchlight mode");
            // A vehicle is a physics body and falls to the ground: wait until it rests, then aim the world-side
            // fixture at where the beam really is. A lamp that kept sinking would leave its own cone.
            GameTestSableRules.await(helper, "observer blaze lamp", () -> GameTestSableRules.vehicleSettled(assembled),
                    () -> "lamp beam " + beamDump(assembled, lampPlot),
                    fixture::cleanup,
                    () -> startMarkedLavaFixture(helper, level, assembled, lampPlot, fixture));
        } catch (RuntimeException | Error failure) {
            fixture.cleanup();
            throw failure;
        }
    }

    /** Lava three blocks down the settled beam, two observers and a redstone lamp, then the phase chain. */
    private static void startMarkedLavaFixture(GameTestHelper helper, ServerLevel level, ServerSubLevel vehicle,
                                               BlockPos lampPlot, MarkedLavaFixture fixture) {
        Vec3 lens = beamLens(vehicle, lampPlot);
        Vec3 direction = beamDirection(vehicle, Direction.EAST);
        BlockPos lava = BlockPos.containing(lens.add(direction.scale(3.0)));
        BlockPos near = lava.south(2);
        BlockPos far = lava.south(20);
        BlockPos redstoneLamp = near.east();
        fixture.track(lava.below());
        fixture.track(lava);
        fixture.track(near);
        fixture.track(far);
        fixture.track(redstoneLamp);
        level.setBlockAndUpdate(lava.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
        level.setBlockAndUpdate(near, observer());
        level.setBlockAndUpdate(far, observer());
        level.setBlockAndUpdate(redstoneLamp, Blocks.REDSTONE_LAMP.defaultBlockState());

        helper.runAfterDelay(25, fixture.phase(() -> {
            helper.assertTrue(level.getBlockState(lava).is(Blocks.LAVA), "the lava pool must still be there, got "
                    + level.getBlockState(lava) + " beam " + beamDump(vehicle, lampPlot));
            helper.assertTrue(powered(level, near) && powered(level, far),
                    "a lit, activated lamp aimed at exposed lava must power both observers; beam "
                            + beamDump(vehicle, lampPlot));
            helper.assertTrue(level.getSignal(near, Direction.UP) == 15, "the observer must read 15 on every side");
            helper.assertTrue(level.getBlockState(redstoneLamp).getValue(BlockStateProperties.LIT),
                    "a neighbouring redstone lamp must light up");
            // The lamp loses searchlight mode: nothing is marked any more.
            level.setBlock(lampPlot, level.getBlockState(lampPlot).setValue(FrontlightSpotlight.ENABLED, false),
                    Block.UPDATE_ALL);
            helper.runAfterDelay(25, fixture.phase(() -> {
                helper.assertTrue(!powered(level, near) && !powered(level, far),
                        "a lamp without searchlight mode must mark nothing");
                helper.assertTrue(!level.getBlockState(redstoneLamp).getValue(BlockStateProperties.LIT),
                        "the neighbouring redstone lamp must go dark again");
                // Activated but unlit: a dark lamp is not a headlight.
                level.setBlock(lampPlot, level.getBlockState(lampPlot)
                        .setValue(FrontlightSpotlight.ENABLED, true)
                        .setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL);
                helper.runAfterDelay(25, fixture.phase(() -> {
                    helper.assertTrue(!powered(level, near), "an unlit lamp must mark nothing");
                    // Lit again, but the radius shrinks below the far observer's distance.
                    level.setBlock(lampPlot, level.getBlockState(lampPlot).setValue(BlockStateProperties.LIT, true),
                            Block.UPDATE_ALL);
                    Config.OBSERVER_BLAZE_RADIUS.set(16);
                    helper.runAfterDelay(25, fixture.phase(() -> {
                        helper.assertTrue(powered(level, near),
                                "the near observer is still inside the minimum radius");
                        helper.assertTrue(!powered(level, far),
                                "lava beyond the detection radius must not power the far observer");
                        // The highlight option is the second half of the gate.
                        Config.SABLE_HEADLIGHT_LAVA_HIGHLIGHT.set(false);
                        helper.runAfterDelay(25, fixture.phase(() -> {
                            helper.assertTrue(!powered(level, near), "the highlight option must gate the detector");
                            fixture.cleanup();
                            helper.succeed();
                        }));
                    }));
                }));
            }));
        }));
    }

    /**
     * A lamp placed in the world -- never assembled into a vehicle -- aimed at exposed lava must power the observer;
     * the client lights and outlines world lamps, so the server's marked set has to include them too.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", batch = "observerBlazeWorldLamp",
            timeoutTicks = 300)
    public static void aWorldLampMarksLavaForTheObserver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MarkedLavaFixture fixture = new MarkedLavaFixture(level, saveConfig());
        try {
            applyMarkedLavaDefaults();
            BlockPos lampWorld = helper.absolutePos(WORLD_LAMP);
            helper.setBlock(WORLD_LAMP, lamp(Direction.EAST, true, true));
            BlockPos lava = lampWorld.east(3);
            fixture.track(lampWorld);
            fixture.track(lava.below());
            fixture.track(lava);
            level.setBlockAndUpdate(lava.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            BlockPos observer = lava.south(2);
            fixture.track(observer);
            level.setBlockAndUpdate(observer, observer());
            // The tracker's world-lamp cache refreshes at most every 40 ticks, so wait past a full rescan.
            helper.runAfterDelay(60, fixture.phase(() -> {
                helper.assertTrue(powered(level, observer),
                        "a lit world lamp aimed at exposed lava must power the observer, got "
                                + level.getBlockState(observer));
                fixture.cleanup();
                helper.succeed();
            }));
        } catch (RuntimeException | Error failure) {
            fixture.cleanup();
            throw failure;
        }
    }

    /**
     * Two observers, one world lamp, one lava pool: the tracker scans once per window and each observer answers with
     * its own radius, so the near one is powered while the far one is not.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", batch = "observerBlazeSharedScan",
            timeoutTicks = 300)
    public static void observersShareOneScanButKeepTheirOwnRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MarkedLavaFixture fixture = new MarkedLavaFixture(level, saveConfig());
        try {
            applyMarkedLavaDefaults();
            Config.OBSERVER_BLAZE_RADIUS.set(16);
            BlockPos lampWorld = helper.absolutePos(WORLD_LAMP);
            helper.setBlock(WORLD_LAMP, lamp(Direction.EAST, true, true));
            BlockPos lava = lampWorld.east(3);
            BlockPos near = lava.south(2);
            BlockPos far = lava.south(20);
            fixture.track(lampWorld);
            fixture.track(lava.below());
            fixture.track(lava);
            fixture.track(near);
            fixture.track(far);
            level.setBlockAndUpdate(lava.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            level.setBlockAndUpdate(near, observer());
            level.setBlockAndUpdate(far, observer());
            helper.runAfterDelay(60, fixture.phase(() -> {
                helper.assertTrue(powered(level, near),
                        "the lava two blocks from the near observer is inside the radius, got "
                                + level.getBlockState(near));
                helper.assertTrue(!powered(level, far),
                        "the same scan must not power an observer twenty blocks away at radius 16, got "
                                + level.getBlockState(far));
                fixture.cleanup();
                helper.succeed();
            }));
        } catch (RuntimeException | Error failure) {
            fixture.cleanup();
            throw failure;
        }
    }

    /** The lens of a settled vehicle lamp, in world space. */
    private static Vec3 beamLens(ServerSubLevel vehicle, BlockPos lampPlot) {
        org.joml.Vector3d out = new org.joml.Vector3d();
        Vec3 plotCenter = lampPlot.getCenter();
        vehicle.logicalPose().transformPosition(
                new org.joml.Vector3d(plotCenter.x, plotCenter.y, plotCenter.z), out);
        return new Vec3(out.x, out.y, out.z);
    }

    /** A direction rotated by the vehicle pose and normalised. */
    private static Vec3 beamDirection(ServerSubLevel vehicle, Direction facing) {
        org.joml.Vector3d out = vehicle.logicalPose().orientation().transform(
                new org.joml.Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
        return new Vec3(out.x, out.y, out.z).normalize();
    }

    private static String beamDump(ServerSubLevel vehicle, BlockPos lampPlot) {
        return beamLens(vehicle, lampPlot) + " -> " + beamDirection(vehicle, Direction.EAST);
    }

    // ---------------------------------------------------------------- no heat

    /**
     * A smouldering burner heats a basin placed above it; swapping that burner for the observer must make the placed
     * basin read no heat. Create keeps {@code BasinBlockEntity#getHeatLevel()} package-private, so the cached value
     * is read back reflectively -- that method is exactly the basin's own lookup of the block below it.
     */
    @GameTest(template = "empty", templateNamespace = "dimblend_headlights", timeoutTicks = 200)
    public static void observerGivesNoHeatToABoilerOrBasin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(MID);
        BlockPos basinPos = helper.absolutePos(MID.above());
        helper.setBlock(MID, burner(HeatLevel.SMOULDERING));
        helper.setBlock(MID.above(), AllBlocks.BASIN.get().defaultBlockState());
        BlockEntity placed = level.getBlockEntity(basinPos);
        helper.assertTrue(placed instanceof BasinBlockEntity, "the basin must have a block entity, got " + placed);
        BasinBlockEntity basin = (BasinBlockEntity) placed;
        helper.assertTrue(basinHeat(basin) == HeatLevel.SMOULDERING,
                "the fixture must heat the placed basin before the swap, got " + basinHeat(basin));

        helper.setBlock(MID, observer());
        BlockState state = level.getBlockState(pos);
        helper.assertTrue(!state.hasProperty(BlazeBurnerBlock.HEAT_LEVEL), "the observer must have no heat property");
        helper.assertTrue(!state.is(AllTags.AllBlockTags.PASSIVE_BOILER_HEATERS.tag),
                "the observer must not be a passive boiler heater");
        // A boiler asks exactly this registry question about the block under it.
        helper.assertTrue(BoilerHeater.findHeat(level, pos, state) == BoilerHeater.NO_HEAT,
                "a boiler must find no heat under the observer");
        // The basin caches its reading and clears it on its own tick, so the swap needs a tick to be seen.
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(basinHeat(basin) == HeatLevel.NONE,
                    "the placed basin above the observer must read no heat, got " + basinHeat(basin));
            helper.succeed();
        });
    }

    /** The placed basin's own heat reading; Create's accessor is package-private, hence the reflective call. */
    private static HeatLevel basinHeat(BasinBlockEntity basin) {
        try {
            Method method = BasinBlockEntity.class.getDeclaredMethod("getHeatLevel");
            method.setAccessible(true);
            return (HeatLevel) method.invoke(basin);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Create's BasinBlockEntity#getHeatLevel is no longer callable", failure);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean powered(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(ObserverBlazeBlocks.OBSERVER_BLAZE.get()) && state.getValue(ObserverBlazeBlock.POWERED);
    }

    private static BlockState observer() {
        return ObserverBlazeBlocks.OBSERVER_BLAZE.get().defaultBlockState();
    }

    private static BlockState burner(HeatLevel heat) {
        return AllBlocks.BLAZE_BURNER.get().defaultBlockState().setValue(BlazeBurnerBlock.HEAT_LEVEL, heat);
    }

    private static BlockState lamp(Direction facing, boolean enabled, boolean lit) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("dndecor", "brass_frontlight"))
                .defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing)
                .setValue(BlockStateProperties.LIT, lit)
                .setValue(FrontlightSpotlight.ENABLED, enabled);
    }

    /** Gives the burner a real fuel level, so "the fuel comes back" is observable. */
    private static void loadFuel(ServerLevel level, BlockPos pos, int burnTime, boolean goggles) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new IllegalStateException("No burner block entity at " + pos);
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("fuelLevel", 1); // BlazeBurnerBlockEntity.FuelType.NORMAL
        tag.putInt("burnTimeRemaining", burnTime);
        if (goggles) {
            tag.putBoolean("Goggles", true);
        }
        blockEntity.loadWithComponents(tag, level.registryAccess());
        blockEntity.setChanged();
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos world) {
        ServerSubLevel vehicle = SubLevelAssemblyHelper.assembleBlocks(level, world,
                BlockPos.betweenClosed(world, world), new BoundingBox3i(world, world));
        if (vehicle == null) {
            throw new IllegalStateException("Sable assembly failed at " + world);
        }
        return vehicle;
    }

    private static BlockPos plotOf(ServerSubLevel vehicle, BlockPos world) {
        return BlockPos.containing(vehicle.logicalPose().transformPositionInverse(world.getCenter()));
    }

    private static void use(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit(pos));
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(pos.getCenter(), Direction.UP, pos, false);
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        return player(helper, mode, packet -> { });
    }

    private static ServerPlayer player(GameTestHelper helper, GameType mode, Consumer<Packet<?>> packets) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "observer-test"),
                ClientInformation.createDefault());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
                packets.accept(packet);
            }
        };
        player.gameMode.changeGameModeForPlayer(mode);
        player.setPos(helper.absolutePos(MID.above(2)).getCenter());
        return player;
    }

    private record Saved(boolean headlight, boolean highlight, double range, double angle, double intensity,
                         boolean occlusion, int radius) {
    }

    private static Saved saveConfig() {
        return new Saved(Config.SABLE_HEADLIGHT.get(), Config.SABLE_HEADLIGHT_LAVA_HIGHLIGHT.get(),
                Config.SABLE_HEADLIGHT_RANGE.get(), Config.SABLE_HEADLIGHT_ANGLE.get(),
                Config.SABLE_HEADLIGHT_INTENSITY.get(), Config.SABLE_HEADLIGHT_OCCLUSION.get(),
                Config.OBSERVER_BLAZE_RADIUS.get());
    }

    private static void restore(Saved saved) {
        Config.SABLE_HEADLIGHT.set(saved.headlight());
        Config.SABLE_HEADLIGHT_LAVA_HIGHLIGHT.set(saved.highlight());
        Config.SABLE_HEADLIGHT_RANGE.set(saved.range());
        Config.SABLE_HEADLIGHT_ANGLE.set(saved.angle());
        Config.SABLE_HEADLIGHT_INTENSITY.set(saved.intensity());
        Config.SABLE_HEADLIGHT_OCCLUSION.set(saved.occlusion());
        Config.OBSERVER_BLAZE_RADIUS.set(saved.radius());
    }

    private static void applyMarkedLavaDefaults() {
        Config.SABLE_HEADLIGHT.set(true);
        Config.SABLE_HEADLIGHT_RANGE.set(24.0D);
        Config.SABLE_HEADLIGHT_ANGLE.set(50.0D);
        Config.SABLE_HEADLIGHT_INTENSITY.set(1.0D);
        Config.SABLE_HEADLIGHT_OCCLUSION.set(true);
        Config.SABLE_HEADLIGHT_LAVA_HIGHLIGHT.set(true);
        Config.OBSERVER_BLAZE_RADIUS.set(32);
    }

    /**
     * One marked-lava fixture: the tune-value snapshot, the assembled vehicle and a cleanup that runs exactly once,
     * so a failing assertion inside a delayed phase cannot leave the config at test values or a sub-level alive for
     * the rest of the run.
     */
    private static final class MarkedLavaFixture {
        private final ServerLevel level;
        private final Saved saved;
        private final Set<BlockPos> placed = new HashSet<>();
        private ServerSubLevel vehicle;
        private boolean cleaned;

        private MarkedLavaFixture(ServerLevel level, Saved saved) {
            this.level = level;
            this.saved = saved;
        }

        /** A world block this test placed; cleared again on cleanup so a later batch cannot see a lit pool. */
        private void track(BlockPos pos) {
            placed.add(pos.immutable());
        }

        private void attach(ServerSubLevel assembled) {
            this.vehicle = assembled;
        }

        /**
         * Restores the tune values, clears the fixture's world blocks and removes the sub-level; safe from any
         * failure path, and runs once.
         */
        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            restore(saved);
            for (BlockPos pos : placed) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            placed.clear();
            if (vehicle != null) {
                vehicle.markRemoved();
            }
        }

        /** A delayed phase that cleans up before rethrowing, so a GameTest failure never leaks state. */
        private Runnable phase(Runnable step) {
            return () -> {
                try {
                    step.run();
                } catch (RuntimeException | Error failure) {
                    cleanup();
                    throw failure;
                }
            };
        }
    }

    private ObserverBlazeGameTests() {
    }
}
