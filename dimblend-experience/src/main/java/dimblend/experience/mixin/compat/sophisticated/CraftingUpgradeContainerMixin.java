package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.RebindableCraftingMatrix;
import dimblend.experience.compat.sophisticated.RebindableSlotSupplier;
import dimblend.experience.compat.sophisticated.SophisticatedWrapperBridge;
import dimblend.experience.compat.sophisticated.WrapperRefreshHook;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 合成升级的槽位与矩阵跟随当前 wrapper。
 *
 * <p>1.4.38 的构造器把 {@code upgradeWrapper::getInventory} 绑进 9 个
 * {@code SlotSuppliedHandler} 与 {@code CraftingItemHandler}，而 {@code upgradeWrapper} 是构造参数
 * 局部变量；客户端模块槽同步替换 wrapper 后两者仍读写旧 {@code ItemStack} 的
 * {@code StatefulComponentItemHandler}（其内部还缓存了 {@code stacks}）。</p>
 *
 * <p>修复：构造器 TAIL 把两者重绑成"调用时经 {@link SophisticatedWrapperBridge} 解析容器当前
 * wrapper 的库存"的 lambda，并在 {@code setUpgradeWrapper} 之后刷新矩阵与结果。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeContainer", remap = false)
public abstract class CraftingUpgradeContainerMixin implements WrapperRefreshHook {

    @Shadow(remap = false)
    public abstract List<Slot> getRecipeSlots();

    @Shadow(remap = false)
    public abstract Container getCraftMatrix();

    @Shadow(remap = false)
    private void onCraftMatrixChanged(Container inventory) {
        throw new AssertionError("shadow method");
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;ILnet/p3pp3rf1y/sophisticatedcore/upgrades/crafting/CraftingUpgradeWrapper;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerType;)V",
            at = @At("TAIL"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$rebindToCurrentWrapper(CallbackInfo ci) {
        Supplier<IItemHandler> slotSupplier = () -> SophisticatedWrapperBridge.resolveInventory(this);
        for (Slot slot : this.getRecipeSlots()) {
            if (slot instanceof RebindableSlotSupplier rebindable) {
                rebindable.dimblend$setItemHandlerSupplier(slotSupplier);
            }
        }
        Container matrix = this.getCraftMatrix();
        if (matrix instanceof RebindableCraftingMatrix rebindableMatrix) {
            rebindableMatrix.dimblend$setInventorySupplier(
                    () -> SophisticatedWrapperBridge.resolveInventory(this));
        }
    }

    @Override
    public void dimblend$afterWrapperReplaced() {
        Container matrix = this.getCraftMatrix();
        if (matrix != null) {
            matrix.setChanged();
            this.onCraftMatrixChanged(matrix);
        }
    }
}
