package dimblend.experience.mixin.compat.cookery;

import dimblend.experience.compat.cookery.TeapotServingRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 茶壶一份原料产出 {@code 12 -> 4} 杯（第 1 / 5 个硬编码点：服务端唯一产出点）。
 *
 * <p>{@code TeapotRecipe#assemble(TeapotInput, HolderLookup.Provider)} 里
 * {@code recipe.getResultItem(registries).copyWithCount(12)} 是全模组唯一的产出裁决；
 * {@code TeapotBlockEntity} 只调用它，所以改这里就够。{@code bipush 12} 在 jar 里只出现
 * 一次（其它 4 处 12 分别在 GUI/JEI/EMI/REI，各自单独处理）。</p>
 */
@Pseudo
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.TeapotRecipe", remap = false)
public abstract class TeapotRecipeMixin {

    @ModifyConstant(
            method = "assemble(Lcom/github/ysbbbbbb/kaleidoscopecookery/crafting/container/TeapotInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
            constant = @Constant(intValue = 12), remap = false, require = 1, allow = 1)
    private int dimblend$fourServingsPerPot(int original) {
        return TeapotServingRules.outputCount();
    }
}
