package dimblend.experience.mixin.compat.dirtywater;

import dimblend.experience.compat.dirtywater.DirtyWaterCraftingRules;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 有序合成：网格里有 Purity 0 的水、输出又是食物/饮品（含面团）时，配方直接不匹配。
 *
 * <p>拦在 {@code matches} 而不是 {@code RecipeManager#getRecipeFor}：精致背包
 * {@code CraftingUpgradeContainer} 会缓存 {@code lastRecipe} 并直接调
 * {@code lastRecipe.matches(...)}，绕过食谱管理器；配方自身的 {@code matches} 是唯一
 * 三端（工作台/自动合成器/背包）都必经的点。</p>
 */
@Mixin(ShapedRecipe.class)
public abstract class ShapedRecipeDirtyWaterMixin {

    @Inject(method = "matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$rejectDirtyWater(CraftingInput input, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (DirtyWaterCraftingRules.blocksRecipe((Recipe<?>) (Object) this, input, level)) {
            cir.setReturnValue(false);
        }
    }
}
