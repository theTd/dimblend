package dimblend.experience.gametest;

import dimblend.experience.Config;
import dimblend.experience.treasure.TreasureShellMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A11 藏宝箱贝壳 X 标记的摆放逻辑检查（直接调 {@link TreasureShellMarker#mark}，
 * mixin 注入点本身的接线靠编译期 target 校验）。模板 treasure_shell_x 为 3x10x3 全空气，
 * X 横向恰好 3x3。全部用例体同步执行，配置改动 finally 还原。
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class TreasureShellMarkerGameTests {

    private static final String TEMPLATE = "treasure_shell_x";
    private static final String NAMESPACE = "dimblend_experience";
    private static final String BATCH = "treasure_shell_x";

    private static final BlockPos CHEST_REL = new BlockPos(1, 1, 1);
    private static final int SAND_TOP_Y = 5;
    private static final int[][] X_ARMS_REL = {{1, 1}, {0, 0}, {0, 2}, {2, 0}, {2, 2}};

    /** 正例：宝箱埋 4 格沙下，中心 + 四对角的表层沙全被替换成贝壳标记。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void buriedChestGetsShellX(GameTestHelper helper) {
        boolean saved = Config.TREASURE_SHELL_X.get();
        try {
            Config.TREASURE_SHELL_X.set(true);
            Block shell = shellBlock();
            helper.setBlock(CHEST_REL, Blocks.CHEST);
            fillSandColumn(helper);

            TreasureShellMarker.mark(helper.getLevel(), helper.absolutePos(CHEST_REL));

            for (int[] arm : X_ARMS_REL) {
                BlockPos expect = new BlockPos(arm[0], SAND_TOP_Y, arm[1]);
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(expect)).is(shell),
                        "shell marker missing at " + expect);
            }
            helper.succeed();
        } finally {
            Config.TREASURE_SHELL_X.set(saved);
        }
    }

    /** 反例一：宝箱头顶即空气（裸露）时不摆任何标记，也绝不替换宝箱自身。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void exposedChestGetsNoMark(GameTestHelper helper) {
        boolean saved = Config.TREASURE_SHELL_X.get();
        try {
            Config.TREASURE_SHELL_X.set(true);
            Block shell = shellBlock();
            helper.setBlock(CHEST_REL, Blocks.CHEST);

            TreasureShellMarker.mark(helper.getLevel(), helper.absolutePos(CHEST_REL));

            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(CHEST_REL)).is(Blocks.CHEST),
                    "exposed chest must not be replaced");
            assertNoShellAnywhere(helper, shell);
            helper.succeed();
        } finally {
            Config.TREASURE_SHELL_X.set(saved);
        }
    }

    /** 反例二：开关关闭时即使满足全部地形条件也不摆标记。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void disabledConfigGetsNoMark(GameTestHelper helper) {
        boolean saved = Config.TREASURE_SHELL_X.get();
        try {
            Config.TREASURE_SHELL_X.set(false);
            Block shell = shellBlock();
            helper.setBlock(CHEST_REL, Blocks.CHEST);
            fillSandColumn(helper);

            TreasureShellMarker.mark(helper.getLevel(), helper.absolutePos(CHEST_REL));

            assertNoShellAnywhere(helper, shell);
            helper.succeed();
        } finally {
            Config.TREASURE_SHELL_X.set(saved);
        }
    }

    /** 残臂：某臂落点不是沙（砂砾）时该臂跳过、原方块不动，其余臂照常替换。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void nonSandArmIsSkipped(GameTestHelper helper) {
        boolean saved = Config.TREASURE_SHELL_X.get();
        try {
            Config.TREASURE_SHELL_X.set(true);
            Block shell = shellBlock();
            helper.setBlock(CHEST_REL, Blocks.CHEST);
            fillSandColumn(helper);
            BlockPos gravelArm = new BlockPos(0, SAND_TOP_Y, 0);
            helper.setBlock(gravelArm, Blocks.GRAVEL);

            TreasureShellMarker.mark(helper.getLevel(), helper.absolutePos(CHEST_REL));

            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(gravelArm)).is(Blocks.GRAVEL),
                    "gravel arm must be left untouched");
            for (int[] arm : X_ARMS_REL) {
                BlockPos expect = new BlockPos(arm[0], SAND_TOP_Y, arm[1]);
                if (expect.equals(gravelArm)) {
                    continue;
                }
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(expect)).is(shell),
                        "shell marker missing at " + expect);
            }
            helper.succeed();
        } finally {
            Config.TREASURE_SHELL_X.set(saved);
        }
    }

    /** 宝箱上方整 3x3 截面铺沙到 SAND_TOP_Y（含宝箱正上方 4 格）。 */
    private static void fillSandColumn(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 2; y <= SAND_TOP_Y; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.SAND);
                }
            }
        }
    }

    private static void assertNoShellAnywhere(GameTestHelper helper, Block shell) {
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 0; y <= 9; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    helper.assertFalse(helper.getLevel().getBlockState(helper.absolutePos(pos)).is(shell),
                            "unexpected shell marker at " + pos);
                }
            }
        }
    }

    private static Block shellBlock() {
        return BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.fromNamespaceAndPath("dimblend_blocks", "shell_marker"))
                .orElseThrow(() -> new IllegalStateException(
                        "dimblend_blocks:shell_marker not registered (blocks mod must be in the GameTest runtime)"));
    }

    private TreasureShellMarkerGameTests() {
    }
}
