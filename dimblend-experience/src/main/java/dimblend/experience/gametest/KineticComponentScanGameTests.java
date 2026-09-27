package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.compat.create.KineticComponentScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * F2 连通扫描运行时验证（{@link KineticComponentScan}）：simulated 便携引擎不在开发运行
 * 类路径上，这里用 Create 齿轮/创造马达作“引擎”替身，锁定引擎互斥依赖的两件事——
 * 零转速静置时按 Create 连接规则判连通（新需求），以及通电离合器在静置/运转两种状态下
 * 都隔断两侧（避免误拆玩家有意隔开的引擎）。同时验证 {@code RotationPropagatorInvoker}
 * 静态 invoker 织入成功（未织入会抛 AssertionError）。
 *
 * <p>布局沿 X 轴一字排开：{@code LEFT - MID - RIGHT}，y=1 z=1；模板复用
 * {@code item_drain_refill}（3×3×3 空平台）。仅 Create 在场时注册。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class KineticComponentScanGameTests {

    private static final BlockPos LEFT = new BlockPos(0, 1, 1);
    private static final BlockPos MID = new BlockPos(1, 1, 1);
    private static final BlockPos RIGHT = new BlockPos(2, 1, 1);
    private static final BlockPos ABOVE_MID = new BlockPos(1, 2, 1);

    private KineticComponentScanGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void idleShaftLinksBothEnds(GameTestHelper helper) {
        placeCogs(helper);
        helper.setBlock(MID, axisX(AllBlocks.SHAFT.get().defaultBlockState()));
        helper.runAfterDelay(2, () -> {
            assertIdle(helper);
            assertScan(helper, LEFT, 2, "idle shaft must link both cogwheels (speed 0 still counts)");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void idleGapSeparates(GameTestHelper helper) {
        placeCogs(helper);
        helper.runAfterDelay(2, () -> {
            assertScan(helper, LEFT, 1, "cogwheels with an air gap must not be linked");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void idleUnpoweredClutchLinks(GameTestHelper helper) {
        placeCogs(helper);
        helper.setBlock(MID, clutch(false));
        helper.runAfterDelay(2, () -> {
            assertIdle(helper);
            assertScan(helper, LEFT, 2, "idle unpowered clutch must link both sides");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void idlePoweredClutchSeparates(GameTestHelper helper) {
        placeCogs(helper);
        helper.setBlock(ABOVE_MID, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(MID, clutch(true));
        helper.runAfterDelay(2, () -> {
            assertIdle(helper);
            helper.assertBlockProperty(MID, BlockStateProperties.POWERED, true);
            // Create 对无源离合器一律判连通；此处锁定扫描按运转语义隔断
            assertScan(helper, LEFT, 1, "idle powered clutch must separate the left side");
            assertScan(helper, RIGHT, 1, "idle powered clutch must separate the right side");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void runningUnpoweredClutchLinks(GameTestHelper helper) {
        placeRunningLine(helper, false);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(speedAt(helper, RIGHT) != 0, "precondition: motor must drive the cogwheel");
            assertScan(helper, LEFT, 2, "running unpowered clutch must link motor and cogwheel");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void runningPoweredClutchSeparates(GameTestHelper helper) {
        placeRunningLine(helper, true);
        helper.runAfterDelay(10, () -> {
            KineticBlockEntity clutch = kinetic(helper, MID);
            helper.assertTrue(clutch.hasSource(), "precondition: clutch must be sourced by the motor");
            helper.assertTrue(speedAt(helper, RIGHT) == 0, "precondition: powered clutch must not drive the cogwheel");
            assertScan(helper, LEFT, 1, "running powered clutch must separate motor from cogwheel");
            assertScan(helper, RIGHT, 1, "running powered clutch must separate cogwheel from motor");
            helper.succeed();
        });
    }

    private static void placeCogs(GameTestHelper helper) {
        BlockState cog = axisX(AllBlocks.COGWHEEL.get().defaultBlockState());
        helper.setBlock(LEFT, cog);
        helper.setBlock(RIGHT, cog);
    }

    private static void placeRunningLine(GameTestHelper helper, boolean powered) {
        if (powered) {
            helper.setBlock(ABOVE_MID, Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        helper.setBlock(LEFT, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        helper.setBlock(MID, clutch(powered));
        helper.setBlock(RIGHT, axisX(AllBlocks.COGWHEEL.get().defaultBlockState()));
    }

    private static BlockState clutch(boolean powered) {
        return axisX(AllBlocks.CLUTCH.get().defaultBlockState()).setValue(BlockStateProperties.POWERED, powered);
    }

    private static BlockState axisX(BlockState state) {
        return state.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    }

    /** “引擎”替身：齿轮与创造马达。 */
    private static final Predicate<BlockState> MARKER = state ->
            state.is(AllBlocks.COGWHEEL.get()) || state.is(AllBlocks.CREATIVE_MOTOR.get());

    private static void assertScan(GameTestHelper helper, BlockPos rel, int expected, String message) {
        List<BlockPos> found = KineticComponentScan.collectMatching(
                helper.getLevel(), helper.absolutePos(rel), new HashSet<>(), MARKER);
        helper.assertTrue(found.size() == expected, message + " (expected " + expected + ", got " + found.size() + ")");
    }

    private static void assertIdle(GameTestHelper helper) {
        helper.assertTrue(speedAt(helper, LEFT) == 0 && speedAt(helper, RIGHT) == 0,
                "precondition: network must be idle (speed 0)");
    }

    private static float speedAt(GameTestHelper helper, BlockPos rel) {
        return kinetic(helper, rel).getSpeed();
    }

    private static KineticBlockEntity kinetic(GameTestHelper helper, BlockPos rel) {
        if (helper.getBlockEntity(rel) instanceof KineticBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("no kinetic block entity at " + rel);
    }
}
