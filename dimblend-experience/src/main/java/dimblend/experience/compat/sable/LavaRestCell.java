package dimblend.experience.compat.sable;

import java.util.OptionalLong;

import net.minecraft.core.BlockPos;

/**
 * 柱顶保留岩浆的落点判定（纯计算，无可变状态；2026-11-03 用户实机缺陷）。
 *
 * <p>缺陷：{@code VoidFitApplier} 的"保留岩浆"分支把熔岩留在保守覆盖的柱顶格。保守覆盖按
 * {@code floor(中心 ± 半 extent)} 取格（{@link VoidFitVoxelizer#addCoveredCells}），scale=1
 * 时一格方块覆盖 {@code R .. R+1} 或 {@code R-1 .. R}——取决于载具亚格沉降方向（{@code floor}
 * 在格边界上翻面，{@code SableLavaMeltGameTests} 的柱顶断言注释也承认这条）。翻到 {@code R}
 * 时柱顶格就是车顶方块自身那一格，熔岩被留在那里 = 视觉上比车顶顶面低一格（用户实报
 * "落入车顶下一格"）。</p>
 *
 * <p>正确落点：{@code structure_void} 装不住流体（原版 {@code FlowingFluid#canHoldFluid}
 * 显式排除 {@code Blocks.STRUCTURE_VOID}），被驱逐成空位的柱顶格上方才是熔岩的静止格。</p>
 * <ul>
 * <li><b>柱顶格就是车顶方块自身那一格</b> → 落柱顶 +1（本格驱逐成空位）；</li>
 * <li><b>柱顶格已经高出车顶方块一格</b>（保守覆盖多收的那一格）→ 落柱顶格（旧行为，视觉上
 *     本来就贴着车顶表面）；</li>
 * <li><b>下方挡不住流体</b> → 不保留（安全方向：保留会让熔岩从船壳漏下去）。</li>
 * </ul>
 *
 * <p>"柱顶格是否是车顶方块自身那一格"由调用方给出：{@link VoidFitVoxelizer} 与体素化同一趟
 * 枚举里收集载具方块自身中心格（{@link VoidFitVoxelizer#ownBlockCell}），命中即"自身格"。
 * 本类只做判定、不读世界、不写世界，落点是否真能写（限高、被真实方块占据、被其它载具
 * target）由 {@code VoidFitApplier} 复核。</p>
 */
public final class LavaRestCell {

    /**
     * @param columnTop     保守覆盖的柱顶格（{@code VoidFitColumnTops} 的柱顶）
     * @param topIsRoofCell 柱顶格是否是载具方块自身中心格（见 {@link VoidFitVoxelizer#ownBlockCell}）
     * @param solidBelow    柱顶正下方是否挡流体（structure_void 或 blocksMotion 方块，
     *                      见 {@code VoidFitApplier#blocksLavaBelow}）
     * @return 熔岩静止格；{@link OptionalLong#empty()} 表示不保留（照常驱逐成结构空位）
     */
    public static OptionalLong restCell(long columnTop, boolean topIsRoofCell, boolean solidBelow) {
        if (!solidBelow) {
            return OptionalLong.empty();
        }
        if (!topIsRoofCell) {
            return OptionalLong.of(columnTop);
        }
        // 本格就是车顶方块自身那一格：本格必须驱逐成空位，熔岩只能停在它上面一格
        return OptionalLong.of(BlockPos.asLong(
                BlockPos.getX(columnTop), BlockPos.getY(columnTop) + 1, BlockPos.getZ(columnTop)));
    }

    private LavaRestCell() {
    }
}
