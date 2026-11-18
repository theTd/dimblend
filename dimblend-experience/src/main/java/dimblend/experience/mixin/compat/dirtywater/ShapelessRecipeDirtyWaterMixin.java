package dimblend.experience.mixin.compat.dirtywater;

import dimblend.experience.compat.dirtywater.DirtyWaterCraftingRules;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 无序合成：同 {@link ShapedRecipeDirtyWaterMixin}，网格里有 Purity 0 的水且输出是
 * 食物/饮品时配方不匹配。
 */
@Mixin(ShapelessRecipe.class)
public abstract class ShapelessRecipeDirtyWaterMixin {

    @Inject(method = "matches(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/world/level/Level;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$rejectDirtyWater(CraftingInput input, Level level, CallbackInfoReturnable<Boolean> cir) {
        if (DirtyWaterCraftingRules.blocksRecipe((Recipe<?>) (Object) this, input, level)) {
            cir.setReturnValue(false);
        }
    }
}
