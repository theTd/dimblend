package dimblend.experience.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 车顶保留岩浆的止流闸门登记表（服务端主线程；2026-11-03 用户实机缺陷）。
 *
 * <p>缺陷：拟合"保留岩浆"支把熔岩留在车顶柱顶格后，原版 {@code FlowingFluid#spread} 先试
 * DOWN、失败就走 {@code spreadToSides}——熔岩脚下是 {@code structure_void}（
 * {@code canHoldFluid} 恒 false），于是横向必然铺开；rotating 的下界/虚空带还被本仓库
 * 改成下界速度（每 10 tick 推 8 格），观感上就是"岩浆向四周流"。修法：把"本轮决定保留
 * 的岩浆静止格"登记在这里，{@code FlowingFluidFlowGuardMixin} 只在 {@code spread} 的
 * 水平分支上查表拦截——向下落保持原版语义。</p>
 *
 * <p>分账（按维度 + 载具）：</p>
 * <ul>
 * <li>登记按载具分账，是因为每轮 refit 重算整张集合（{@link #refresh} 用新集合替换旧集合），
 *     载具开走/投影收缩时旧登记随之释放；</li>
 * <li>{@link #releaseCell} 是"空位被拆"这条即时释放路径：拦的岩浆就压在被拆掉的那格空位
 *     上方，空位一拆闸门立即解除，熔岩恢复原版流动；</li>
 * <li>同一格可能被多载具共同保留（重叠载具），故内部按引用计数合并，最后一个 owner 释放
 *     才真正解冻。</li>
 * </ul>
 *
 * <p>线程：只在服务端主线程读写（refit / 空位拆除 / 方块 tick 都在主线程）。
 * 静态表不跨存档持久化：服务器重启后由下一次 refit/补放重新登记。</p>
 */
public final class LavaFlowGuard {

    /** 每维度的保留格引用计数：命中即"该格岩浆不许水平扩散"。 */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> REFS = new HashMap<>();

    /** 每个载具本轮保留的格（用于整轮替换与载具消失时整体释放）。 */
    private static final Map<UUID, Held> HELD = new HashMap<>();

    private static final class Held {
        private final ResourceKey<Level> dimension;
        private final LongOpenHashSet cells = new LongOpenHashSet();

        private Held(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }

    /**
     * 用本轮重算的保留格集合替换某载具的旧集合：旧集合里不在新集合中的格立即释放。
     *
     * <p>只有"整轮都评估完"的 refit 才调用（{@code VoidFitApplier#refit} 在柱顶写入轮前
     * 算出完整几何集合）；预算截断、轮次被跳过的情形保持旧登记不动。</p>
     */
    public static void refresh(ResourceKey<Level> dimension, UUID owner, LongCollection cells) {
        Held held = HELD.get(owner);
        if (held != null && !held.dimension.equals(dimension)) {
            releaseOwner(owner);
            held = null;
        }
        if (held != null) {
            LongIterator it = held.cells.iterator();
            while (it.hasNext()) {
                long cell = it.nextLong();
                if (!cells.contains(cell)) {
                    it.remove();
                    decrement(dimension, cell);
                }
            }
            if (held.cells.isEmpty()) {
                HELD.remove(owner);
            }
        }
        for (long cell : cells) {
            retain(dimension, owner, cell);
        }
    }

    /** 登记一个保留格（同一载具重复登记幂等）。 */
    public static void retain(ResourceKey<Level> dimension, UUID owner, long cell) {
        Held held = HELD.get(owner);
        if (held == null) {
            held = new Held(dimension);
            HELD.put(owner, held);
        }
        if (!held.cells.add(cell)) {
            return;
        }
        REFS.computeIfAbsent(dimension, key -> new Long2IntOpenHashMap()).addTo(cell, 1);
    }

    /** 载具消失：撤销该载具的全部登记（对应 {@code VoidFitApplier#removeAll} 收尾）。 */
    public static void releaseOwner(UUID owner) {
        Held held = HELD.remove(owner);
        if (held == null) {
            return;
        }
        for (long cell : held.cells) {
            decrement(held.dimension, cell);
        }
    }

    /**
     * 空位被拆除：该格上方不再有"被保留的岩浆"，立即释放该格的闸门。
     * 由 {@code VoidFitApplier#removeVoid} 在拆除已物化空位后调用（载具离开/投影收缩），
     * 不等下一轮 refit 重算——载具静止时 refit 最长可隔一个自愈周期（100 tick）。
     *
     * @param cell 被保留岩浆所在的格（即被拆空位的正上方一格）
     */
    public static void releaseCell(ResourceKey<Level> dimension, long cell) {
        for (Held held : HELD.values()) {
            if (held.dimension.equals(dimension) && held.cells.remove(cell)) {
                decrement(dimension, cell);
            }
        }
    }

    /** 该格当前是否被某个"保留岩浆"的载具占着（mixin 闸门唯一查询入口）。 */
    public static boolean contains(ResourceKey<Level> dimension, long cell) {
        Long2IntOpenHashMap refs = REFS.get(dimension);
        return refs != null && refs.get(cell) > 0;
    }

    private static void decrement(ResourceKey<Level> dimension, long cell) {
        Long2IntOpenHashMap refs = REFS.get(dimension);
        if (refs == null) {
            return;
        }
        if (refs.addTo(cell, -1) <= 0) {
            refs.remove(cell);
            if (refs.isEmpty()) {
                REFS.remove(dimension);
            }
        }
    }

    private LavaFlowGuard() {
    }
}
