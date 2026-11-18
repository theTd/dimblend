package dimblend.experience.compat.sophisticated;

import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 槽位供应商可重绑：{@code SlotSuppliedHandler}（精妙核心）在构造时把
 * {@code Supplier<IItemHandler>} 存成 final 字段，之后 {@link net.minecraft.world.inventory.Slot}
 * 的读写都经 {@code getItemHandler()} → 该字段。升级模块槽同步会替换升级 wrapper 实例
 * （同一物品、新 {@code ItemStack}），构造期的供应商仍指向旧 wrapper，于是合成/锻造/切石页
 * 读到分离的库存。
 *
 * <p>本接口由 {@code dimblend.experience.mixin.compat.sophisticated.SlotSuppliedHandlerMixin}
 * 实现（mixin 包内不允许放 helper，故放在普通包）。重绑后 {@code SlotItemHandler} 的所有路径
 * 自动跟到新 wrapper，无需重建槽位对象。</p>
 */
public interface RebindableSlotSupplier {

    /** 换成新的库存供应商；调用方保证非 {@code null}。 */
    void dimblend$setItemHandlerSupplier(Supplier<IItemHandler> supplier);
}
