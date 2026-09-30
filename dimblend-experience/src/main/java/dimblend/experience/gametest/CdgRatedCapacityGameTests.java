package dimblend.experience.gametest;

import com.jesz.createdieselgenerators.CDGBlocks;
import com.jesz.createdieselgenerators.CDGFluids;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * B7 爬梯/波动只改转速、应力容量恒按额定（{@code CdgRatedCapacityMath}）运行时验证。
 * 需要 CDG 进 dev 运行：{@code ./gradlew :dimblend-experience:runGameTestServer -PwithCdg}；
 * 仅 CDG 在场时注册。模板复用 {@code item_drain_refill}（3×3×3 空平台），y=1 z=1。
 * 容量期望值按 CDG 自身公式现算（柴油 1.3.15 数据：普通 96rpm/6144SU，巨型 224rpm/16384SU）。
 *
 * <p>不加 {@code @GameTestHolder}：NeoForge 会按扫描数据自动注册所有带该注解的类，
 * 无 CDG 时加载本类即 NoClassDefFoundError；只由 DimBlend 在 CDG 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class CdgRatedCapacityGameTests {

    private static final BlockPos WEST = new BlockPos(0, 1, 1);
    private static final BlockPos MID = new BlockPos(1, 1, 1);
    private static final BlockPos EAST = new BlockPos(2, 1, 1);
    /** 额定饱和所需爬梯 tick 远超任何燃料额定（16 + 2×200 rpm）。 */
    private static final int RATED_RAMP_TICKS = 80 * 200;
    private static final float TOLERANCE = 0.5F;

    private CdgRatedCapacityGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void rampingEngineSuppliesRatedCapacity(GameTestHelper helper) {
        helper.setBlock(WEST, engine(Direction.EAST));
        helper.setBlock(MID, shaftX());
        helper.setBlock(EAST, AllBlocks.ENCASED_FAN.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        DieselEngineBlockEntity engine = helper.getBlockEntity(WEST);
        fuel(engine.getTank(), 1000);
        helper.runAfterDelay(10, () -> {
            float rated = ratedSpeed(engine);
            helper.assertTrue(engine.getGeneratedSpeed() != 0.0F, "precondition: engine must be running");
            helper.assertTrue(Math.abs(engine.getGeneratedSpeed()) < rated,
                    "precondition: engine must still be ramping, got " + engine.getGeneratedSpeed());
            assertCapacity(helper, engine.getOrCreateNetwork(), ratedCapacity(engine), "ramping engine");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void divergentFluctuationKeepsSummedCapacity(GameTestHelper helper) {
        helper.setBlock(WEST, engine(Direction.EAST));
        helper.setBlock(MID, shaftX());
        helper.setBlock(EAST, engine(Direction.WEST));
        DieselEngineBlockEntity west = helper.getBlockEntity(WEST);
        DieselEngineBlockEntity east = helper.getBlockEntity(EAST);
        // 对置两台同向转：东侧反转方向设置（与 CDG 放置时的对置翻转同口径）
        BlockEntityBehaviour.get(east, ScrollValueBehaviour.TYPE).setValue(1);
        fuel(west.getTank(), 1000);
        fuel(east.getTank(), 1000);
        helper.runAfterDelay(5, () -> {
            // 两台都已额定：西 100%、东 80%，计时拉长不再重抽
            holdAtRated(west, 1.0F);
            holdAtRated(east, 0.8F);
            helper.runAfterDelay(10, () -> {
                float rated = ratedSpeed(west);
                helper.assertTrue(Math.abs(Math.abs(west.getGeneratedSpeed()) - rated) < TOLERANCE,
                        "precondition: west engine must run at rated, got " + west.getGeneratedSpeed());
                helper.assertTrue(Math.abs(Math.abs(east.getGeneratedSpeed()) - rated * 0.8F) < TOLERANCE,
                        "precondition: east engine must run at 80%, got " + east.getGeneratedSpeed());
                helper.assertTrue(west.hasNetwork() && west.network.equals(east.network),
                        "precondition: both engines must share one network");
                assertCapacity(helper, west.getOrCreateNetwork(), ratedCapacity(west) + ratedCapacity(east),
                        "two engines at 100%/80%");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void rampingHugeEngineSuppliesRatedCapacity(GameTestHelper helper) {
        // 先放轴（Z 轴），巨型机 onPlace 把 FACING 方向 2 格处的非同轴传动杆换成动力轴
        helper.setBlock(EAST, AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        helper.setBlock(WEST, CDGBlocks.HUGE_DIESEL_ENGINE.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        HugeDieselEngineBlockEntity engine = helper.getBlockEntity(WEST);
        fuel(engine.getTank(), 100);
        helper.runAfterDelay(10, () -> {
            PoweredEngineShaftBlockEntity shaft = engine.getShaft();
            helper.assertTrue(shaft != null, "precondition: huge engine must see its powered shaft");
            float rated = Math.abs(engine.getFuelSpeed() * engine.getThrottle());
            helper.assertTrue(shaft.getGeneratedSpeed() != 0.0F, "precondition: shaft must be driven");
            helper.assertTrue(Math.abs(shaft.getGeneratedSpeed()) < rated,
                    "precondition: huge engine must still be ramping, got " + shaft.getGeneratedSpeed());
            float expected = engine.getUpgrade().getCapacity(engine.getFuelCapacity(), engine) * rated;
            assertCapacity(helper, shaft.getOrCreateNetwork(), expected, "ramping huge engine");
            helper.succeed();
        });
    }

    private static net.minecraft.world.level.block.state.BlockState engine(Direction facing) {
        return CDGBlocks.DIESEL_ENGINE.get().defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    private static net.minecraft.world.level.block.state.BlockState shaftX() {
        return AllBlocks.SHAFT.get().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    }

    private static void fuel(IFluidHandler tank, int amount) {
        Fluid diesel = CDGFluids.DIESEL.getSource();
        tank.fill(new FluidStack(diesel, amount), IFluidHandler.FluidAction.EXECUTE);
    }

    private static void holdAtRated(DieselEngineBlockEntity engine, float factor) {
        CdgEngineState state = engine.getData(CdgAttachments.ENGINE_STATE);
        state.rampTicks = RATED_RAMP_TICKS;
        state.fluctFactor = factor;
        state.fluctTicksLeft = 10_000;
    }

    private static float ratedSpeed(DieselEngineBlockEntity engine) {
        return Math.abs(engine.getUpgrade().getSpeed(engine.getFuelSpeed(), engine) * engine.getThrottle());
    }

    /** 额定总 SU：CDG 每转容量公式的分子（额定转速下 每转容量 × 额定 恒等于它）。 */
    private static float ratedCapacity(DieselEngineBlockEntity engine) {
        return engine.getUpgrade().getCapacity(engine.getFuelCapacity() * engine.getFuelSpeed(), engine);
    }

    private static void assertCapacity(GameTestHelper helper, KineticNetwork network, float expected, String stage) {
        float actual = network.calculateCapacity();
        helper.assertTrue(Math.abs(actual - expected) < TOLERANCE,
                stage + ": network capacity must stay at rated " + expected + ", got " + actual);
    }
}
