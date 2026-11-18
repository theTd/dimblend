package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.RebindableSlotSupplier;
import dimblend.experience.compat.sophisticated.SophisticatedWrapperBridge;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 锻造升级的三个输入槽跟随当前 wrapper（精妙背包）。
 *
 * <p>{@code PersistableSmithingMenu#createInputSlots} 用
 * {@code SmithingUpgradeContainer.this.upgradeWrapper::getInventory} 创建
 * {@code SlotSuppliedHandler}，方法引用在创建时绑定 wrapper 实例。模块槽同步替换 wrapper 后，
 * 三个输入槽仍读写旧 {@code ItemStack}（上游 {@code setUpgradeWrapper} 只补了
 * {@code createResult()}，没重绑槽位）。</p>
 *
 * <p>{@code SmithingUpgradeContainer#setUpgradeWrapper} 的 {@code createResult()} 保留不动：
 * 本 mixin 只负责槽位指向，结果重算由上游自己触发。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.smithing.SmithingUpgradeContainer", remap = false)
public abstract class SmithingUpgradeContainerMixin {

    @Shadow(remap = false)
    public abstract List<Slot> getRecipeSlots();

    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;ILnet/p3pp3rf1y/sophisticatedbackpacks/upgrades/smithing/SmithingUpgradeWrapper;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerType;)V",
            at = @At("TAIL"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$rebindRecipeSlotSuppliers(CallbackInfo ci) {
        Supplier<IItemHandler> slotSupplier = () -> SophisticatedWrapperBridge.resolveInventory(this);
        for (Slot slot : this.getRecipeSlots()) {
            if (slot instanceof RebindableSlotSupplier rebindable) {
                rebindable.dimblend$setItemHandlerSupplier(slotSupplier);
            }
        }
    }
}
