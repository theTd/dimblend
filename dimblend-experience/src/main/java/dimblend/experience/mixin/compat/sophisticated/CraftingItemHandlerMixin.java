package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.RebindableCraftingMatrix;
import java.util.Objects;
import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 让 {@code CraftingItemHandler}（合成/切石升级共用的 3x3 矩阵）的库存供应商可替换。
 *
 * <p>{@code setChanged()} 只把缓存的 {@code items} 快照标记为失效，调用它会强制下一次
 * {@code getItems()} 从新库存重建；它不会触发菜单/槽位变化，因此在构造期调用安全。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingItemHandler", remap = false)
public abstract class CraftingItemHandlerMixin implements RebindableCraftingMatrix {

    @Shadow(remap = false)
    @Final
    @Mutable
    private Supplier<IItemHandlerModifiable> supplyInventory;

    @Shadow(remap = false)
    public abstract void setChanged();

    @Override
    public void dimblend$setInventorySupplier(Supplier<IItemHandlerModifiable> supplier) {
        this.supplyInventory = Objects.requireNonNull(supplier, "supplier");
        this.setChanged();
    }
}
