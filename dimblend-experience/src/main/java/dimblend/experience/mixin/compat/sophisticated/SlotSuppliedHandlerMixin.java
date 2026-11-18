package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.RebindableSlotSupplier;
import java.util.Objects;
import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 让 {@code SlotSuppliedHandler} 的库存供应商可替换。
 *
 * <p>目标字段 {@code itemHandlerSupplier} 的描述符是 {@code Ljava/util/function/Supplier;}，
 * 泛型参数不在描述符里，所以编译期可以按 {@code Supplier<IItemHandler>} 影子化。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.common.gui.SlotSuppliedHandler", remap = false)
public abstract class SlotSuppliedHandlerMixin implements RebindableSlotSupplier {

    @Shadow(remap = false)
    @Final
    @Mutable
    private Supplier<IItemHandler> itemHandlerSupplier;

    @Override
    public void dimblend$setItemHandlerSupplier(Supplier<IItemHandler> supplier) {
        this.itemHandlerSupplier = Objects.requireNonNull(supplier, "supplier");
    }
}
