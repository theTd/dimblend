package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyOptionsScreen;
import com.crystaelix.simurail.gui.SLabel;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 生存模式的转向架选项界面只留应力（2026-11-25 LOCKED #3 {@code bogey_survival_menu}）。
 *
 * <p>物理/角度/位移/垂直/控制/连接件六组设置整组隐藏（控件从可渲染列表移除、标题置灰留位），
 * 应力条保留可调。创造模式完全不受影响：无论哪种模式，服务端另有一道合并闸
 * （{@code PhysicsBogeySetOptionsPacket} 的 mixin），客户端界面只是"别让玩家点到
 * 改了也不生效的开关"。</p>
 */
@Mixin(value = PhysicsBogeyOptionsScreen.class, remap = false)
public abstract class BogeyOptionsScreenSurvivalMixin {

    private static final int GREYED_LABEL_COLOR = 0x9A9A9A;

    @Shadow
    private SLabel physicsLabel;
    @Shadow
    private SLabel rotationLabel;
    @Shadow
    private SLabel offsetLabel;
    @Shadow
    private SLabel verticalLabel;
    @Shadow
    private SLabel controlLabel;
    @Shadow
    private SLabel connectorLabel;

    @Shadow
    private SelectionScrollInput physicsInput;
    @Shadow
    private SelectionScrollInput rotationInput;
    @Shadow
    private SelectionScrollInput offsetInput;
    @Shadow
    private SelectionScrollInput verticalInput;
    @Shadow
    private SelectionScrollInput controlInput;
    @Shadow
    private SelectionScrollInput connectorInput;

    @Shadow
    protected abstract void removeWidget(GuiEventListener listener);

    @Inject(method = "init", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$restrictSurvivalOptions(CallbackInfo ci) {
        if (dimblend$isCreative()) {
            return;
        }
        dimblend$hide(physicsInput, physicsLabel);
        dimblend$hide(rotationInput, rotationLabel);
        dimblend$hide(offsetInput, offsetLabel);
        dimblend$hide(verticalInput, verticalLabel);
        dimblend$hide(controlInput, controlLabel);
        dimblend$hide(connectorInput, connectorLabel);
    }

    private void dimblend$hide(ScrollInput input, SLabel label) {
        if (input != null) {
            removeWidget(input);
        }
        if (label != null) {
            label.colored(GREYED_LABEL_COLOR);
        }
    }

    private static boolean dimblend$isCreative() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.isCreative();
    }
}
