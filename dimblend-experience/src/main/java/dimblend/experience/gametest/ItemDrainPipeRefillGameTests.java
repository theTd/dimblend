package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 运行时织入检查：管道补水（见 {@code ItemDrainPipeRefill}）开启后分液池水箱在
 * DOWN/侧面/UP 三面都能被 {@code fill} 灌入且同一水箱累加；关闭后三面拒灌、
 * UP 面不再暴露接口（恢复原版）。两处织入点（tick 双向置位 + UP 面 provider）都是
 * 行为级改动，纯函数单测覆盖不到，故在此做运行时验证；翻转用例锁定“开→关”与
 * “关→开”两个转移，防止 closeInsertion 回归空实现假绿。
 *
 * <p>开关翻转用例放独立 batch（{@code pipe_refill_toggle}）顺序执行，避免共享的
 * 全局配置被同批次并发用例互踩；两用例各自开头显式 set 期望值、翻转用例收尾还原。
 * 仅 Create 在场时注册（见 {@code DimBlend#onRegisterGameTests} 的 ModList 守卫），
 * 模板结构见 {@code data/dimblend_experience/structure/item_drain_refill.nbt}
 * （与 {@code copycat_obsidian.nbt} 同构的空平台）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class ItemDrainPipeRefillGameTests {

    private ItemDrainPipeRefillGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 200)
    public static void pipeRefillAcceptsFillOnAllFaces(GameTestHelper helper) {
        // 防 run/config 残留 false（或并行的翻转用例）导致假失败
        Config.ITEM_DRAIN_PIPE_REFILL.set(true);
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
        // 等 2 tick：tick()V 置位注入需先跑一拍（建块当拍行为初始化在 tick 之前完成）
        helper.runAfterDelay(2, () -> {
            BlockPos abs = helper.absolutePos(rel);
            BlockEntity be = helper.getBlockEntity(rel);
            helper.assertTrue(be != null, "item drain block entity missing");

            int downFilled = fill(helper, abs, Direction.DOWN, 500);
            int sideFilled = fill(helper, abs, Direction.NORTH, 250);
            int upFilled = fill(helper, abs, Direction.UP, 250);
            helper.assertTrue(downFilled == 500, "DOWN fill accepted " + downFilled + "/500");
            helper.assertTrue(sideFilled == 250, "NORTH fill accepted " + sideFilled + "/250");
            helper.assertTrue(upFilled == 250, "UP fill accepted " + upFilled + "/250");

            IFluidHandler view = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, Direction.DOWN);
            helper.assertTrue(view != null, "DOWN fluid view missing");
            helper.assertTrue(view.getFluidInTank(0).getAmount() == 1000,
                    "tank should hold 1000mb after three fills, got " + view.getFluidInTank(0).getAmount());
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "pipe_refill_toggle")
    public static void switchOffRestoresVanilla(GameTestHelper helper) {
        // 锁定热切换两个转移：构造即禁（原版）→ 开 → 关。先开后放块之后再关，
        // 确保 closeInsertion 的“开→关”路径真的被走到（若先 set(false) 再放块，
        // BE 构造期本就 forbidInsertion，回归 F1 也会假绿）
        Config.ITEM_DRAIN_PIPE_REFILL.set(true);
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, AllBlocks.ITEM_DRAIN.get().defaultBlockState());

        // 第 1 拍（+2 tick）：确认已开——DOWN 面灌入成功即注水门开放
        helper.runAfterDelay(2, () -> {
            BlockPos abs = helper.absolutePos(rel);
            helper.assertTrue(fill(helper, abs, Direction.DOWN, 100) == 100,
                    "DOWN fill must succeed while enabled (precondition for the off transition)");
            Config.ITEM_DRAIN_PIPE_REFILL.set(false);
        });
        // 第 2 拍（+4 tick）：热关闭经 tick()V 置位注入在下一拍生效，断言恢复原版
        helper.runAfterDelay(4, () -> {
            BlockPos abs = helper.absolutePos(rel);

            helper.assertTrue(fill(helper, abs, Direction.DOWN, 500) == 0, "DOWN fill must be rejected when disabled");
            helper.assertTrue(fill(helper, abs, Direction.NORTH, 250) == 0, "NORTH fill must be rejected when disabled");
            helper.assertTrue(fill(helper, abs, Direction.UP, 250) == 0, "UP fill must be rejected when disabled");
            helper.assertTrue(
                    helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, Direction.UP) == null,
                    "UP face must expose no fluid handler when disabled");

            Config.ITEM_DRAIN_PIPE_REFILL.set(true); // 还原，防影响后续批次/复跑
            helper.succeed();
        });
    }

    private static int fill(GameTestHelper helper, BlockPos abs, Direction face, int mb) {
        IFluidHandler handler = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, abs, face);
        if (handler == null) {
            return 0;
        }
        return handler.fill(new FluidStack(Fluids.WATER, mb), FluidAction.EXECUTE);
    }
}
