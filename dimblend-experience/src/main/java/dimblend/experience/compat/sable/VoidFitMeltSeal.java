package dimblend.experience.compat.sable;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

/**
 * 熔穿孔封口塞判定（纯计算，无可变状态；2026-10-31 用户实机缺陷）。
 *
 * <p>缺陷：体素化只收非空气方块 + 每轴 +1 保守格，车厢内部净空（&gt;1 格宽/深的核心
 * 体积）从不在 target 内。车顶被浇熔穿后，那一格原本只由车顶块自身覆盖；块一熔，下一轮
 * 不在 target，{@code VoidFitApplier} 的拆除路径把空位拆回空气——舱内没有封口格，整根
 * 岩浆柱直接切进车厢、从上到下同时融毁。对照实心堆叠：熔穿格仍被下方方块的 +1 覆盖留在
 * target，所以只下降一层。</p>
 *
 * <p>修法边界（三条都成立才封口）：</p>
 * <ol>
 * <li><b>正上方是岩浆</b>（允许跨过载具沉降造成的少量空气格，见
 *     {@code VoidFitApplier#lavaAbovePlug}）——只有岩浆压在孔口上时，拆掉空位才会让
 *     岩浆灌进去；上方没有岩浆时正常收缩，空位该拆就拆。</li>
 * <li><b>孔口比一格深</b>：孔口正下方同柱下一格也不在本轮 target 内。否则那是"下方还有
 *     目标格顶着"的正常逐层熔穿（实心堆叠），熔岩必须照旧下降一层继续熔下一层——
 *     在这里封口会把 {@code lavaDescendsExactlyOneLayerAfterRoofMelts} 锁死。</li>
 * <li><b>孔口下方仍有本车目标格</b>（该 (x,z) 柱在 target 里有任何一格）：孔是车壳里的
 *     腔体，不是整柱搬走的残影。载具驶离后该柱不再有投影，封口塞必须随之拆除，否则空位
 *     会以"岩浆仍压在上面"为由永久留在原地、把 target 集越积越大。</li>
 * </ol>
 *
 * <p>封口效果：岩浆停在孔口上，进不了舱；只熔与岩浆直接接触的那层。塞子本身仍在
 * materialized 账上、不写世界、不占写入预算；每轮 refit 重新判定，岩浆流走或壳层熔掉
 * 后自动拆除。</p>
 */
public final class VoidFitMeltSeal {

    /**
     * 该离开投影的已物化空位是否必须保成封口塞。
     *
     * @param lavaAbovePlug     该格正上方（可跨过沉降造成的少量空气格）是否是岩浆；
     *                          调用方 {@code VoidFitApplier#lavaAbovePlug} 负责
     *                          {@code hasChunkAt} 守卫，未加载按 {@code false}
     * @param columnTops        本轮新 target 的柱顶索引（用于判断孔口下方是否还有壳层）
     * @param newTarget         本轮新 target 全集（尚未并入本格）
     * @param cell              上一轮已 materialized、本轮不在 target 内的格
     */
    public static boolean shouldSeal(boolean lavaAbovePlug, Long2IntMap columnTops,
            LongSet newTarget, long cell) {
        return lavaAbovePlug
                && isHoleMouth(newTarget, cell)
                && hasShellBelow(columnTops, cell);
    }

    /** 孔口比一格深：正下方同柱下一格不在本轮 target 内（不是"下方还有目标格顶着"）。 */
    public static boolean isHoleMouth(LongSet newTarget, long cell) {
        return !newTarget.contains(VoidFitColumnTops.cellBelow(cell));
    }

    /** 该 (x,z) 柱在本轮 target 里仍有格（载具的壳层/腔底还在）。 */
    public static boolean hasShellBelow(Long2IntMap columnTops, long cell) {
        return columnTops.get(VoidFitColumnTops.columnKey(BlockPos.getX(cell), BlockPos.getZ(cell)))
                != Integer.MIN_VALUE;
    }

    private VoidFitMeltSeal() {
    }
}
