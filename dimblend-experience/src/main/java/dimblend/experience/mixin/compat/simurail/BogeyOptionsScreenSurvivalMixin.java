package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyOptionsScreen;
import com.crystaelix.simurail.gui.SLabel;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 *
 * <p>移除控件走<b>继承</b>而不是 {@code @Shadow}：{@code PhysicsBogeyOptionsScreen}
 * （extends {@code PhysicsBogeyScreen} extends {@code AbstractSimiScreen} extends {@code Screen}）
 * 自己并没有声明 {@code removeWidget}，它继承自 {@link Screen}，所以
 * {@code @Shadow protected abstract void removeWidget(GuiEventListener)} 会抛
 * {@code InvalidMixinException}（目标类里找不到该成员）。这里让 mixin 类直接
 * {@code extends Screen}（与 {@code GunsmithAnvilMenuMixin extends ItemCombinerMenu} 同型），
 * {@code this.removeWidget(input)} 就落回 {@code Screen#removeWidget}——它会同时清理
 * {@code renderables} / {@code narratables} / {@code children} 三份表，零反射。</p>
 */
@Mixin(value = PhysicsBogeyOptionsScreen.class, remap = false)
public abstract class BogeyOptionsScreenSurvivalMixin extends Screen {

    @Unique
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

    protected BogeyOptionsScreenSurvivalMixin(Component title) {
        super(title);
    }

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

    @Unique
    private void dimblend$hide(ScrollInput input, SLabel label) {
        if (input != null) {
            removeWidget(input);
        }
        if (label != null) {
            label.colored(GREYED_LABEL_COLOR);
        }
    }

    @Unique
    private static boolean dimblend$isCreative() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.isCreative();
    }
}
