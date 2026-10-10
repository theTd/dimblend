package dimblend.experience.gametest;

import java.util.Optional;

import dimblend.experience.Config;
import dimblend.experience.loot.ExplorationMapLootFilter;
import dimblend.experience.mixin.LootPoolAccessor;
import dimblend.experience.mixin.LootTableAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A10 战利品地图过滤的运行时织入检查（事件改写表 + mixin 兜底都是运行时行为，
 * 纯函数单测覆盖不到）。全部用例体同步执行，配置改动 finally 还原。
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class LootMapFilterGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final String BATCH = "loot_map_filter";

    /**
     * 数据层：原版三张藏宝图表（沉船地图箱、大小水下废墟）加载后不再含禁出地图条目，
     * 其余条目保留（沉船表另有指南针/空地图/纸等第二 pool，不是空表）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void treasureMapTablesAreStripped(GameTestHelper helper) {
        assertTableStripped(helper, BuiltInLootTables.SHIPWRECK_MAP);
        assertTableStripped(helper, BuiltInLootTables.UNDERWATER_RUIN_BIG);
        assertTableStripped(helper, BuiltInLootTables.UNDERWATER_RUIN_SMALL);
        helper.succeed();
    }

    /**
     * 运行时兜底：藏宝图 destination（缺省值）与 dungeons_arise destination 的
     * exploration_map 函数开开关时产空堆、关开关时透传（附近无目标结构时原样返回
     * 空白地图，两种情况都非空堆）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void bannedExplorationMapYieldsEmptyStack(GameTestHelper helper) {
        boolean saved = Config.LOOT_MAP_FILTER.get();
        try {
            // makeExplorationMap 的缺省 destination 即 #minecraft:on_treasure_maps
            ExplorationMapFunction treasure = (ExplorationMapFunction) ExplorationMapFunction.makeExplorationMap().build();
            ExplorationMapFunction dungeonsArise = (ExplorationMapFunction) ExplorationMapFunction.makeExplorationMap()
                    .setDestination(TagKey.create(Registries.STRUCTURE,
                            ResourceLocation.fromNamespaceAndPath("dungeons_arise", "explorer_maps/monastery")))
                    .build();
            LootContext context = new LootContext.Builder(lootParams(helper)).create(Optional.empty());

            Config.LOOT_MAP_FILTER.set(true);
            helper.assertTrue(treasure.run(new ItemStack(Items.MAP), context).isEmpty(),
                    "banned treasure destination must yield an empty stack");
            helper.assertTrue(dungeonsArise.run(new ItemStack(Items.MAP), context).isEmpty(),
                    "banned dungeons_arise destination must yield an empty stack");

            Config.LOOT_MAP_FILTER.set(false);
            ItemStack allowed = treasure.run(new ItemStack(Items.MAP), context);
            helper.assertFalse(allowed.isEmpty(), "lootMapFilter=false must pass the map through");
            helper.succeed();
        } finally {
            Config.LOOT_MAP_FILTER.set(saved);
        }
    }

    private static LootParams lootParams(GameTestHelper helper) {
        return new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)))
                .create(LootContextParamSets.CHEST);
    }

    private static void assertTableStripped(GameTestHelper helper, ResourceKey<LootTable> key) {
        LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(key);
        int remaining = 0;
        for (LootPool pool : ((LootTableAccessor) table).dimblend$getPools()) {
            for (LootPoolEntryContainer entry : ((LootPoolAccessor) pool).dimblend$getEntries()) {
                helper.assertFalse(ExplorationMapLootFilter.isBannedMapEntry(entry),
                        key.location() + " still contains a banned exploration map entry");
                remaining++;
            }
        }
        helper.assertTrue(remaining > 0, key.location() + " must keep its non-map entries");
    }

    private LootMapFilterGameTests() {
    }
}
