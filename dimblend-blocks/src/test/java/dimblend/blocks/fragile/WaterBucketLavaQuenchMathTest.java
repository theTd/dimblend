package dimblend.blocks.fragile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.LavaQuenchOutcome;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 水桶浇岩浆判定表自检：5x5 形状、单格固化分支、创造/生存扣桶口径。
 * 纯 Java + {@link BlockPos}，不需要游戏 bootstrap。
 */
class WaterBucketLavaQuenchMathTest {

    @Test
    void horizontalSquareCoversTwentyFiveCells() {
        List<BlockPos> cells = WaterBucketLavaQuenchMath.horizontalSquare5x5(new BlockPos(10, 64, -7));

        assertEquals(25, cells.size());
        assertEquals(25, new HashSet<>(cells).size(), "5x5 不允许重复格");
    }

    @Test
    void horizontalSquareStaysInBoundsAndSameY() {
        BlockPos center = new BlockPos(-31, 12, 47);
        List<BlockPos> cells = WaterBucketLavaQuenchMath.horizontalSquare5x5(center);

        for (BlockPos cell : cells) {
            assertEquals(center.getY(), cell.getY(), "固化层必须是同一 Y（dy = 0）：" + cell);
            assertTrue(Math.abs(cell.getX() - center.getX()) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS,
                    "dx 越界：" + cell);
            assertTrue(Math.abs(cell.getZ() - center.getZ()) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS,
                    "dz 越界：" + cell);
        }
        assertTrue(cells.contains(center), "中心格必须在集合内");
    }

    @Test
    void horizontalSquareCoversEveryCellOfTheSquare() {
        BlockPos center = new BlockPos(3, 0, 3);
        Set<BlockPos> cells = new HashSet<>(WaterBucketLavaQuenchMath.horizontalSquare5x5(center));

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                assertTrue(cells.contains(center.offset(dx, 0, dz)), "缺格 dx=" + dx + " dz=" + dz);
            }
        }
    }

    @Test
    void horizontalSquareIsImmutable() {
        List<BlockPos> cells = WaterBucketLavaQuenchMath.horizontalSquare5x5(BlockPos.ZERO);

        assertThrows(UnsupportedOperationException.class, () -> cells.add(BlockPos.ZERO));
    }

    @Test
    void sourceLavaBecomesObsidian() {
        assertEquals(LavaQuenchOutcome.OBSIDIAN,
                WaterBucketLavaQuenchMath.classifyCell(true, true, true));
    }

    @Test
    void flowingLavaBecomesFragileStone() {
        assertEquals(LavaQuenchOutcome.FRAGILE_STONE,
                WaterBucketLavaQuenchMath.classifyCell(true, false, true));
    }

    @Test
    void nonLavaFluidIsLeftAlone() {
        assertEquals(LavaQuenchOutcome.NONE,
                WaterBucketLavaQuenchMath.classifyCell(false, true, true));
        assertEquals(LavaQuenchOutcome.NONE,
                WaterBucketLavaQuenchMath.classifyCell(false, false, true));
    }

    @Test
    void lavaFluidInNonLiquidBlockIsLeftAlone() {
        assertEquals(LavaQuenchOutcome.NONE,
                WaterBucketLavaQuenchMath.classifyCell(true, true, false));
        assertEquals(LavaQuenchOutcome.NONE,
                WaterBucketLavaQuenchMath.classifyCell(true, false, false));
    }

    @Test
    void survivalConsumesBucketAndCreativeKeepsIt() {
        assertTrue(WaterBucketLavaQuenchMath.shouldConsumeBucket(false), "生存/冒险应扣水");
        assertFalse(WaterBucketLavaQuenchMath.shouldConsumeBucket(true), "创造（无限材料）应保留水桶");
    }
}
