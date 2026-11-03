package dimblend.craft.mixin.compat.twilightforest;

import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 配方查看器（JEI / EMI）的拆解分类也清空。
 *
 * <p><b>为什么 {@link UncraftingMenuMixin} 不够。</b>那个 mixin 只清空拆解台自己的反推清单，
 * 而 JEI 的「拆解」分类页走的是另一条路：
 * {@code twilightforest.compat.RecipeViewerConstants#getAllUncraftingRecipes(RecipeManager)}，
 * 由 {@code JEICompat} 在配方重载期调用。两条路互不相干。
 *
 * <p><b>为什么不能只靠删配方（{@code RecipeEditRules} 里那三条）。</b>那三条特殊配方
 * （{@code uncrafting/tipped_arrow}、{@code uncrafting/written_book}、
 * {@code relinquish_keepsakes}）是在 {@code RecipeIngredientReloadFilter#onTagsUpdated}
 * 里从 {@code RecipeManager} 移除的，而 JEI 在**重载过程内部**就把列表收集走了。
 * 2026-11-03 那次真实会话的日志把这段时序摊开了：
 * <pre>
 * 07:43:14  Reloading ResourceManager …          ← 重载开始，JEI 在这里收集
 * 07:43:58  [配方] 删除 twilightforest:uncrafting/tipped_arrow（规则：暮色拆解台特殊配方）
 * </pre>
 * 删除确实命中（三条都在），但晚了 44 秒，JEI 手里已经是旧列表——这就是用户看到的
 * 「未生效」。删除逻辑本身没坏，**是它赢不了这场竞速**。
 *
 * <p><b>为什么这里返回空而不是照抄 TF 的空分支。</b>TF 自己在
 * {@code disableUncraftingOnly} 为真时并不是返回空，而是返回
 * {@code getAllRecipesFor(UNCRAFTING_RECIPE)}——也就是那三条特殊配方（配置注释原文：
 * "special uncrafting recipes are not disabled as the mod relies on them for other things"）。
 * 本任务要的是「一条都不显示」，所以直接给空表，与删除是否已经跑完无关。
 *
 * <p><b>只删显示，不动配方本身。</b>TF 的其它机制依赖这几条特殊配方（配置注释点名），
 * 所以这里只让配方查看器看不见它们；真正的配方实体仍由 {@code RecipeEditRules} 按 id 删除。
 * 两个动作目的不同，保留两者。
 *
 * <p>目标是客户端类（同类里 {@code renderFlatBlock(PoseStack, …)} 是客户端方法），
 * 所以列在 {@code dimblend_craft.mixins.json} 的 {@code client} 数组；TF 缺席时由
 * {@code DimBlendCraftMixinPlugin} 跳过。
 */
@Mixin(targets = "twilightforest.compat.RecipeViewerConstants", remap = false)
public abstract class UncraftingViewerMixin {

    @Inject(
            method = "getAllUncraftingRecipes(Lnet/minecraft/world/item/crafting/RecipeManager;)"
                    + "Ljava/util/List;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private static void dimblend$emptyUncraftingViewerList(RecipeManager recipeManager,
            CallbackInfoReturnable<List<RecipeHolder<?>>> cir) {
        cir.setReturnValue(List.of());
    }
}