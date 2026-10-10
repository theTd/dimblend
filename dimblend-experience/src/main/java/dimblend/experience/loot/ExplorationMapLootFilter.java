package dimblend.experience.loot;

import java.util.ArrayList;
import java.util.List;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.mixin.ExplorationMapFunctionAccessor;
import dimblend.experience.mixin.LootItemAccessor;
import dimblend.experience.mixin.LootPoolAccessor;
import dimblend.experience.mixin.LootPoolSingletonContainerAccessor;
import dimblend.experience.mixin.LootTableAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.LootTableLoadEvent;

/**
 * A10 战利品探索地图过滤（全局功能，默认开，数据层）：
 * 任何战利品箱不再产出 Dungeons Arise（含七海）的探险地图与原版藏宝图
 * （沉船 {@code minecraft:chests/shipwreck_map}、水下废墟的 buried_treasure 地图）。
 *
 * <p>实现：{@code LootTableLoadEvent} 在每张战利品表 JSON 加载时触发（原版/模组/
 * 数据包全覆盖，{@code /reload} 后重新生效），移除直接挂在 pool 下的禁出地图条目
 * （{@code minecraft:map} + exploration_map 函数且目的地命中禁令清单）。</p>
 *
 * <p>判定依据是 exploration_map 的 destination 结构 tag，而非地图图标：
 * 原版藏宝图 = {@code #minecraft:on_treasure_maps}（codec 缺省值，JSON 省略
 * destination 字段时同样命中）；Dungeons Arise = destination 命名空间
 * {@code dungeons_arise} / {@code dungeons_arise_seven_seas}。</p>
 *
 * <p>覆盖边界：复合条目（alternatives/sequence/group）嵌套的地图条目无法改写
 * （{@code CompositeEntryBase} 构造时即固化 composedChildren），程序化生成的战利品表
 * 不经本事件——两者由 {@link dimblend.experience.mixin.ExplorationMapFunctionBanMixin}
 * 运行时兜底，命中时产出空堆，地图同样不会出现。兜底拦的是 exploration_map 函数的
 * 执行：GLM 若经该函数产出地图同样在覆盖内；直接产出成品地图 ItemStack 的 GLM 不在
 * 覆盖内（在案 mod 无此用法）。制图师村民交易不走战利品表，不受影响。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class ExplorationMapLootFilter {

    /** 禁出地图的 destination 结构 tag 命名空间（Dungeons Arise 本体与七海） */
    private static final List<String> BANNED_DESTINATION_NAMESPACES = List.of("dungeons_arise", "dungeons_arise_seven_seas");

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        // SERVER 配置未加载时按默认开启过滤（与 Config 默认值一致）
        if (Config.isLoaded() && !Config.LOOT_MAP_FILTER.get()) {
            return;
        }
        LootTable table = event.getTable();
        int removed = 0;
        for (LootPool pool : ((LootTableAccessor) table).dimblend$getPools()) {
            LootPoolAccessor poolAccess = (LootPoolAccessor) pool;
            List<LootPoolEntryContainer> kept = null;
            List<LootPoolEntryContainer> entries = poolAccess.dimblend$getEntries();
            for (int i = 0; i < entries.size(); i++) {
                LootPoolEntryContainer entry = entries.get(i);
                if (isBannedMapEntry(entry)) {
                    if (kept == null) {
                        kept = new ArrayList<>(entries);
                    }
                    kept.remove(entry);
                }
            }
            if (kept != null) {
                // 解码产物是 ImmutableList，必须整体换引用而非原地 removeIf
                poolAccess.dimblend$setEntries(kept);
                removed += entries.size() - kept.size();
            }
        }
        if (removed > 0) {
            DimBlend.LOGGER.info("[A10] loot table {}: removed {} banned exploration map entries", event.getName(), removed);
        }
    }

    /**
     * 禁出地图条目判据：{@code minecraft:map} 条目且挂有 destination 命中禁令清单的
     * exploration_map 函数。空地图（无函数）与其他物品的条目不受影响。
     */
    public static boolean isBannedMapEntry(LootPoolEntryContainer entry) {
        if (!(entry instanceof LootItem) || ((LootItemAccessor) entry).dimblend$getItem().value() != Items.MAP) {
            return false;
        }
        for (LootItemFunction function : ((LootPoolSingletonContainerAccessor) entry).dimblend$getFunctions()) {
            if (function instanceof ExplorationMapFunction
                    && isBannedDestination(((ExplorationMapFunctionAccessor) function).dimblend$getDestination())) {
                return true;
            }
        }
        return false;
    }

    /** destination 结构 tag 命中禁令：原版藏宝图 tag 或 Dungeons Arise 命名空间 */
    public static boolean isBannedDestination(TagKey<Structure> destination) {
        ResourceLocation id = destination.location();
        return isBannedDestinationId(id.getNamespace(), id.getPath());
    }

    /** 纯函数版禁令判定（单测可达，不碰注册表） */
    static boolean isBannedDestinationId(String namespace, String path) {
        return ("minecraft".equals(namespace) && "on_treasure_maps".equals(path))
                || BANNED_DESTINATION_NAMESPACES.contains(namespace);
    }

    private ExplorationMapLootFilter() {
    }
}
