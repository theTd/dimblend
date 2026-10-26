package dimblend.experience.loot;

import java.util.ArrayList;
import java.util.List;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.mixin.LootPoolAccessor;
import dimblend.experience.mixin.LootTableAccessor;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;

/**
 * A10 战利品地图过滤（全局功能，默认开，数据层第一层）：
 * 任何战利品箱不再产出地图——战利品表加载时把每个 pool 条目树里的
 * {@code minecraft:map} / {@code minecraft:filled_map} / 任何 {@link net.minecraft.world.item.MapItem}
 * 子类条目<b>替换</b>为 1 张纸（{@code minecraft:paper}），而不是移除。
 *
 * <p>替换规则见 {@link MapLootEntryRewrite}：保留 weight / quality / conditions，
 * 丢弃全部 functions（故恒为 1 张纸）；alternatives / group / sequence 递归处理。
 * 判据只看物品，<b>不再看 exploration_map 的 destination</b>——空地图、藏宝图、
 * Dungeons Arise / Nova Structures 等一切目的地一视同仁。所有命名空间的战利品表
 * 都经本事件（{@code LootTableLoadEvent} 对原版/模组/数据包全覆盖，{@code /reload}
 * 后重新生效），pool entries 是 ImmutableList，经 accessor 整体换引用。</p>
 *
 * <p>三层实现（全部受 {@code lootMapFilter} 开关控制）：
 * ① 本类表加载替换（JEI/JER 展示同步干净）；② {@code ExplorationMapFunctionBanMixin}
 * 运行时短路一切 exploration_map 目的地并产纸（不跑昂贵的结构搜索，覆盖复合条目/
 * 程序化战利品表/模组 GLM 路径）；③ {@link PaperInsteadOfMapsLootModifier} 全局
 * 战利品修饰器兜底，把最终生成的任何地图堆换成 1 张纸。
 * 制图师村民交易不走战利品表；那条路径由 A13 直接禁止探险图条目
 * （{@link dimblend.experience.mixin.TreasureMapTradeMixin}，与维度无关、无开关）。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class ExplorationMapLootFilter {

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        // SERVER 配置未加载时按默认开启过滤（与 Config 默认值一致）
        if (Config.isLoaded() && !Config.LOOT_MAP_FILTER.get()) {
            return;
        }
        LootTable table = event.getTable();
        int replaced = 0;
        for (LootPool pool : ((LootTableAccessor) table).dimblend$getPools()) {
            LootPoolAccessor poolAccess = (LootPoolAccessor) pool;
            List<LootPoolEntryContainer> entries = poolAccess.dimblend$getEntries();
            List<LootPoolEntryContainer> rewritten = null;
            for (int i = 0; i < entries.size(); i++) {
                LootPoolEntryContainer entry = entries.get(i);
                LootPoolEntryContainer next = MapLootEntryRewrite.rewriteEntry(entry);
                if (next != entry) {
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(entries);
                    }
                    rewritten.set(i, next);
                    replaced++;
                }
            }
            if (rewritten != null) {
                // 解码产物是 ImmutableList，必须整体换引用而非原地 set
                poolAccess.dimblend$setEntries(rewritten);
            }
        }
        if (replaced > 0) {
            DimBlend.LOGGER.info("[A10] loot table {}: replaced {} map entries with paper",
                    event.getName(), replaced);
        }
    }

    private ExplorationMapLootFilter() {
    }
}
