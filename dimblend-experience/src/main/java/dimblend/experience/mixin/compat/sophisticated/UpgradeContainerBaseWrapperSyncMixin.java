package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.WrapperRefreshHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 升级容器替换 wrapper 后通知容器自身刷新。
 *
 * <p>模块槽同步（{@code StorageContainerMenuBase#updateWrappersAndCheckForReloadNeeded}）
 * 对"同物品"的新 wrapper 只调 {@code setUpgradeWrapper}，不重建容器；上游实现仅替换字段，
 * 构造期捕获的槽位/矩阵/配方供应商全部停在旧 wrapper 上。注入点放在 TAIL，命中时字段
 * 已经是新 wrapper，实现 {@link WrapperRefreshHook} 的子类 mixin 可立即重绑并重算。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase", remap = false)
public abstract class UpgradeContainerBaseWrapperSyncMixin {

    @Inject(
            method = "setUpgradeWrapper(Lnet/p3pp3rf1y/sophisticatedcore/upgrades/IUpgradeWrapper;)V",
            at = @At("TAIL"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$notifyWrapperReplaced(CallbackInfo ci) {
        if (this instanceof WrapperRefreshHook hook) {
            hook.dimblend$afterWrapperReplaced();
        }
    }
}
