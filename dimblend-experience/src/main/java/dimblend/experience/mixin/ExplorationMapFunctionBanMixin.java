package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.loot.ExplorationMapLootFilter;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A10 战利品地图过滤的运行时兜底：{@code ExplorationMapLootFilter} 已清掉 JSON
 * 表内直挂的禁出地图条目，本 mixin 拦 exploration_map 函数的执行——复合条目嵌套、
 * 程序化生成的战利品表、经该函数产图的 GLM 均在覆盖内，命中禁令时产出空堆
 * （箱子表现为少一件物品，地图绝不出现）。直接产出成品地图 ItemStack 而不经
 * 该函数的 GLM 不在覆盖内（在案 mod 无此用法）。
 */
@Mixin(ExplorationMapFunction.class)
public abstract class ExplorationMapFunctionBanMixin {

    @Shadow
    @Final
    private TagKey<Structure> destination;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void dimblend$banExplorationMap(ItemStack stack, LootContext context, CallbackInfoReturnable<ItemStack> cir) {
        // SERVER 配置未加载时按默认开启过滤（与 Config 默认值一致）
        if (Config.isLoaded() && !Config.LOOT_MAP_FILTER.get()) {
            return;
        }
        // 与原版 run 首行同口径：非地图堆透传，不介入
        if (!stack.is(Items.MAP)) {
            return;
        }
        if (ExplorationMapLootFilter.isBannedDestination(this.destination)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
