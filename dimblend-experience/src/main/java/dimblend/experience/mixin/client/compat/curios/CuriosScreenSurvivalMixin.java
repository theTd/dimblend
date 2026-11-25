package dimblend.experience.mixin.client.compat.curios;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import dimblend.experience.compat.curios.CuriosCosmeticButtonHider;
import net.minecraft.client.gui.screens.Screen;

/**
 * 生存模式下抹掉 Curios 界面的外观按钮（客户端）：{@code CuriosScreen#updateRenderButtons()} TAIL。
 *
 * <p>为什么放在 TAIL：Curios 会在这个方法里先清掉旧的 {@code cosmeticButton}，再按
 * {@code menu.hasCosmetics} 决定是否新建——TAIL 时"已建/未建"两种情况都确定了，
 * 此时把按钮隐藏、失活并从三个 widget 列表里摘掉，才能保证这一次重建的结果里没有它。</p>
 *
 * <p>只做客户端显示层：服务端那段（{@link CuriosContainerSurvivalMixin}）已经拒绝切换视图，
 * 这里少一个按钮也不会造成两端状态不一致。{@code @Pseudo} 保证 Curios 缺席时不应用。</p>
 */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.client.gui.CuriosScreen", remap = false)
public abstract class CuriosScreenSurvivalMixin {

    @Inject(method = "updateRenderButtons", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$stripCosmeticButton(CallbackInfo ci) {
        if (!AccessoriesSurvivalRules.isClientSurvival()) {
            return;
        }
        CuriosCosmeticButtonHider.hide((Screen) (Object) this);
    }
}
