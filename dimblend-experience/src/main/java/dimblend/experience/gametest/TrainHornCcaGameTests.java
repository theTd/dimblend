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
