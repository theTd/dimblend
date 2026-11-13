package dimblend.blocks.fragile;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 水桶浇岩浆的纯判定表：只依赖 {@link BlockPos}，不触达 {@code Blocks}/{@code Items}
 * 静态初始化，故可在无游戏引导（bootstrap）的 JVM 里直接单测。
 *
 * <p>口径（2026-11-13 拍板）：命中点与其同一 Y 层水平 5x5 范围内——源岩浆固化黑曜石，
 * 流动岩浆固化易碎石头，其余格原样；生存/冒险消耗桶内水，创造保留水桶。</p>
 */
public final class WaterBucketLavaQuenchMath {

    /** 5x5 半径：dx/dz ∈ [-2, 2]，dy = 0。 */
    public static final int QUENCH_RADIUS = 2;

    /** 单格固化结果。 */
    public enum LavaQuenchOutcome {
        /** 不动（非岩浆、非液体方块）。 */
        NONE,
        /** 源岩浆 → 黑曜石。 */
        OBSIDIAN,
        /** 流动岩浆 → 易碎石头。 */
        FRAGILE_STONE
    }

    /**
     * 判定单格固化结果。三者分别对应 {@code FluidState#is(FluidTags.LAVA)}、
     * {@code FluidState#isSource()} 与 {@code BlockState#liquid()}——非液体方块即使挂着
     * 岩浆流体（例如被别的 mod 塞进非液体方块）也不动手，避免把方块本体覆盖掉。
     */
    public static LavaQuenchOutcome classifyCell(boolean isLavaFluid, boolean isSourceFluid, boolean isLiquidBlock) {
        if (!isLavaFluid || !isLiquidBlock) {
            return LavaQuenchOutcome.NONE;
        }
        return isSourceFluid ? LavaQuenchOutcome.OBSIDIAN : LavaQuenchOutcome.FRAGILE_STONE;
    }

    /** 命中点同一 Y 层的水平 5x5 全部格（25 个，含中心格），顺序为 dx 外层、dz 内层。 */
    public static List<BlockPos> horizontalSquare5x5(BlockPos center) {
        List<BlockPos> cells = new ArrayList<>((QUENCH_RADIUS * 2 + 1) * (QUENCH_RADIUS * 2 + 1));
        for (int dx = -QUENCH_RADIUS; dx <= QUENCH_RADIUS; dx++) {
            for (int dz = -QUENCH_RADIUS; dz <= QUENCH_RADIUS; dz++) {
                cells.add(center.offset(dx, 0, dz));
            }
        }
        return List.copyOf(cells);
    }

    /** 生存/冒险消耗桶内水；创造（{@code hasInfiniteMaterials()}）保留水桶。 */
    public static boolean shouldConsumeBucket(boolean hasInfiniteMaterials) {
        return !hasInfiniteMaterials;
    }

    private WaterBucketLavaQuenchMath() {
    }
}
