package dimblend.experience.compat.create.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 显示连接器「读取存量-计位 / 读取存量-计件」口径（纯函数，见 {@link VaultStorageMath}）。 */
class VaultStorageMathTest {

    @Test
    void pieceWeightOfAFullStackIsAlwaysSixtyFour() {
        // 一整堆（不论 64/16/1）都折算 64 件
        assertEquals(64L, VaultStorageMath.pieces(64, 64));
        assertEquals(64L, VaultStorageMath.pieces(16, 16));
        assertEquals(64L, VaultStorageMath.pieces(1, 1));
    }

    @Test
    void pieceWeightScalesWithMaxStackSize() {
        // 64 堆叠：每个 1 件；16 堆叠：每个 4 件；不可堆叠：每个 64 件
        assertEquals(1L, VaultStorageMath.pieces(1, 64));
        assertEquals(5L, VaultStorageMath.pieces(5, 64));
        assertEquals(4L, VaultStorageMath.pieces(1, 16));
        assertEquals(12L, VaultStorageMath.pieces(3, 16));
        assertEquals(64L, VaultStorageMath.pieces(1, 1));
        assertEquals(128L, VaultStorageMath.pieces(2, 1));
    }

    @Test
    void pieceWeightSurvivesOddInputs() {
        assertEquals(0L, VaultStorageMath.pieces(0, 64));
        assertEquals(0L, VaultStorageMath.pieces(-3, 64));
        // maxStackSize <= 0 按 1 处理，不除零
        assertEquals(7L * 64L, VaultStorageMath.pieces(7, 0));
        assertEquals(7L * 64L, VaultStorageMath.pieces(7, -8));
        // 非 64 整除的怪异堆叠量向下取整，不产生 0 以外的意外
        assertEquals(106L, VaultStorageMath.pieces(5, 3));
    }

    @Test
    void tallySeparatesGroupAndPieceReadings() {
        VaultStorageMath.Tally tally = new VaultStorageMath.Tally();
        tally.addSlot(5, 64);   // 64 堆叠，只放 5 个 → 1 格 / 5 件
        tally.addSlot(0, 1);    // 空格
        tally.addSlot(1, 1);    // 不可堆叠 1 个 → 1 格 / 64 件
        tally.addSlot(16, 16);  // 16 堆叠一整堆 → 1 格 / 64 件

        assertEquals(4, tally.totalSlots());
        assertEquals(3, tally.usedGroups(), "计位数量：一件也算一格");
        assertEquals(5L + 64L + 64L, tally.usedPieces());
        assertEquals(4L * VaultStorageMath.GROUP_PIECES, tally.totalPieces());
    }

    @Test
    void emptyTallyReportsZeroQuantityButFullCapacity() {
        VaultStorageMath.Tally tally = new VaultStorageMath.Tally();
        tally.addSlot(0, 1);
        tally.addSlot(0, 1);

        assertEquals(2, tally.totalSlots());
        assertEquals(0, tally.usedGroups());
        assertEquals(0L, tally.usedPieces());
        assertEquals(128L, tally.totalPieces());
    }

    @Test
    void progressIsClampedToUnitRange() {
        assertEquals(0.0F, VaultStorageMath.progress(0, 100));
        assertEquals(0.5F, VaultStorageMath.progress(50, 100));
        assertEquals(1.0F, VaultStorageMath.progress(100, 100));
        // 计件是加权件数：一格塞满不可堆叠物会超过"总量"，显示侧必须压到 100%
        assertEquals(1.0F, VaultStorageMath.progress(4096, 1280));
        // 没有容量/负值一律 0，不产生 NaN
        assertEquals(0.0F, VaultStorageMath.progress(5, 0));
        assertEquals(0.0F, VaultStorageMath.progress(5, -1));
        assertEquals(0.0F, VaultStorageMath.progress(-5, 100));
    }
}
