package dimblend.experience.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import dimblend.experience.compat.hopper.VanillaHopperSpeed;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;

/**
 * 漏斗 ×4 的第三条路径：NeoForge 把 {@code TileEntityHopper#insertStack} 复制到
 * {@code VanillaInventoryCodeHooks#insertStack} 并接管能力（capability）容器。
 * 该方法是 {@code private static}，节律只有一处字面量 {@code 8}——
 * 目的地是漏斗时 {@code setCooldown(8 - k)}。
 *
 * <p>NeoForge 类不做映射（{@code remap = false}），与仓库内其它 NeoForge 目标一致。</p>
 */
@Mixin(value = VanillaInventoryCodeHooks.class, remap = false)
public abstract class VanillaInventoryCodeHooksSpeedMixin {

    @ModifyConstant(
            method = "insertStack",
            constant = @Constant(intValue = VanillaHopperSpeed.VANILLA_COOLDOWN_TICKS),
            require = 1, allow = 1,
            remap = false)
    private static int dimblend$fasterCapabilityCooldown(int original) {
        return VanillaHopperSpeed.COOLDOWN_TICKS;
    }
}
