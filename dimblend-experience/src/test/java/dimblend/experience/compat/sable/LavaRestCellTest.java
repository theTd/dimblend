package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 柱顶保留岩浆的落点判定（见 {@link LavaRestCell}）：柱顶格是车顶方块自身那一格时，
 * structure_void 装不住流体，熔岩只能停到柱顶 +1；柱顶本就高出车顶方块一格时留在柱顶；
 * 下方挡不住流体时不保留（安全方向）。
 *
 * <p>同一个任务里还有一个纯记账类 {@link LavaFlowGuard}（mixin 闸门本身只能实机验证）：
 * 本任务只拥有这一个测试文件，登记/替换/释放的记账断言也放在这里。</p>
 */
class LavaRestCellTest {

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"));
    private static final ResourceKey<Level> OTHER_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("dimblend", "other"));

    private static long cell(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** 柱顶格 = 车顶方块自身格（亚格沉降把保守覆盖顶格翻到方块格）：落点必须抬到柱顶 +1。 */
    @Test
    void roofCellTopRestsOneCellAbove() {
        long top = cell(8, 64, -3);
        assertEquals(OptionalLong.of(cell(8, 65, -3)), LavaRestCell.restCell(top, true, true));
    }

    /** 柱顶已高出车顶方块一格（保守覆盖多收的那一格）：熔岩就停在柱顶格，不再多抬。 */
    @Test
    void topAlreadyAboveRoofKeepsTopCell() {
        long top = cell(8, 65, -3);
        assertEquals(OptionalLong.of(top), LavaRestCell.restCell(top, false, true));
    }

    /** 下方没有方块挡流体：保留会让熔岩从船壳漏下去，一律不保留。 */
    @Test
    void noSolidBelowDoesNotRetain() {
        long top = cell(8, 64, -3);
        assertTrue(LavaRestCell.restCell(top, false, false).isEmpty());
        assertTrue(LavaRestCell.restCell(top, true, false).isEmpty());
    }

    /** 抬升只改 y：x/z 与柱顶格一致，负坐标不发生符号错位。 */
    @Test
    void restAboveRoofMovesOnlyYAndKeepsNegativeCoordinates() {
        long top = cell(-5, -60, -7);
        assertEquals(OptionalLong.of(cell(-5, -59, -7)), LavaRestCell.restCell(top, true, true));
    }

    /** 载具方块自身中心格：投影中心逐轴 floor（与 {@code LavaContactScan.blockCellY} 同口径）。 */
    @Test
    void ownBlockCellFloorsProjectedBlockCenter() {
        assertEquals(cell(8, -60, 9), VoidFitVoxelizer.ownBlockCell(8.5D, -59.5D, 9.7D));
    }

    /** 负坐标逐轴 floor：-0.5 落在 -1 格，而不是截断成 0。 */
    @Test
    void ownBlockCellFloorsNegativeCoordinates() {
        assertEquals(cell(-1, -1, -4), VoidFitVoxelizer.ownBlockCell(-0.5D, -0.2D, -3.1D));
    }

    // ------------------------------------------------------------------
    // LavaFlowGuard 记账（闸门本体在 mixin 里，只能实机验证）
    // ------------------------------------------------------------------

    /** 登记即生效；下一轮整轮重算时，旧集合里不再保留的格必须释放。 */
    @Test
    void refreshReleasesCellsTheVehicleNoLongerRetains() {
        UUID owner = new UUID(0, 11);
        long kept = cell(2, 70, 2);
        long stale = cell(3, 70, 2);
        LongOpenHashSet first = new LongOpenHashSet();
        first.add(kept);
        first.add(stale);
        LavaFlowGuard.refresh(DIMENSION, owner, first);
        assertTrue(LavaFlowGuard.contains(DIMENSION, kept));
        assertTrue(LavaFlowGuard.contains(DIMENSION, stale));
        LongOpenHashSet second = new LongOpenHashSet();
        second.add(kept);
        LavaFlowGuard.refresh(DIMENSION, owner, second);
        assertTrue(LavaFlowGuard.contains(DIMENSION, kept));
        assertFalse(LavaFlowGuard.contains(DIMENSION, stale), "旧登记必须随整轮替换释放");
        LavaFlowGuard.releaseOwner(owner);
        assertFalse(LavaFlowGuard.contains(DIMENSION, kept));
    }

    /** 重叠载具共同保留同一格：最后一个 owner 释放前闸门不解除。 */
    @Test
    void overlappingOwnersKeepTheGateUntilTheLastRelease() {
        UUID first = new UUID(0, 21);
        UUID second = new UUID(0, 22);
        long shared = cell(4, 70, 4);
        LavaFlowGuard.retain(DIMENSION, first, shared);
        LavaFlowGuard.retain(DIMENSION, second, shared);
        LavaFlowGuard.releaseOwner(first);
        assertTrue(LavaFlowGuard.contains(DIMENSION, shared), "还有一位 owner 时闸门必须保持");
        LavaFlowGuard.releaseOwner(second);
        assertFalse(LavaFlowGuard.contains(DIMENSION, shared));
    }

    /** 空位被拆（载具离开）立即解冻该格：不等下一轮 refit 重算。 */
    @Test
    void releaseCellUnfreezesEveryOwnerOfThatCell() {
        UUID first = new UUID(0, 31);
        UUID second = new UUID(0, 32);
        long lava = cell(5, 71, 5);
        long other = cell(6, 71, 5);
        LavaFlowGuard.retain(DIMENSION, first, lava);
        LavaFlowGuard.retain(DIMENSION, second, lava);
        LavaFlowGuard.retain(DIMENSION, second, other);
        LavaFlowGuard.releaseCell(DIMENSION, lava);
        assertFalse(LavaFlowGuard.contains(DIMENSION, lava));
        assertTrue(LavaFlowGuard.contains(DIMENSION, other), "只释放这一格，同 owner 的其它格不动");
        LavaFlowGuard.releaseOwner(first);
        LavaFlowGuard.releaseOwner(second);
    }

    /** 维度隔离：同一坐标在另一维度不被闸门拦住。 */
    @Test
    void gateIsPerDimension() {
        UUID owner = new UUID(0, 41);
        long lava = cell(7, 72, 7);
        LavaFlowGuard.retain(DIMENSION, owner, lava);
        assertTrue(LavaFlowGuard.contains(DIMENSION, lava));
        assertFalse(LavaFlowGuard.contains(OTHER_DIMENSION, lava));
        LavaFlowGuard.releaseOwner(owner);
    }
}
