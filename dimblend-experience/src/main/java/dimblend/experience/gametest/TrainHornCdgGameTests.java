package dimblend.experience.gametest;

import com.jesz.createdieselgenerators.CDGBlocks;
import com.simibubi.create.AllBlocks;
import dimblend.experience.compat.create.TrainHorn;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 列车汽笛 CDG 路径运行时验证——三种柴油引擎（普通/巨型/组合式）都识别为
 * {@link TrainHorn.HornKind#DIESEL}，以及多引擎邻接时的方向优先级
 * （正面 → 背面 → 左 → 右 → 上 → 下）。布局同 {@link TrainHornGameTests}
 * （汽笛 (1,2,1) 朝北，信号红石块 (1,2,0)=正面）。
 *
 * <p>不加 {@code @GameTestHolder}：引用 CDG 类，仅 CDG 在场时由 DimBlend 显式注册
 * （CDG 依赖 Create，故 Create 必然在场）。dev 运行：
 * {@code ./gradlew :dimblend-experience:runGameTestServer -PwithCdg}。</p>
 */
@PrefixGameTestTemplate(false)
public final class TrainHornCdgGameTests {

    private static final BlockPos ENGINE = new BlockPos(2, 2, 1);
    private static final BlockPos SIGNAL = new BlockPos(1, 2, 0);
    private static final BlockPos BACK = new BlockPos(1, 2, 2);
    private static final BlockPos LEFT = new BlockPos(0, 2, 1);
    private static final BlockPos ABOVE = new BlockPos(1, 3, 1);

    private TrainHornCdgGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void dieselEngineTriggersHorn(GameTestHelper helper) {
        assertDieselTrigger(helper, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void hugeDieselEngineTriggersHorn(GameTestHelper helper) {
        assertDieselTrigger(helper, CDGBlocks.HUGE_DIESEL_ENGINE.get().defaultBlockState());
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void modularDieselEngineTriggersHorn(GameTestHelper helper) {
        assertDieselTrigger(helper, CDGBlocks.MODULAR_DIESEL_ENGINE.get().defaultBlockState());
    }

    /** 背面优先于左侧：柴油@背面 + 蒸汽@左侧 → 识别为柴油。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void directionPriorityPrefersBackOverLeft(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(BACK, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        helper.setBlock(LEFT, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.DIESEL,
                    "back face must win over left face, got " + TrainHornGameTests.hornKind(helper));
            helper.succeed();
        });
    }

    /** 正面优先于背面：蒸汽@正面 + 柴油@背面 → 识别为蒸汽（信号改由上方红石块供能）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void directionPriorityPrefersFrontOverBack(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(SIGNAL, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(BACK, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(ABOVE, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long until = TrainHornGameTests.hornUntil(helper);
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.STEAM
                            && until == t0 + TrainHorn.HornKind.STEAM.durationTicks(),
                    "front face must win over back face, kind=" + TrainHornGameTests.hornKind(helper)
                            + " until=" + until + " expected=" + (t0 + TrainHorn.HornKind.STEAM.durationTicks()));
            helper.succeed();
        });
    }

    /** 左侧优先于右侧：蒸汽@左侧 + 柴油@右侧 → 识别为蒸汽。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void directionPriorityPrefersLeftOverRight(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(LEFT, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(ENGINE, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.STEAM,
                    "left face must win over right face, got " + TrainHornGameTests.hornKind(helper));
            helper.succeed();
        });
    }

    /** 右侧优先于上方：柴油@右侧 + 蒸汽@上方 → 识别为柴油（信号由正面供能）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void directionPriorityPrefersRightOverUp(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(ENGINE, CDGBlocks.DIESEL_ENGINE.get().defaultBlockState());
        helper.setBlock(ABOVE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.DIESEL,
                    "right face must win over up face, got " + TrainHornGameTests.hornKind(helper));
            helper.succeed();
        });
    }

    private static void assertDieselTrigger(GameTestHelper helper,
            net.minecraft.world.level.block.state.BlockState engine) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(ENGINE, engine);
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long until = TrainHornGameTests.hornUntil(helper);
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.DIESEL
                            && until == t0 + TrainHorn.HornKind.DIESEL.durationTicks(),
                    "diesel engine neighbor must lock horn for diesel duration, kind="
                            + TrainHornGameTests.hornKind(helper) + " until=" + until + " expected="
                            + (t0 + TrainHorn.HornKind.DIESEL.durationTicks()));
            helper.succeed();
        });
    }
}
