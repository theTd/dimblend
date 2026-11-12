package dimblend.experience.gametest;

import com.crystaelix.simurail.content.SimurailBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.ChassisPose;
import dimblend.experience.chassis.ChassisRailLockRules;
import dimblend.experience.chassis.ChassisRegistryData;
import dimblend.experience.compat.sable.ChassisRailLock;
import dimblend.experience.compat.sable.ChassisWorldAnchorProbe;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 世界锚探针与生产平面轨锁（{@code DECISIONS.md} #2，Option B）的 GameTest 验证。
 */
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisWorldAnchorGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (ChassisEvents.available()) {
            event.register(ChassisWorldAnchorGameTests.class);
        }
    }

    @GameTest(
            template = "empty",
            templateNamespace = "dimblend_chassis",
            batch = "chassis_world_anchor_probe_hold",
            timeoutTicks = 200)
    public static void holdProbeSamplesAndRemovesItsWorldAnchor(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        BlockPos body = new BlockPos(512, 65, 0);
        for (int x = (body.getX() - 8) >> 4; x <= (body.getX() + 8) >> 4; x++) {
            level.getChunk(x, 0);
        }
        level.setBlockAndUpdate(body, Blocks.STONE.defaultBlockState());
        ServerSubLevel car = SubLevelAssemblyHelper.assembleBlocks(level, body, List.of(body),
                new BoundingBox3i(body, body));
        helper.assertTrue(car != null, "a one-block Sable sub-level is created");
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        container.addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(ChassisWorldAnchorProbe.start(level, car,
                            ChassisWorldAnchorProbe.Mode.HOLD, 40),
                    "the hold probe starts on the test car: " + ChassisWorldAnchorProbe.failure());
            helper.assertTrue(ChassisWorldAnchorProbe.isActive(), "the probe is active");
            helper.assertTrue(ChassisWorldAnchorProbe.activeJointValid(), "the world anchor joint is valid");
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(!ChassisWorldAnchorProbe.isActive(), "the probe stops after its duration");
                ChassisWorldAnchorProbe.Report report = ChassisWorldAnchorProbe.lastReport();
                helper.assertTrue(report != null, "the probe leaves a report");
                helper.assertTrue(report.jointCreated(), "the probe created a joint");
                helper.assertTrue(report.jointRemoveAttempted(), "the probe attempted to remove the joint");
                helper.assertTrue(report.jointRemoved(), "the joint is actually removed (isValid=false)");
                helper.assertTrue(!report.jointValidAfterRemove(), "the removed joint is invalid");
                helper.assertTrue(report.sampleCount() >= 2, "the probe sampled the car repeatedly");
                helper.assertTrue(report.samples().stream().anyMatch(ChassisWorldAnchorProbe.Sample::handleValid),
                        "at least one sample saw a valid rigid body handle");
                container.removeForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                car.markRemoved();
                helper.runAfterDelay(5, helper::succeed);
            });
        });
    }

    @GameTest(
            template = "empty",
            templateNamespace = "dimblend_chassis",
            batch = "chassis_world_anchor_probe_missing",
            timeoutTicks = 60)
    public static void missingTargetNeverCreatesAConstraint(GameTestHelper helper) {
        ChassisWorldAnchorProbe.shutdown();
        ServerLevel level = rotating(helper);
        helper.assertTrue(!ChassisWorldAnchorProbe.start(level, (ServerSubLevel) null,
                        ChassisWorldAnchorProbe.Mode.HOLD, 40),
                "a null target refuses to start");
        helper.assertTrue(!ChassisWorldAnchorProbe.isActive(), "a refused start leaves no active probe");
        helper.assertTrue(ChassisWorldAnchorProbe.failure().contains("invalid_target"),
                "the refusal names the invalid target, got " + ChassisWorldAnchorProbe.failure());
        helper.succeed();
    }

    @GameTest(
            template = "empty",
            templateNamespace = "dimblend_chassis",
            batch = "chassis_world_anchor_unregistered",
            timeoutTicks = 320)
    public static void unregisteredBogeyCarLocksDeckAt66RefreshesBeyond32BlocksAndKeepsMassData(
            GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        // GameTestServer has 0 players in dimblend:rotating, so hold chunk force-tickets across
        // the test corridor (cleaned up before succeed) so ServerChunkCache#getChunkNow stays non-null.
        for (int cx = 38; cx <= 44; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        // Clear vault debris (trees/moss from neighbor decoration, see dimblend-dev §8.20) and lay
        // a uniform connected straight XO rail runway across X=612..700 at Y=64, Z=0.
        BlockState xoTrack = AllBlocks.TRACK.getDefaultState()
                .setValue(TrackBlock.SHAPE, TrackShape.XO);
        for (int x = 612; x <= 700; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 65; y <= 69; y++) {
                    BlockPos clearPos = new BlockPos(x, y, z);
                    if (!level.getBlockState(clearPos).isAir()) {
                        level.setBlockAndUpdate(clearPos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            BlockPos railPos = new BlockPos(x, 64, 0);
            if (!level.getBlockState(railPos).equals(xoTrack)) {
                level.setBlockAndUpdate(railPos, xoTrack);
            }
            TrackPropagator.onRailAdded(level, railPos, level.getBlockState(railPos));
        }

        // Assemble an unregistered two-bogey car anchored at Y=66 while its bogeys sit at Y=65,
        // directly exercising bogeyPlotY + 1.0 -> world Y=66.0 without registry enrollment and
        // with off-center roll weight across Z=+1 / Z=-1.
        BlockPos rearBogey = new BlockPos(622, 65, 0);
        BlockPos frontBogey = new BlockPos(628, 65, 0);
        BlockPos topAnchor = new BlockPos(625, 66, 0);
        BlockPos leftWeight = new BlockPos(625, 66, 1);
        BlockPos rightWeight = new BlockPos(625, 66, -1);
        BlockState eastBogey = SimurailBlocks.PHYSICS_BOGEY.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(rearBogey, eastBogey);
        level.setBlockAndUpdate(frontBogey, eastBogey);
        java.util.ArrayList<BlockPos> cells = new java.util.ArrayList<>();
        cells.add(rearBogey);
        cells.add(frontBogey);
        for (int x = 622; x <= 628; x++) {
            BlockPos deckBlock = new BlockPos(x, 66, 0);
            level.setBlockAndUpdate(deckBlock, Blocks.IRON_BLOCK.defaultBlockState());
            cells.add(deckBlock);
        }
        level.setBlockAndUpdate(leftWeight, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(rightWeight, Blocks.STONE.defaultBlockState());
        cells.add(leftWeight);
        cells.add(rightWeight);

        ServerSubLevel car = SubLevelAssemblyHelper.assembleBlocks(
                level,
                topAnchor,
                cells,
                new BoundingBox3i(622, 65, -1, 628, 66, 1));
        helper.assertTrue(car != null, "unregistered bogey car must assemble");

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        container.addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        double initialMass = car.getMassTracker().getMass();
        Vector3d initialCom = new Vector3d(car.getMassTracker().getCenterOfMass());
        org.joml.Matrix3d initialInertia = new org.joml.Matrix3d(car.getMassTracker().getInertiaTensor());
        int bogeyPlotY = car.getPlot().getCenterBlock().getY() - 1;
        Vec3 plotCenter = car.getPlot().getCenterBlock().getCenter();
        Vector3d plotDeck = new Vector3d(plotCenter.x, bogeyPlotY + 1.0, plotCenter.z);

        helper.runAfterDelay(50, () -> {
            helper.assertTrue("LOCKED".equals(ChassisRailLock.status(level, car.getUniqueId())),
                    "unregistered bogey car must lock automatically, got "
                            + ChassisRailLock.status(level, car.getUniqueId()));
            helper.assertTrue(ChassisRegistryData.get(level).registry().get(car.getUniqueId()) == null,
                    "unregistered car must not be written into ChassisRegistryData");

            // Top up cruise speed every 10 ticks against Simurail's unpiloted deadman brake drag while
            // keeping per-tick step < 1.0 block/tick (15 m/s = 0.75 blocks/tick) across > 32 blocks.
            driveStepsAndAssert(
                    helper,
                    level,
                    container,
                    car,
                    plotDeck,
                    initialMass,
                    initialCom,
                    initialInertia,
                    10);
        });
    }

    private static void driveStepsAndAssert(
            GameTestHelper helper,
            ServerLevel level,
            ServerSubLevelContainer container,
            ServerSubLevel car,
            Vector3d plotDeck,
            double initialMass,
            Vector3d initialCom,
            org.joml.Matrix3d initialInertia,
            int remainingSteps) {
        double sign = (remainingSteps % 2 == 0) ? 1.0 : -1.0;
        topUpCruiseAndLoad(car, 15.0, -6.0, 0.4 * sign, -0.4 * sign);
        helper.runAfterDelay(10, () -> {
            if (remainingSteps > 1) {
                driveStepsAndAssert(
                        helper,
                        level,
                        container,
                        car,
                        plotDeck,
                        initialMass,
                        initialCom,
                        initialInertia,
                        remainingSteps - 1);
                return;
            }
            PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
            Pose3d pose = pipeline.readPose(car, new Pose3d());
            Vector3d comNow = new Vector3d(car.getMassTracker().getCenterOfMass());
            Vector3d worldDeck = ChassisPose.anchor(
                    pose.orientation(), plotDeck, comNow, pose.position(), new Vector3d());
            ChassisPose.Angles angles = ChassisPose.angles(pose.orientation());

            helper.assertTrue("LOCKED".equals(ChassisRailLock.status(level, car.getUniqueId())),
                    "lock must hold across >32-block setFrame1 refresh, got "
                            + ChassisRailLock.status(level, car.getUniqueId()));
            helper.assertTrue(worldDeck.x - 625.5 > 32.5,
                    "car must move freely >32 blocks along X, got x=" + worldDeck.x);
            helper.assertTrue(Math.abs(worldDeck.y - 66.0) < 0.08,
                    "deck Y must stay hard-locked at 66.0 under downward load, got " + worldDeck.y);
            helper.assertTrue(Math.abs(angles.pitchDegrees()) < 1.5
                            && Math.abs(angles.rollDegrees()) < 1.5,
                    "pitch and roll must stay locked near zero, got pitch="
                            + angles.pitchDegrees() + " roll=" + angles.rollDegrees());
            helper.assertTrue(Math.abs(car.getMassTracker().getMass() - initialMass) < 1.0E-6
                            && initialCom.distance(comNow) < 1.0E-6
                            && initialInertia.equals(car.getMassTracker().getInertiaTensor(), 1.0E-6),
                    "MassData must remain 100% untouched");

            ChassisRailLock.release(level, car.getUniqueId(), "gametest_cleanup");
            container.removeForceLoadTicket(
                    car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            car.markRemoved();
            for (int cx = 38; cx <= 44; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
            helper.runAfterDelay(5, helper::succeed);
        });
    }

    private static void topUpCruiseAndLoad(
            ServerSubLevel car, double targetVx, double downwardDv, double pitchW, double rollW) {
        RigidBodyHandle handle = RigidBodyHandle.of(car);
        if (handle == null || !handle.isValid()) {
            return;
        }
        Vector3d currentVel = handle.getLinearVelocity(new Vector3d());
        double dvx = Math.max(0.0, targetVx - currentVel.x);
        handle.addLinearAndAngularVelocity(
                new Vector3d(dvx, downwardDv, 0.0),
                new Vector3d(pitchW, 0.0, rollW));
    }

    @GameTest(
            template = "empty",
            templateNamespace = "dimblend_chassis",
            batch = "chassis_world_anchor_option_b",
            timeoutTicks = 200)
    public static void optionBConstraintAllowsFreeXZAndYawWhileLockingDeckHeightAndPitchRoll(
            GameTestHelper helper) {
        helper.assertTrue(ChassisRailLockRules.horizontalAndYawFree(),
                "Option B rule set must leave LINEAR_X, LINEAR_Z, and ANGULAR_Y free");
        ServerLevel level = rotating(helper);
        for (int cx = 45; cx <= 49; cx++) {
            for (int cz = 1; cz <= 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        for (int x = 730; x <= 764; x++) {
            for (int z = 26; z <= 60; z++) {
                for (int y = 63; y <= 69; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        BlockPos anchor = new BlockPos(736, 65, 32);
        List<BlockPos> blocks = List.of(anchor, anchor.east(), anchor.south(), anchor.above());
        for (BlockPos block : blocks) {
            level.setBlockAndUpdate(block, Blocks.IRON_BLOCK.defaultBlockState());
        }
        ServerSubLevel car = SubLevelAssemblyHelper.assembleBlocks(
                level,
                anchor,
                blocks,
                new BoundingBox3i(736, 65, 32, 737, 66, 33));
        helper.assertTrue(car != null, "free-plane test body must assemble");

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        container.addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        double initialMass = car.getMassTracker().getMass();
        Vector3d initialCom = new Vector3d(car.getMassTracker().getCenterOfMass());
        org.joml.Matrix3d initialInertia = new org.joml.Matrix3d(car.getMassTracker().getInertiaTensor());

        helper.runAfterDelay(10, () -> {
            PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
            Pose3d initialPose = pipeline.readPose(car, new Pose3d());
            Vec3 plotCenter = car.getPlot().getCenterBlock().getCenter();
            Vector3d plotDeck = new Vector3d(plotCenter.x, plotCenter.y + 0.5, plotCenter.z);
            Vector3d com = new Vector3d(car.getMassTracker().getCenterOfMass());
            Vector3d initialDeck = ChassisPose.anchor(
                    initialPose.orientation(), plotDeck, com, initialPose.position(), new Vector3d());

            EnumSet<ConstraintJointAxis> axes = EnumSet.noneOf(ConstraintJointAxis.class);
            for (ChassisRailLockRules.Axis axis : ChassisRailLockRules.lockedAxes()) {
                axes.add(ConstraintJointAxis.valueOf(axis.name()));
            }
            GenericConstraintHandle joint = pipeline.addConstraint(
                    null,
                    car,
                    new GenericConstraintConfiguration(
                            new Vector3d(initialDeck.x, 66.0, initialDeck.z),
                            plotDeck,
                            ChassisPose.yawFrame(initialPose.orientation()),
                            new Quaterniond(),
                            axes));
            helper.assertTrue(joint != null && joint.isValid(), "Option B constraint must be valid");

            RigidBodyHandle handle = RigidBodyHandle.of(car);
            helper.assertTrue(handle != null && handle.isValid(), "rigid body handle must be valid");
            // Simultaneously excite +X, -Y (heavy downward load), +Z, pitch, yaw (+Y), and roll.
            handle.addLinearAndAngularVelocity(
                    new Vector3d(12.0, -20.0, 12.0),
                    new Vector3d(5.0, 3.5, -5.0));

            helper.runAfterDelay(25, () -> {
                Pose3d midPose = pipeline.readPose(car, new Pose3d());
                Vector3d midCom = new Vector3d(car.getMassTracker().getCenterOfMass());
                Vector3d midDeck = ChassisPose.anchor(
                        midPose.orientation(), plotDeck, midCom, midPose.position(), new Vector3d());
                ChassisPose.Angles midAngles = ChassisPose.angles(midPose.orientation());

                helper.assertTrue(Math.abs(midDeck.y - 66.0) < 0.05,
                        "LINEAR_Y must hold Y=66.0 with zero sag, got " + midDeck.y);
                helper.assertTrue(Math.abs(midAngles.pitchDegrees()) < 1.0
                                && Math.abs(midAngles.rollDegrees()) < 1.0,
                        "ANGULAR_X and ANGULAR_Z must hold pitch/roll near zero, got pitch="
                                + midAngles.pitchDegrees() + " roll=" + midAngles.rollDegrees());
                helper.assertTrue(midDeck.x - initialDeck.x > 3.0,
                        "LINEAR_X must remain free, got dx=" + (midDeck.x - initialDeck.x));
                helper.assertTrue(midDeck.z - initialDeck.z > 3.0,
                        "LINEAR_Z must remain free, got dz=" + (midDeck.z - initialDeck.z));
                helper.assertTrue(Math.abs(midAngles.yawDegrees()) > 20.0,
                        "ANGULAR_Y must rotate freely past 15 deg, got yaw=" + midAngles.yawDegrees());
                helper.assertTrue(ChassisRailLockRules.needsFrameRefresh(
                                midDeck.x - initialDeck.x,
                                midDeck.z - initialDeck.z,
                                midAngles.yawDegrees(),
                                0.0),
                        "yaw rotation >15 deg must trigger setFrame1 refresh rule");

                // Refresh world frame in-place via setFrame1 and apply another downward shock.
                joint.setFrame1(
                        new Vector3d(midDeck.x, 66.0, midDeck.z),
                        ChassisPose.yawFrame(midPose.orientation()));
                RigidBodyHandle refreshedHandle = RigidBodyHandle.of(car);
                if (refreshedHandle != null && refreshedHandle.isValid()) {
                    refreshedHandle.addLinearAndAngularVelocity(
                            new Vector3d(6.0, -18.0, 6.0),
                            new Vector3d(-4.0, 2.0, 4.0));
                }

                helper.runAfterDelay(15, () -> {
                    Pose3d finalPose = pipeline.readPose(car, new Pose3d());
                    Vector3d finalCom = new Vector3d(car.getMassTracker().getCenterOfMass());
                    Vector3d finalDeck = ChassisPose.anchor(
                            finalPose.orientation(), plotDeck, finalCom, finalPose.position(), new Vector3d());
                    ChassisPose.Angles finalAngles = ChassisPose.angles(finalPose.orientation());

                    helper.assertTrue(joint.isValid(), "joint must stay valid after setFrame1");
                    helper.assertTrue(Math.abs(finalDeck.y - 66.0) < 0.05,
                            "deck Y must stay locked at 66.0 after setFrame1, got " + finalDeck.y);
                    helper.assertTrue(Math.abs(finalAngles.pitchDegrees()) < 1.0
                                    && Math.abs(finalAngles.rollDegrees()) < 1.0,
                            "pitch/roll must stay locked after setFrame1, got pitch="
                                    + finalAngles.pitchDegrees() + " roll=" + finalAngles.rollDegrees());
                    helper.assertTrue(Math.abs(car.getMassTracker().getMass() - initialMass) < 1.0E-6
                                    && initialCom.distance(finalCom) < 1.0E-6
                                    && initialInertia.equals(car.getMassTracker().getInertiaTensor(), 1.0E-6),
                            "MassData must remain unchanged");

                    joint.remove();
                    container.removeForceLoadTicket(
                            car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    car.markRemoved();
                    helper.runAfterDelay(5, helper::succeed);
                });
            });
        });
    }

    private static ServerLevel rotating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("dimblend:rotating")));
        helper.assertTrue(level != null, "rotating dimension must be loaded");
        return level;
    }

    private ChassisWorldAnchorGameTests() {
    }
}
