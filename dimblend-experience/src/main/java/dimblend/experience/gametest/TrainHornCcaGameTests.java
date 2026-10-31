package dimblend.experience.gametest;

import com.mrh0.createaddition.index.CABlocks;
import dimblend.experience.compat.create.TrainHorn;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 列车汽笛 CCA 路径运行时验证——电动马达与模块化电池都识别为
 * {@link TrainHorn.HornKind#ELEC}（CCA 1.5.10 旧 {@code accumulator} 已从注册表
 * 移除，电动组仅此两种）。布局同 {@link TrainHornGameTests}。
 *
 * <p>不加 {@code @GameTestHolder}：引用 CCA 类，仅 CCA 在场时由 DimBlend 显式注册
 * （CCA 依赖 Create，故 Create 必然在场）。dev 运行：
 * {@code ./gradlew :dimblend-experience:runGameTestServer -PwithCca}。</p>
 */
@PrefixGameTestTemplate(false)
public final class TrainHornCcaGameTests {

    private static final BlockPos ENGINE = new BlockPos(2, 2, 1);
    private static final BlockPos SIGNAL = new BlockPos(1, 2, 0);

    private TrainHornCcaGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void electricMotorTriggersHorn(GameTestHelper helper) {
        assertElecTrigger(helper, CABlocks.ELECTRIC_MOTOR.get().defaultBlockState());
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void modularAccumulatorTriggersHorn(GameTestHelper helper) {
        assertElecTrigger(helper, CABlocks.MODULAR_ACCUMULATOR.get().defaultBlockState());
    }

    /** 电动马达邻接同样算特殊模式：进入时重置音高 1.0（刻度 10）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void electricMotorEntersSpecialModeResetsPitch(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(ENGINE, CABlocks.ELECTRIC_MOTOR.get().defaultBlockState());
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(TrainHornGameTests.specialMode(helper),
                    "electric motor neighbor must enter special mode");
            helper.assertTrue(TrainHornGameTests.pitchTenths(helper) == TrainHorn.PITCH_TENTHS_DEFAULT,
                    "electric entry must reset the pitch to 1.0, tenths=" + TrainHornGameTests.pitchTenths(helper));
            helper.succeed();
        });
    }

    /**
     * 电动路径的「沿开始、电平结束」（2026-10-31，取代 2026-10-26 规则 B）：红石下降沿
     * 清锁（= 下发给客户端的停止包），复电后新的上升沿立即重新起播。
     */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 60)
    public static void elecPowerLossStopsAndRestartsOnNextEdge(GameTestHelper helper) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(ENGINE, CABlocks.ELECTRIC_MOTOR.get().defaultBlockState());
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.assertTrue(TrainHornGameTests.hornUntil(helper) == t0 + TrainHorn.HornKind.ELEC.durationTicks(),
                "precondition: the rising edge must start the electric horn");
        helper.setBlock(SIGNAL, Blocks.AIR);
        helper.assertTrue(TrainHornGameTests.hornUntil(helper) == 0L,
                "electric power loss must clear the lock at once, until=" + TrainHornGameTests.hornUntil(helper));
        long rise = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.ELEC,
                    "the restart must stay ELEC, got " + TrainHornGameTests.hornKind(helper));
            helper.assertTrue(TrainHornGameTests.hornUntil(helper) == rise + TrainHorn.HornKind.ELEC.durationTicks(),
                    "the rising edge after power loss must restart the electric horn, until="
                            + TrainHornGameTests.hornUntil(helper));
            helper.succeed();
        });
    }

    private static void assertElecTrigger(GameTestHelper helper,
            net.minecraft.world.level.block.state.BlockState engine) {
        TrainHornGameTests.placeWhistleRig(helper);
        helper.setBlock(ENGINE, engine);
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long until = TrainHornGameTests.hornUntil(helper);
            helper.assertTrue(TrainHornGameTests.hornKind(helper) == TrainHorn.HornKind.ELEC
                            && until == t0 + TrainHorn.HornKind.ELEC.durationTicks(),
                    "electric neighbor must lock horn for elec duration, kind="
                            + TrainHornGameTests.hornKind(helper) + " until=" + until + " expected="
                            + (t0 + TrainHorn.HornKind.ELEC.durationTicks()));
            helper.succeed();
        });
    }
}
