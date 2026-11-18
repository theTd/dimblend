package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.BlockConverterBindable;
import dimblend.experience.compat.sophisticated.SophisticatedWrapperBridge;
import dimblend.experience.compat.sophisticated.WrapperRefreshHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 切石机升级容器：构造末尾把配方容器绑定到自己，wrapper 替换后触发刷新。
 *
 * <p>{@code BlockConverterUpgradeContainer} 是铁砧/切石/磨石三个升级的公共基类；
 * 配方容器在 {@code this.recipeContainer = createRecipeContainer(...)} 之后才可访问，
 * 所以绑定只能放在构造器 TAIL。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.blockconverter.BlockConverterUpgradeContainer", remap = false)
public abstract class BlockConverterUpgradeContainerMixin implements WrapperRefreshHook {

    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;ILnet/p3pp3rf1y/sophisticatedcore/upgrades/blockconverter/BlockConverterUpgradeWrapper;Lnet/p3pp3rf1y/sophisticatedcore/common/gui/UpgradeContainerType;)V",
            at = @At("TAIL"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$bindRecipeContainerToSelf(CallbackInfo ci) {
        if (SophisticatedWrapperBridge.getRecipeContainer(this) instanceof BlockConverterBindable bindable) {
            bindable.dimblend$bindToOwnerContainer(this);
        }
    }

    @Override
    public void dimblend$afterWrapperReplaced() {
        if (SophisticatedWrapperBridge.getRecipeContainer(this) instanceof BlockConverterBindable bindable) {
            bindable.dimblend$refreshFromWrapper();
        }
    }
}
