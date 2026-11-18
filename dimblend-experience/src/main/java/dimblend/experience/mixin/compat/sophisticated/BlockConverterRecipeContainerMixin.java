package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.BlockConverterBindable;
import dimblend.experience.compat.sophisticated.RebindableCraftingMatrix;
import dimblend.experience.compat.sophisticated.RebindableSlotSupplier;
import dimblend.experience.compat.sophisticated.SophisticatedWrapperBridge;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 切石机（block converter）配方容器动态跟随当前 wrapper。
 *
 * <p>1.4.38 与上游 1.5.38 都在构造时把 {@code upgradeContainer.getUpgradeWrapper()} 求值进
 * {@code inputSlot}、{@code inputInventory}、{@code getLastSelectedRecipeId}、
 * {@code setLastSelectedRecipeId}。模块槽同步替换 wrapper 后：输入格仍读写旧库存、选中配方
 * 仍写旧 {@code ItemStack}——放入的物品在客户端不出现，重开背包才恢复。</p>
 *
 * <p>绑定入口由 {@code BlockConverterUpgradeContainerMixin} 在容器构造末尾调用，因为本类拿不到
 * 上层容器；{@code inputInventory} 由构造器末句 {@code this.onCraftMatrixChanged(this.inputInventory, false)}
 * 入口直接捕获，免去跨模块私有字段反射；刷新入口是 wrapper 替换后的 {@code dimblend$refreshFromWrapper()}。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.blockconverter.BlockConverterRecipeContainer", remap = false)
public abstract class BlockConverterRecipeContainerMixin implements BlockConverterBindable {

    @Shadow(remap = false)
    @Final
    private Slot inputSlot;

    @Shadow(remap = false)
    @Final
    @Mutable
    private Supplier<Optional<ResourceLocation>> getLastSelectedRecipeId;

    @Shadow(remap = false)
    @Final
    @Mutable
    private Consumer<ResourceLocation> setLastSelectedRecipeId;

    @Shadow(remap = false)
    private void onCraftMatrixChanged(Container inventory, boolean countIncreased) {
        throw new AssertionError("shadow method");
    }

    @Unique
    private Container dimblend$capturedInputInventory;

    @Inject(
            method = "onCraftMatrixChanged(Lnet/minecraft/world/Container;Z)V",
            at = @At("HEAD"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$captureInputInventory(Container inventory, boolean countIncreased, CallbackInfo ci) {
        if (this.dimblend$capturedInputInventory == null && inventory != null) {
            this.dimblend$capturedInputInventory = inventory;
        }
    }

    @Override
    public void dimblend$bindToOwnerContainer(Object ownerUpgradeContainer) {
        Object owner = ownerUpgradeContainer;
        if (this.inputSlot instanceof RebindableSlotSupplier rebindableSlot) {
            rebindableSlot.dimblend$setItemHandlerSupplier(
                    () -> SophisticatedWrapperBridge.resolveBlockConverterInputInventory(owner));
        }
        Container inputInventory = this.dimblend$capturedInputInventory != null
                ? this.dimblend$capturedInputInventory
                : SophisticatedWrapperBridge.getBlockConverterInputInventoryField(this);
        if (inputInventory instanceof RebindableCraftingMatrix rebindableMatrix) {
            rebindableMatrix.dimblend$setInventorySupplier(
                    () -> SophisticatedWrapperBridge.resolveBlockConverterInputInventory(owner));
        }
        this.getLastSelectedRecipeId = () -> SophisticatedWrapperBridge.resolveBlockConverterRecipeId(owner);
        this.setLastSelectedRecipeId =
                recipeId -> SophisticatedWrapperBridge.writeBlockConverterRecipeId(owner, recipeId);
    }

    @Override
    public void dimblend$refreshFromWrapper() {
        Container inputInventory = this.dimblend$capturedInputInventory != null
                ? this.dimblend$capturedInputInventory
                : SophisticatedWrapperBridge.getBlockConverterInputInventoryField(this);
        if (inputInventory != null) {
            inputInventory.setChanged();
            this.onCraftMatrixChanged(inputInventory, false);
        }
    }
}
