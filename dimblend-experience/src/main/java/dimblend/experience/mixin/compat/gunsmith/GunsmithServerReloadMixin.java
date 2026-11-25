package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithMath;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端换弹速度加速（0–50 级线性 {@code 1.0x..1.5x}）。
 *
 * <p>经字节码核实（{@code tacz-neoforge-1.21.1-1.1.8-hotfix-r1.jar}）：
 * <ul>
 *   <li>默认 Java 换弹 {@code ModernKineticGunItem#defaultTickReload}（第 346 行）调用
 *       {@code api.getReloadTime()}；</li>
 *   <li>扩容弹匣脚本（{@code xmag_reload_logic.lua}）与逐发装填脚本（{@code kar98_gun_logic.lua}、
 *       {@code m870_gun_logic.lua} 等）全部调用 {@code api:getReloadTime()}。</li>
 * </ul>
 * 因此将 {@code getReloadTime()} 的已流逝毫秒数乘以 {@link GunsmithMath#reloadSpeedMultiplier(int)}
 * 即可一处覆盖全包 67 把枪；客户端骨骼动画由 {@link GunsmithClientAnimationMixin} 同步加速。</p>
 *
 * <p>方法体有 2 处 {@code lreturn}（未换弹提前返回 0 与末尾返回已耗时），
 * 故使用 {@code @At("TAIL")} 仅命中正常返回分支。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunScriptAPI", remap = false)
public abstract class GunsmithServerReloadMixin {

    @Shadow
    private ItemStack itemStack;

    @Inject(method = "getReloadTime()J", at = @At("TAIL"), cancellable = true, require = 1, allow = 1)
    private void dimblend$speedUpReload(CallbackInfoReturnable<Long> cir) {
        long base = cir.getReturnValueJ();
        if (base <= 0L) {
            return;
        }
        int level = GunsmithData.getLevel(this.itemStack);
        if (level <= 0) {
            return;
        }
        cir.setReturnValue(Math.round(base * (double) GunsmithMath.reloadSpeedMultiplier(level)));
    }
}
