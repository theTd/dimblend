package dimblend.carwash.server;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水桶 5x5x5 清洗范围自检：125 格、无重复、每轴偏移落在 [-2, 2] 且含中心。
 * 纯 Java + {@link BlockPos}（不触达物品/方块注册表），不需要游戏 bootstrap。
 */
class ChassisWashingAreaTest {

    @Test
    void areaCoversOneHundredTwentyFiveUniqueCells() {
        List<BlockPos> cells = ChassisWashing.cube5x5x5(new BlockPos(10, 64, -7));

        assertEquals(125, cells.size(), "5x5x5 应为 125 格");
        assertEquals(125, new HashSet<>(cells).size(), "5x5x5 不允许重复格");
    }

    @Test
    void areaStaysWithinTwoBlocksOnEveryAxis() {
        BlockPos center = new BlockPos(-31, 12, 47);
        List<BlockPos> cells = ChassisWashing.cube5x5x5(center);

        for (BlockPos cell : cells) {
            assertTrue(Math.abs(cell.getX() - center.getX()) <= ChassisWashing.AREA_RADIUS,
                    "dx 越界：" + cell);
            assertTrue(Math.abs(cell.getY() - center.getY()) <= ChassisWashing.AREA_RADIUS,
                    "dy 越界（必须是立体的 5x5x5，不是水平 5x5）：" + cell);
            assertTrue(Math.abs(cell.getZ() - center.getZ()) <= ChassisWashing.AREA_RADIUS,
                    "dz 越界：" + cell);
        }
        assertTrue(cells.contains(center), "中心格必须在集合内");
    }

    @Test
    void areaCoversEveryOffsetOfTheCube() {
        BlockPos center = new BlockPos(3, 0, 3);
        Set<BlockPos> cells = new HashSet<>(ChassisWashing.cube5x5x5(center));

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    assertTrue(cells.contains(center.offset(dx, dy, dz)),
                            "缺格 dx=" + dx + " dy=" + dy + " dz=" + dz);
                }
            }
            assertEquals(25, countWithXOffset(cells, center, dx), "dx=" + dx + " 的切片格数不对");
        }
    }

    @Test
    void areaListIsImmutable() {
        List<BlockPos> cells = ChassisWashing.cube5x5x5(BlockPos.ZERO);

        assertThrows(UnsupportedOperationException.class, () -> cells.add(BlockPos.ZERO));
    }

    private static int countWithXOffset(Set<BlockPos> cells, BlockPos center, int dx) {
        int count = 0;
        for (BlockPos cell : cells) {
            if (cell.getX() - center.getX() == dx) {
                count++;
            }
        }
        return count;
    }
}
