package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.InitialStackSyncState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 背包打开初期不把"记忆槽位/槽位过滤器"画成半透明无数量幽灵物品。
 *
 * <p>目标：{@code net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase}
 * （不在编译期 classpath，{@code @Pseudo} 按字符串匹配）。上游 {@code renderSlot} 在
 * {@code slot.getItem()} 为空时走 {@code renderSlotBackground}：把记忆槽位或槽位过滤器里的
 * 物品画出来，再叠一层 {@code GUI_CONTROLS(77,0)} 压暗贴图，且永不画数量——看起来与设置
 * 模式一致。客户端槽位表尚未收到 {@code SyncContainerStacksPayload} 时所有存储格都是"空"，
 * 这段回退渲染就会把过滤器里的物品当成真实内容显示。</p>
 *
 * <p>菜单侧（{@code StorageContainerMenuSyncMixin}）在 {@code initializeContents} 到达时置位；
 * 到达之前（或 20 tick 宽限期内）整段跳过背景回退渲染，同步到达后一切走正常分支
 * （带数量、不透明）。正常打开时这个窗口只有一两个 tick，肉眼不可见。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase", remap = false)
public abstract class StorageScreenInitialSyncMixin {

    @Inject(method = "renderSlotBackground(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;II)V",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$skipGhostBackgroundBeforeInitialSync(GuiGraphics graphics, Slot slot, int x, int y,
            CallbackInfo ci) {
        if (!dimblend$initialSyncSettled()) {
            ci.cancel();
        }
    }

    @Unique
    private boolean dimblend$initialSyncSettled() {
        if ((Object) this instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() instanceof InitialStackSyncState state) {
            return state.dimblend$isInitialStackSyncSettled();
        }
        // 菜单 mixin 未应用（或别的菜单实现）：不改变上游行为。
        return true;
    }
}
