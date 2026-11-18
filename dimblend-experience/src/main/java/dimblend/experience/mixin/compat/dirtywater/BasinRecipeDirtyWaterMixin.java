package dimblend.experience.mixin.compat.dirtywater;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import dimblend.experience.compat.dirtywater.BasinDirtyWaterPolicy;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 工作盆配方级保底：输入罐里已经是脏水时，食物/饮品（含面团、{@code c:tea} 一类饮品流体）
 * 配方一律不启动。
 *
 * <p>注入点是私有的 {@code apply(BasinBlockEntity, Recipe<?>, boolean)}——{@code match}
 * （过滤槽判定 + SIMULATE）与 {@code apply}（真实执行）都汇总到这里，一处覆盖两条路径。
 * 这也兜住"脏水先进了罐、面粉后扔进来"与"过滤槽中途改成食物"两种绕过路径；
 * {@code create:potion}（{@code CreatePotionRecipe}）不走本方法，药水酿造不受影响。</p>
 */
@Mixin(value = BasinRecipe.class, remap = false)
public abstract class BasinRecipeDirtyWaterMixin {

    @Inject(
            method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;"
                    + "Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1
    )
    private static void dimblend$rejectDirtyWaterFood(BasinBlockEntity basin, Recipe<?> recipe, boolean simulate,
            CallbackInfoReturnable<Boolean> cir) {
        if (BasinDirtyWaterPolicy.blocksRecipe(basin, recipe)) {
            cir.setReturnValue(false);
        }
    }
}
