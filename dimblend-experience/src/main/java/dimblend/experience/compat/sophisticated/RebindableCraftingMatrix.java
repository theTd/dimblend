package dimblend.experience.compat.sophisticated;

import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 合成矩阵可重绑：{@code CraftingItemHandler}（精妙核心，合成/切石升级共用）的
 * {@code supplyInventory} 同样在构造时绑定具体 wrapper，wrapper 被替换后矩阵仍读写旧库存。
 *
 * <p>由 {@code CraftingItemHandlerMixin} 实现。</p>
 */
public interface RebindableCraftingMatrix {

    /** 换成新的库存供应商，并清掉已缓存的 {@code items} 快照。 */
    void dimblend$setInventorySupplier(Supplier<IItemHandlerModifiable> supplier);
}
