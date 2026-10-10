package dimblend.experience.treasure;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A11 藏宝箱贝壳 X 标记：埋藏的宝藏生成时（注入点见
 * {@code BuriedTreasureShellMarkerMixin}，createChest 之后、boundingBox 已重设为宝箱坐标），
 * 从宝箱列向上找到地表，把中心列与四个对角列的表层沙块替换为
 * {@code dimblend_blocks:shell_marker}，摆成与地面齐平的 X。
 *
 * <p>跨模块零编译依赖：贝壳方块按注册名运行时查找，dimblend-blocks 缺席时静默跳过
 * （单独安装本模组不崩的横切约定）。</p>
 */
public final class TreasureShellMarker {

    private static final ResourceLocation SHELL_MARKER_ID =
            ResourceLocation.fromNamespaceAndPath("dimblend_blocks", "shell_marker");

    /** 宝箱埋深无硬上限（= 沙层厚度），向上扫描的防呆上限。 */
    private static final int MAX_CLIMB = 32;

    /** 臂落点搜索范围：相对中心地表 Y 上浮/下探的格数（容忍小高差与沙丘起伏）。 */
    private static final int ARM_UP = 2;
    private static final int ARM_DOWN = 6;

    /** X 的五格：中心 + 四对角。 */
    private static final int[][] X_ARMS = {{0, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * 在宝箱上方的沙滩表面摆 X。生成期（WorldGenLevel）与 GameTest（ServerLevel）
     * 都可调用——两者均实现 {@link LevelAccessor}。
     */
    public static void mark(LevelAccessor level, BlockPos chestPos) {
        if (!Config.isLoaded() || !Config.TREASURE_SHELL_X.get()) {
            return;
        }
        Block shell = BuiltInRegistries.BLOCK.getOptional(SHELL_MARKER_ID).orElse(null);
        if (shell == null) {
            return;
        }

        // 从宝箱向上找地表：头顶仍是实心方块（沙/沙石等）就继续上移；
        // 雪层/草/水等更晚或更浅的覆盖物不算地表（isSolidRender 为 false 即停）
        BlockPos.MutableBlockPos cursor = chestPos.mutable();
        int climbed = 0;
        while (climbed < MAX_CLIMB && level.getBlockState(cursor.above()).isSolidRender(level, cursor.above())) {
            cursor.move(Direction.UP);
            climbed++;
        }
        // 宝箱裸露（头顶即非实心，如洞穴穿入）时没有"埋"可言，整标放弃，也绝不替换宝箱自身
        if (climbed == 0) {
            return;
        }

        int surfaceY = cursor.getY();
        for (int[] arm : X_ARMS) {
            placeArm(level, chestPos.getX() + arm[0], chestPos.getZ() + arm[1], surfaceY, shell);
        }
    }

    /** 单臂落点：从中心地表 Y+ARM_UP 向下扫，第一格实心且为沙则替换；找不到合格落点则该臂跳过。 */
    private static void placeArm(LevelAccessor level, int x, int z, int surfaceY, Block shell) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surfaceY + ARM_UP, z);
        while (pos.getY() >= surfaceY - ARM_DOWN) {
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                if (state.is(BlockTags.SAND)) {
                    level.setBlock(pos, shell.defaultBlockState(), 2);
                }
                return;
            }
            pos.move(Direction.DOWN);
        }
    }

    private TreasureShellMarker() {
    }
}
