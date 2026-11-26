package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.container.BarrelRecipeContainer;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
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
 *
 * <p>酒桶水纯度口径：桶内水与<b>取出来的水</b>统一 {@code Purity: 1}（过滤）——
 * <ul>
 *   <li>{@code addFluid} RETURN：倒进来的水一律覆写成 1；</li>
 *   <li>{@code loadAdditional} TAIL：旧存档里残留的 2/3 或无标签水，读档即统一成 1；</li>
 *   <li>{@code removeFluid}：包住唯一一次 {@code FluidUtils.fillItem}，先把桶内水覆写成 1，
 *       再进入"酒桶取水作用域"，让 {@link FluidUtilsPurityMixin} 把交出去的容器（含旧纯度）一律覆写成 1。</li>
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
     * 倒进桶里的水一律"过滤"（{@code Purity: 1}），旧值 {@code 2}/{@code 3} 也照覆写。
     *
     * <p>{@code addFluid} 有 <b>4</b> 个 {@code ireturn}（运行 jar
     * {@code kaleidoscopetavern-1.2.0-neoforge+mc1.21.1.jar} 字节码偏移 8 / 17 / 54 / 83）：
     * 桶未打开、正在酿造、配方槽位非空（这三条都返回 {@code false}，早于倒水短路），
     * 以及最后返回 {@code FluidUtils.emptyItem(...)} 的倒水结果。{@code allow = 4} 把四条
     * 返回点全部覆盖（{@code require = 1} 表示只要有一个方法内匹配成立就应用），随后
     * 只有真正倒成功（{@code true}）才打标。{@code emptyItem} 走的是
     * {@code FluidBucketWrapper}，进桶的 FluidStack 不带组件（或带着倒入容器的旧纯度），
     * 不覆写的话桶里就是"脏水"或旧的"杀菌"，会把
     * {@code dimblend$holdIncompleteQuarterBatch} 与配方匹配一起带偏。
     * </p>
     */
    @ModifyReturnValue(method = "addFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("RETURN"), remap = false, require = 1, allow = 4)
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
     * 从酒桶取水：先把桶内水统一成 {@code Purity: 1}（过滤），再在"酒桶取水作用域"里跑
     * {@code FluidUtils.fillItem}，让 {@link FluidUtilsPurityMixin} 把交到玩家手里的容器一律覆写成 1。
     *
     * <p>作用域精确套在这一个调用的原因：{@code removeFluid} 的取水就是
     * {@code FluidUtils.fillItem(user, stack, this.fluid, 4000)}（运行 jar 字节码偏移 61..70，
     * 签名 {@code (LivingEntity, ItemStack, IFluidHandler, int)Z}），容器物品在
     * {@code getItemToLivingEntity} 之前就已定型；HEAD/RETURN 两个 handler 手工配对在异常路径上
     * 会把 ThreadLocal 漏给同线程的后续 {@code fillItem}（榨汁桶共用这个方法），
     * 包住唯一调用点则没有这个缝。</p>
     *
     * <p>桶里不是水（酒/汤汁等）时整条逻辑放行原样，也不进入取水作用域。</p>
     */
    @WrapOperation(
            method = "removeFluid(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/ysbbbbbb/kaleidoscopetavern/util/FluidUtils;fillItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;I)Z"),
            remap = false, require = 1, allow = 1)
    private boolean dimblend$takeBarrelWaterAsFiltered(LivingEntity user, ItemStack stack, IFluidHandler handler,
            int amount, Operation<Boolean> original) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return original.call(user, stack, handler, amount);
        }
        BarrelBlockEntity self = (BarrelBlockEntity) (Object) this;
        FluidStack tank = self.getFluid().getFluid();
        if (!BarrelWaterPurity.isWater(tank)) {
            return original.call(user, stack, handler, amount);
        }
        // 旧存档里存成 2/3、或别处路径写进来的水：取水前统一成 1，抽出的 FluidStack 才是"过滤"
        BarrelWaterPurity.stampTank(tank);
        self.setChanged();
        return BarrelWaterPurity.withinBarrelTake(() -> original.call(user, stack, handler, amount));
    }

    /**
     * 读档自愈：旧存档里桶中的水没有 Purity（或曾装过别的流体 / 带 2、3 的旧纯度），
     * 只要现在装的是水就统一覆写成 {@code Purity: 1}（只改组件，不动数量）。
     *
     * <p>这里不主动 {@code setChanged()}：读档时标脏会把每个区块都压成待保存；
     * 自愈是幂等的，下次倒水/取水（那两处会标脏）也会把新值落到盘上。</p>
     */
    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$healLoadedBarrelWater(CallbackInfo ci) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return;
        }
        BarrelBlockEntity self = (BarrelBlockEntity) (Object) this;
        BarrelWaterPurity.stampTank(self.getFluid().getFluid());
    }
}
