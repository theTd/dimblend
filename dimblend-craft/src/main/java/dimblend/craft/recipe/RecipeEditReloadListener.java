package dimblend.craft.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * 数据包重载监听器：在 vanilla 配方加载完成后执行本 mod 的配方编辑规则。
 *
 * <p>{@link AddReloadListenerEvent#addListener} 添加的监听器排在 vanilla 监听器之后，
 * 因此 apply 时 {@link RecipeManager} 已完成本次重载的配方解析，这里改写的结果
 * 就是本轮重载的最终状态（初始世界加载与 /reload 均走此路径）。</p>
 */
public final class RecipeEditReloadListener extends SimplePreparableReloadListener<Void> {

    private final RecipeManager recipeManager;
    private final HolderLookup.Provider registries;

    public RecipeEditReloadListener(AddReloadListenerEvent event) {
        this.recipeManager = event.getServerResources().getRecipeManager();
        this.registries = event.getRegistryAccess();
    }

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null;
    }

    @Override
    protected void apply(Void prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        RecipeEditApplicator.apply(this.recipeManager, this.registries, RecipeEditRules.edits());
    }
}