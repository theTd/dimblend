package dimblend.experience.mixin.compat.curios;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import net.minecraft.world.entity.player.Player;

/**
 * 生存模式下禁掉 Curios 的"外观/饰品视图"（{@code CuriosContainer}）。
 *
 * <p>{@code javap -p} 核实的公开形状：{@code public final Player player}、
 * {@code public boolean isViewingCosmetics}、{@code public void toggleCosmetics()}、
 * {@code public void resetSlots()}。</p>
 *
 * <p>两处注入：{@code toggleCosmetics} 直接拒绝（把视图状态压回 false），
 * {@code resetSlots} 再兜一次——容器重建/槽位重排时若视图状态仍为 true 就压回 false，
 * 免得"创造模式开着外观视图 → 切生存后菜单重建"把外观槽带进来。</p>
 */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.common.inventory.container.CuriosContainer", remap = false)
public abstract class CuriosContainerSurvivalMixin {

    /** 仓库纪律：{@code @Shadow} 成员一律 private（可见性不必与目标字段一致）。 */
    @Shadow
    @Final
    private Player player;

    @Shadow
    private boolean isViewingCosmetics;

    @Inject(method = "toggleCosmetics", at = @At("HEAD"), cancellable = true, remap = false,
            require = 1, allow = 1)
    private void dimblend$refuseCosmeticView(CallbackInfo ci) {
        if (AccessoriesSurvivalRules.isSurvival(player)) {
            this.isViewingCosmetics = false;
            ci.cancel();
        }
    }

    @Inject(method = "resetSlots", at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void dimblend$forceCosmeticViewOff(CallbackInfo ci) {
        if (this.isViewingCosmetics && AccessoriesSurvivalRules.isSurvival(player)) {
            this.isViewingCosmetics = false;
        }
    }
}
