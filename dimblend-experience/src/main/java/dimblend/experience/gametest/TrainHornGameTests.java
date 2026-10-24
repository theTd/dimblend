package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.create.TrainHornStateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.jetbrains.annotations.Nullable;

/**
 * 列车汽笛（Create 蒸汽引擎路径）运行时验证——模板复用 {@code item_drain_refill}
 * （3×3×3 全空平台，工作层 y=1）：储罐 (1,1,1)、汽笛 (1,2,1) 落地式朝北，
 * 红石块 (1,2,0)（汽笛正面相邻格）供信号，引擎放 (2,2,1)（汽笛右侧）。
 *
 * <p>锁定截止 tick 由 {@link TrainHornStateAccess} 读取；触发与放置同 tick 同步发生
 * （neighborChanged 在 setBlock 内同步调用），故 until == 放置时 gameTime +
 * {@link TrainHorn.HornKind#durationTicks()} 可精确断言。</p>
 *
 * <p>不加 {@code @GameTestHolder}：引用 Create 类，仅 Create 在场时由 DimBlend 显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class TrainHornGameTests {

    private static final BlockPos TANK = new BlockPos(1, 1, 1);
    private static final BlockPos WHISTLE = new BlockPos(1, 2, 1);
    private static final BlockPos SIGNAL = new BlockPos(1, 2, 0);
    private static final BlockPos ENGINE = new BlockPos(2, 2, 1);

    private TrainHornGameTests() {
    }

    /** 六面邻接蒸汽引擎时，无热源也给红石上升沿鸣笛（本需求核心口径）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void steamEngineNeighborTriggersHorn(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        long t0 = helper.getLevel().getGameTime();
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long until = hornUntil(helper);
            helper.assertTrue(until == t0 + TrainHorn.HornKind.STEAM.durationTicks(),
                    "steam engine neighbor must lock horn for steam duration, until=" + until
                            + " expected=" + (t0 + TrainHorn.HornKind.STEAM.durationTicks()));
            helper.assertTrue(hornKind(helper) == TrainHorn.HornKind.STEAM,
                    "steam engine neighbor must be recognized as STEAM, got " + hornKind(helper));
            helper.succeed();
        });
    }

    /** 无引擎邻接：红石上升沿不触发（保持 Create 原生行为）。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void whistleWithoutEngineStaysSilent(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(hornUntil(helper) == 0L,
                    "no engine neighbor must not trigger horn, until=" + hornUntil(helper));
            helper.succeed();
        });
    }

    /** 播放期间断电立即停止并清锁；不等片段结束即可重新供能、从头播放。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void powerLossStopsAndRearmsImmediately(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(hornUntil(helper) > 0L, "precondition: horn must be locked after first edge");
            helper.setBlock(SIGNAL, Blocks.AIR);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(hornUntil(helper) == 0L,
                        "power loss must immediately clear horn lock, until=" + hornUntil(helper));
                long restartedAt = helper.getLevel().getGameTime();
                helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(hornUntil(helper) == restartedAt + TrainHorn.HornKind.STEAM.durationTicks(),
                            "fresh power must immediately restart the full horn duration, until="
                                    + hornUntil(helper) + " expected="
                                    + (restartedAt + TrainHorn.HornKind.STEAM.durationTicks()));
                    helper.succeed();
                });
            });
        });
    }

    /** 持续高电平只播放一次：素材自然结束后，普通邻接更新不得重触发。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void heldPowerDoesNotRetriggerAfterPlayback(GameTestHelper helper) {
        placeWhistleRig(helper);
        helper.setBlock(ENGINE, AllBlocks.STEAM_ENGINE.get().defaultBlockState());
        helper.setBlock(SIGNAL, Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(2, () -> {
            long firstUntil = hornUntil(helper);
            helper.assertTrue(firstUntil > 0L, "precondition: horn must be locked after first edge");
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(helper.getLevel().getGameTime() >= firstUntil,
                        "precondition: steam clip must have ended");
                helper.setBlock(WHISTLE.west(), Blocks.STONE);
                helper.runAfterDelay(2, () -> {
                    helper.assertTrue(hornUntil(helper) == firstUntil,
                            "held power must not retrigger on a neighbor update, firstUntil=" + firstUntil
                                    + " now=" + hornUntil(helper));
                    helper.succeed();
                });
            });
        });
    }

    /** 复用给 CDG/CCA 的同类测试：储罐 + 朝北落地汽笛。 */
    static void placeWhistleRig(GameTestHelper helper) {
        helper.setBlock(TANK, AllBlocks.FLUID_TANK.get().defaultBlockState());
        helper.setBlock(WHISTLE, AllBlocks.STEAM_WHISTLE.get().defaultBlockState()
                .setValue(WhistleBlock.FACING, Direction.NORTH));
    }

    static long hornUntil(GameTestHelper helper) {
        if (helper.getBlockEntity(WHISTLE) instanceof TrainHornStateAccess access) {
            return access.dimblend$hornUntilTick();
        }
        helper.fail("precondition: whistle BE must implement TrainHornStateAccess");
        return -1L;
    }

    /** 最近一次锁定的音效种类（null = 从未触发；有效性由 {@link #hornUntil} 判定）。 */
    static TrainHorn.@Nullable HornKind hornKind(GameTestHelper helper) {
        if (helper.getBlockEntity(WHISTLE) instanceof TrainHornStateAccess access) {
            int ordinal = access.dimblend$hornKind();
            return ordinal < 0 ? null : TrainHorn.HornKind.values()[ordinal];
        }
        helper.fail("precondition: whistle BE must implement TrainHornStateAccess");
        return null;
    }
}
