package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.loot.MapLootEntryRewrite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A10 战利品地图过滤的运行时第二层：{@code exploration_map} 函数<b>一律</b>短路，
 * 不再区分 {@code destination}（原版只禁藏宝图 tag + Dungeons Arise 命名空间，
 * Nova Structures 的 {@code nova_structures:*}/{@code minecraft:ancient_city}/
 * {@code minecraft:pillager_outpost} 等目的地会漏）。命中地图堆时直接返回
 * 1 张纸，<b>绝不执行原版 {@code findNearestMapStructure} 结构搜索</b>
 * （大搜索半径/未加载区块下很贵，且结果反正要被第三层替换；原版
 * {@code ExplorationMapFunction.java} 76-95 行先认 MAP 再调
 * {@code findNearestMapStructure}）。
 *
 * <p>覆盖：复合条目嵌套、程序化生成的战利品表、经该函数产图的模组 GLM 都在内；
 * 直接产出成品地图 ItemStack 而不经该函数的 GLM 由
 * {@link dimblend.experience.loot.PaperInsteadOfMapsLootModifier} 兜底。
 * 关 {@code lootMapFilter} 时完全透传（原版行为）。</p>
 */
@Mixin(ExplorationMapFunction.class)
public abstract class ExplorationMapFunctionBanMixin {

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void dimblend$replaceExplorationMapWithPaper(ItemStack stack, LootContext context,
            CallbackInfoReturnable<ItemStack> cir) {
        // SERVER 配置未加载时按默认开启过滤（与 Config 默认值一致）
        if (Config.isLoaded() && !Config.LOOT_MAP_FILTER.get()) {
            return;
        }
        // 原版 run 首行只认 Items.MAP（EmptyMapItem，不是 MapItem 子类）；这里连成品
        // 地图一起拦（第三层同样换纸），保证该函数无论输入什么地图都产纸、且不进入结构搜索
        if (!MapLootEntryRewrite.isMapItem(stack.getItem())) {
            return;
        }
        cir.setReturnValue(new ItemStack(Items.PAPER));
    }
}
