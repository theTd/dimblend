package dimblend.experience.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.compat.sable.ChassisWorldAnchorProbe;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 世界锚探针的 GameTest 入口：对一台真实的单方块 Sable 子层级建 {@code hold} 关节，
 * 验证它确实产生样本、确实在时长结束后移除，并且移除后 {@code isValid()} 为 false。
 *
 * <p>这是探针入口，不是生产锁；生产自动 tick 仍未接入。</p>
 */
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisWorldAnchorGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (ChassisEvents.available()) event.register(ChassisWorldAnchorGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 200)
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
                car.markRemoved();
                helper.runAfterDelay(5, helper::succeed);
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 60)
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

    private static ServerLevel rotating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("dimblend:rotating")));
        helper.assertTrue(level != null, "rotating dimension must be loaded");
        return level;
    }

    private ChassisWorldAnchorGameTests() {
    }
}
