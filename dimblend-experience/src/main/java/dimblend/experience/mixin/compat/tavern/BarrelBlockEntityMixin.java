package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.container.BarrelRecipeContainer;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterFeedback;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.tavern.BarrelWaterPurity;
import dimblend.experience.compat.tavern.TavernBrewingRules;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the unmatched-recipe Vinegar fallback to 4 items and holds fermentation idle when a
 * valid recipe is still below the 4-per-slot threshold in quarter-yield mode (so closing
 * the lid early never consumes 4 fluid buckets into Vinegar).
 *
 * <p>脏水（Purity 0）拒收也挂在这里：
 * <ul>
 *   <li>{@code addFluid}（盖子右键倒水）HEAD 拒收，hotbar 提示，不转移；</li>
 *   <li>tick 里 {@code BarrelRecipeContainer} 构造前把整个 tick 掐掉——容器只保留
 *       {@code Fluid}（{@code BarrelRecipeContainer#getFluid()} 丢掉组件），
 *       {@code BarrelRecipe#matches} 只看 {@code Fluid.isSame}，纯度在配方层已经不可见，
 *       所以"配方级保底"必须在构建容器之前做。酒桶没有方块流体能力，进罐只有
 *       {@code addFluid} 这一条路，两道口子合起来就是全部入口。</li>
 * </ul></p>
 */
@Mixin(value = BarrelBlockEntity.class, remap = false)
public abstract class BarrelBlockEntityMixin {

    @Shadow(remap = false)
    @Final
    private RecipeManager.CachedCheck<BarrelRecipeContainer, BarrelRecipe> quickCheck;

    @Inject(method = "addFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$rejectDirtyWater(LivingEntity user, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!DirtyWaterCompat.purityAvailable() || !DirtyWaterPurity.isDirtyWaterItem(stack)) {
            return;
        }
        Level level = ((BarrelBlockEntity) (Object) this).getLevel();
        if (level != null && !level.isClientSide) {
            DirtyWaterFeedback.notify(user);
        }
        cir.setReturnValue(false);
    }

    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;)V",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/neoforged/neoforge/items/IItemHandler;Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;)Lcom/github/ysbbbbbb/kaleidoscopetavern/crafting/container/BarrelRecipeContainer;"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$holdIncompleteQuarterBatch(Level level, CallbackInfo ci) {
        BarrelBlockEntity self = (BarrelBlockEntity) (Object) this;
        boolean holdsDirtyWater = DirtyWaterCompat.purityAvailable()
                && DirtyWaterPurity.isDirtyWaterFluid(self.getFluid().getFluid());
        if (holdsDirtyWater
                || TavernBrewingRules.shouldHoldIncompleteRecipeBatch(self, this.quickCheck, level)) {
            ci.cancel();
        }
    }

    @ModifyConstant(method = "lambda$tick$1()V", constant = @Constant(intValue = 16),
            remap = false, require = 1, allow = 1)
    private int dimblend$capFallbackVinegar(int original) {
        return TavernBrewingRules.outputCount();
    }

    /**
     * 倒进桶里的水一律灭菌（{@code Purity: 2}）。
     *
     * <p>{@code addFluid} 有两个 return（参数校验早退 + 真正倒水），{@code allow = 3} 覆盖
     * 全部；只有真正倒成功（{@code true}）才打标。{@code emptyItem} 走的是
     * {@code FluidBucketWrapper}，进桶的 FluidStack 不带组件，不补的话桶里就是"脏水"，
     * 会把 {@code dimblend$holdIncompleteQuarterBatch} 与配方匹配一起带偏。</p>
     */
    @ModifyReturnValue(method = "addFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("RETURN"), remap = false, require = 1, allow = 3)
    private boolean dimblend$stampBarrelWater(boolean original) {
        if (original && DirtyWaterCompat.purityAvailable()) {
            BarrelBlockEntity self = (BarrelBlockEntity) (Object) this;
            FluidStack tank = self.getFluid().getFluid();
            if (BarrelWaterPurity.isWater(tank)) {
                BarrelWaterPurity.stampTank(tank);
                self.setChanged();
            }
        }
        return original;
    }

    /**
     * 读档自愈：旧存档里桶中的水没有 Purity（或曾装过别的流体），
     * 只要现在装的是水就补 {@code Purity: 2}（只改组件，不动数量）。
     */
    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$healLoadedBarrelWater(CallbackInfo ci) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return;
        }
        BarrelBlockEntity self = (BarrelBlockEntity) (Object) this;
        FluidStack tank = self.getFluid().getFluid();
        if (BarrelWaterPurity.isWater(tank)) {
            BarrelWaterPurity.stampTank(tank);
        }
    }
}
