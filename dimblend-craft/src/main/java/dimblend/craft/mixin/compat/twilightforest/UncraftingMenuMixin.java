package dimblend.craft.mixin.compat.twilightforest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拆解台反推清单清空。
 *
 * <p>整合包把 {@code disableUncrafting} 设成 true，但那个开关只拦住「从拆解格取出成品」
 * （UncraftingMenu#slotsChanged 之后的两处判断），候选清单本身照常计算并列出，
 * 所以台面上仍然看得到每一条可逆配方。这里在候选计算的入口直接返回空表。
 *
 * <p>目标方法来自整合包实装 jar
 * {@code [暮色森林] twilightforest-1.21.1-4.8.3345-universal.jar}，不是本机源码：
 * <pre>
 * private static net.minecraft.world.item.crafting.Recipe&lt;?&gt;[]
 *     getRecipesFor(net.minecraft.world.item.ItemStack, net.minecraft.world.level.Level)
 * descriptor: (Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)[Lnet/minecraft/world/item/crafting/Recipe;
 * </pre>
 *
 * <p>两个必须遵守的细节：
 * <ul>
 *   <li>本类里 {@code getRecipesFor} 有第二个重载
 *       {@code (CraftingInput, Level) -> RecipeHolder<CraftingRecipe>[]}，
 *       所以方法选择器必须带完整描述符，只写方法名会同时命中两个重载。</li>
 *   <li>目标方法是 {@code static}，Mixin 0.8.5 的 {@code Injector#checkTargetModifiers}
 *       要求 handler 的静态性与目标完全一致，实例 handler 会直接抛
 *       {@code non-static callback method ... targets a static method which is not supported}。
 *       故 handler 只能是 {@code private static}。同模块的
 *       {@code MillstoneSearchRecipeMixin} 对 static 目标也是这么写的。</li>
 * </ul>
 *
 * <p>上游若改了签名，{@code require = 1} 会让加载期直接失败，而不是静默什么都不做。
 * 暮色森林缺席时本 mixin 由 {@code DimBlendCraftMixinPlugin} 跳过，不会应用。
 */
@Mixin(targets = "twilightforest.inventory.UncraftingMenu", remap = false)
public abstract class UncraftingMenuMixin {

    @Inject(
            method = "getRecipesFor(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)[Lnet/minecraft/world/item/crafting/Recipe;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private static void dimblend$emptyUncraftingCandidates(ItemStack inputStack, Level world,
            CallbackInfoReturnable<Recipe<?>[]> cir) {
        // 输入与等级都不参与判断：这个任务的目的就是让清单恒为空。
        cir.setReturnValue(new Recipe<?>[0]);
    }
}