package dimblend.experience.mixin.compat.disenchantingtable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.compat.disenchantingtable.DisenchantingCurseRules;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 附魔台唯一的"能不能洗"判定：{@code DisenchantmentHelper.canDisenchant(ItemStack)Z}
 * （{@code javap -p} 核实：public static boolean，单参 ItemStack）。
 *
 * <p>{@code @Pseudo}：该模组是可选依赖，缺席时目标类不存在，mixin 不应用
 * （{@code DimBlendMixinPlugin} 再按 {@code disenchanting_table} 在场性过滤一次，
 * 见 manifest）。{@code remap = false}：目标类不在 Minecraft 的映射表里。</p>
 *
 * <p>HEAD + cancellable：只要带诅咒就 {@code false}，原逻辑完全不跑（不读附魔等级、
 * 不选诅咒）。干净物品原样透传。</p>
 */
@Pseudo
@Mixin(targets = "neoforge.com.cursee.disenchanting_table.core.util.DisenchantmentHelper", remap = false)
public abstract class DisenchantmentHelperCurseMixin {

    @Inject(method = "canDisenchant", at = @At("HEAD"), cancellable = true, remap = false,
            require = 1, allow = 1)
    private static void dimblend$refuseCursedItems(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (DisenchantingCurseRules.hasAnyCurse(stack)) {
            cir.setReturnValue(false);
        }
    }
}
