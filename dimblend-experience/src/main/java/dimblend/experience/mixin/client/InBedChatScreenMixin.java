package dimblend.experience.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.InBedChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 睡觉回血改版（2026-10-31 用户口径「只删按钮、ESC 保留」）：
 * 只把原版「起床」按钮设为不可见；{@code keyPressed} 的 key 256 与 {@code onClose}
 * 的起床路径一行不动——8 秒后服务端自动起身，玩家仍可随时按 ESC 提前起。
 *
 * <p>按钮字段由目标类自己声明（{@code InBedChatScreen#leaveBedButton}），@Shadow
 * 只解析目标类自声明字段，所以这里可行；该字段在 {@code init} 末尾才赋值，注入点用
 * TAIL 并钉死匹配数。</p>
 */
@Mixin(InBedChatScreen.class)
public abstract class InBedChatScreenMixin {
    @Shadow
    private Button leaveBedButton;

    @Inject(method = "init", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$hideLeaveBedButton(CallbackInfo ci) {
        // visible=false 会让 final 的 AbstractWidget#render 直接跳过绘制，点击与焦点也一并
        // 失效；按钮仍留在 children 列表里，避免改动 Screen 的控件集合。
        this.leaveBedButton.visible = false;
    }
}
