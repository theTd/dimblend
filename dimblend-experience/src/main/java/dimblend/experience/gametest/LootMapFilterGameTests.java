package dimblend.experience.gametest;

import java.util.List;
import java.util.Optional;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import dimblend.experience.Config;
import dimblend.experience.loot.PaperInsteadOfMapsLootModifier;
import dimblend.experience.loot.MapLootEntryRewrite;
import dimblend.experience.mixin.CompositeEntryBaseAccessor;
import dimblend.experience.mixin.LootItemAccessor;
import dimblend.experience.mixin.LootPoolAccessor;
import dimblend.experience.mixin.LootPoolEntryContainerAccessor;
import dimblend.experience.mixin.LootPoolSingletonContainerAccessor;
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
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.EntryGroup;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A10 战利品地图过滤三层实现的运行时检查（表加载替换 / exploration_map 运行时短路 /
 * GLM 最终网都是运行时行为，纯函数单测覆盖不到）。用例体同步执行，配置改动 finally 还原。
 *
 * <p>第一层：原版三张藏宝图表加载后不再含任何地图条目、非地图条目保留；真实复合树
 * （alternatives 包 group，group 内挂条件与两种地图叶子）经
 * {@link MapLootEntryRewrite#rewriteEntry} 后全部变纸，且 weight/quality/conditions
 * 保留、functions 清空。第二层：任意 destination（藏宝图/Dungeons Arise/Nova
 * Structures/海底神殿/林地府邸）都产 1 张纸且不跑结构搜索；关开关透传。第三层：
 * 反复 roll {@code minecraft:chests/shipwreck_map} 不出现地图、至少出现一次纸
 * （纸来自表加载替换后的纸条目，也经过 GLM）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class LootMapFilterGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    /** roll 次数：船表两个 pool 各有权重，200 次足以稳定覆盖纸与地图替换结果。 */
    private static final int ROLLS = 200;

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = "loot_map_filter_tables")
    public static void treasureMapTablesAreStripped(GameTestHelper helper) {
        assertTableStripped(helper, BuiltInLootTables.SHIPWRECK_MAP);
        assertTableStripped(helper, BuiltInLootTables.UNDERWATER_RUIN_BIG);
        assertTableStripped(helper, BuiltInLootTables.UNDERWATER_RUIN_SMALL);
        helper.succeed();
    }

    /**
     * 运行时第二层：所有 exploration_map 目的地一律产 1 张纸（不进入结构搜索）；
     * 关开关时透传原版行为；非地图输入不受影响。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = "loot_map_filter_destinations")
    public static void everyExplorationMapDestinationYieldsPaper(GameTestHelper helper) {
        boolean saved = Config.LOOT_MAP_FILTER.get();
        try {
            LootContext context = new LootContext.Builder(lootParams(helper)).create(Optional.empty());
            Config.LOOT_MAP_FILTER.set(true);

            List<TagKey<Structure>> destinations = List.of(
                    ExplorationMapFunction.DEFAULT_DESTINATION,
                    structureTag("dungeons_arise", "explorer_maps/monastery"),
                    structureTag("dungeons_arise_seven_seas", "corsair_corvette"),
                    structureTag("nova_structures", "tavern"),
                    structureTag("minecraft", "ancient_city"),
                    structureTag("minecraft", "pillager_outpost"),
                    structureTag("minecraft", "on_woodland_explorer_maps"),
                    structureTag("minecraft", "on_ocean_explorer_maps"));
            for (TagKey<Structure> destination : destinations) {
                ExplorationMapFunction function = (ExplorationMapFunction) ExplorationMapFunction.makeExplorationMap()
                        .setDestination(destination).build();
                ItemStack result = function.run(new ItemStack(Items.MAP), context);
                helper.assertTrue(result.is(Items.PAPER) && result.getCount() == 1,
                        destination.location() + " must yield exactly one paper, got " + result);
            }

            // 成品地图输入同样短路（原版 run 会原样返回，第三层也应换纸）
            ExplorationMapFunction treasure = (ExplorationMapFunction) ExplorationMapFunction.makeExplorationMap().build();
            ItemStack filled = treasure.run(new ItemStack(Items.FILLED_MAP), context);
            helper.assertTrue(filled.is(Items.PAPER), "filled map input must become paper, got " + filled);

            // 非地图输入完全不介入
            ItemStack stone = treasure.run(new ItemStack(Items.STONE), context);
            helper.assertTrue(stone.is(Items.STONE), "non-map input must pass through, got " + stone);

            Config.LOOT_MAP_FILTER.set(false);
            TagKey<Structure> absent = structureTag("dimblend_experience", "no_such_structure_tag");
            ExplorationMapFunction passthrough = (ExplorationMapFunction) ExplorationMapFunction.makeExplorationMap()
                    .setDestination(absent).build();
            ItemStack allowed = passthrough.run(new ItemStack(Items.MAP), context);
            helper.assertTrue(allowed.is(Items.MAP),
                    "lootMapFilter=false must pass the map through when no structure is found, got " + allowed);
            helper.succeed();
        } finally {
            Config.LOOT_MAP_FILTER.set(saved);
        }
    }

    /** 第三层 + 第一层联动：反复 roll 船表，最终结果无地图、至少一张纸。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 200, batch = "loot_map_filter_rolls")
    public static void shipwreckMapRollsNeverContainMaps(GameTestHelper helper) {
        boolean saved = Config.LOOT_MAP_FILTER.get();
        try {
            Config.LOOT_MAP_FILTER.set(true);
            LootTable table = helper.getLevel().getServer().reloadableRegistries()
                    .getLootTable(BuiltInLootTables.SHIPWRECK_MAP);
            LootParams params = lootParams(helper);
            boolean sawPaper = false;
            for (int roll = 0; roll < ROLLS; roll++) {
                for (ItemStack stack : table.getRandomItems(params)) {
                    helper.assertFalse(stack.getItem() instanceof net.minecraft.world.item.MapItem,
                            "shipwreck_map roll " + roll + " must not contain a map, got " + stack);
                    if (stack.is(Items.PAPER)) {
                        sawPaper = true;
                    }
                }
            }
            helper.assertTrue(sawPaper, "shipwreck_map rolls must contain paper after replacement");
            helper.succeed();
        } finally {
            Config.LOOT_MAP_FILTER.set(saved);
        }
    }

    /**
     * 真实条目树：alternatives(paper, group(map[weight=7,quality=2,condition,exploration_map],
     * empty_map))，group 自身带 condition。重建后整棵树无地图、两种地图叶子都变纸，
     * 纸条目保留 weight/quality/condition、丢弃 functions；group 的 condition 保留。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = "loot_map_filter_composite")
    public static void rewritesRealCompositeTree(GameTestHelper helper) {
        LootPoolSingletonContainer.Builder<?> mapWithFunction = LootItem.lootTableItem(Items.MAP)
                .setWeight(7)
                .setQuality(2)
                .when(LootItemRandomChanceCondition.randomChance(0.5F))
                .apply(ExplorationMapFunction.makeExplorationMap());
        LootPoolSingletonContainer.Builder<?> emptyMap = LootItem.lootTableItem(Items.MAP);
        EntryGroup.Builder group = EntryGroup.list(mapWithFunction, emptyMap);
        group.when(LootItemRandomChanceCondition.randomChance(0.25F));
        LootPoolEntryContainer composite = AlternativesEntry.alternatives(
                LootItem.lootTableItem(Items.PAPER), group).build();

        LootPoolEntryContainer rewritten = MapLootEntryRewrite.rewriteEntry(composite);

        helper.assertFalse(MapLootEntryRewrite.containsMapEntry(rewritten),
                "rewritten tree must not contain any map entry");
        helper.assertTrue(rewritten instanceof AlternativesEntry,
                "alternatives root must stay alternatives, got " + rewritten.getClass().getName());
        List<LootPoolEntryContainer> alternativesChildren =
                ((CompositeEntryBaseAccessor) rewritten).dimblend$getChildren();
        helper.assertTrue(alternativesChildren.size() == 2, "alternatives must keep both children");
        helper.assertTrue(alternativesChildren.get(0) instanceof LootItem
                        && ((LootItemAccessor) alternativesChildren.get(0)).dimblend$getItem().value() == Items.PAPER,
                "untouched paper child must stay paper");

        LootPoolEntryContainer rebuiltGroup = alternativesChildren.get(1);
        helper.assertTrue(rebuiltGroup instanceof EntryGroup,
                "nested group must be rebuilt as a group, got " + rebuiltGroup.getClass().getName());
        helper.assertTrue(((LootPoolEntryContainerAccessor) rebuiltGroup).dimblend$getConditions().size() == 1,
                "group conditions must be preserved");
        List<LootPoolEntryContainer> groupChildren =
                ((CompositeEntryBaseAccessor) rebuiltGroup).dimblend$getChildren();
        helper.assertTrue(groupChildren.size() == 2, "group must keep both leaves");

        LootItem first = (LootItem) groupChildren.get(0);
        helper.assertTrue(((LootItemAccessor) first).dimblend$getItem().value() == Items.PAPER,
                "map leaf must become paper");
        LootPoolSingletonContainerAccessor firstFields = (LootPoolSingletonContainerAccessor) first;
        helper.assertTrue(firstFields.dimblend$getWeight() == 7,
                "weight must be preserved, got " + firstFields.dimblend$getWeight());
        helper.assertTrue(firstFields.dimblend$getQuality() == 2,
                "quality must be preserved, got " + firstFields.dimblend$getQuality());
        helper.assertTrue(firstFields.dimblend$getFunctions().isEmpty(),
                "functions must be dropped so the stack count is 1");
        helper.assertTrue(((LootPoolEntryContainerAccessor) first).dimblend$getConditions().size() == 1,
                "leaf conditions must be preserved");
        helper.assertTrue(((LootItemAccessor) groupChildren.get(1)).dimblend$getItem().value() == Items.PAPER,
                "empty map leaf must become paper too");
        helper.succeed();
    }

    /**
     * 运行时第三层：GLM 直接吃一个混合 loot list——map/filled_map 变 1 张纸，
     * 原有纸/其它物品不动；关开关原样返回。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = "loot_map_filter_glm")
    public static void globalLootModifierReplacesEveryMapStack(GameTestHelper helper) {
        boolean saved = Config.LOOT_MAP_FILTER.get();
        try {
            LootContext context = new LootContext.Builder(lootParams(helper)).create(Optional.empty());
            PaperInsteadOfMapsLootModifier modifier = new PaperInsteadOfMapsLootModifier(new LootItemCondition[0]);
            // 空地图是 EmptyMapItem（不是 MapItem 子类）：判据必须显式认 Items.MAP
            helper.assertTrue(MapLootEntryRewrite.isMapItem(Items.MAP), "empty map must count as a map");
            helper.assertTrue(MapLootEntryRewrite.isMapItem(Items.FILLED_MAP), "filled map must count as a map");
            helper.assertFalse(MapLootEntryRewrite.isMapItem(Items.PAPER), "paper must not count as a map");

            Config.LOOT_MAP_FILTER.set(true);
            ObjectArrayList<ItemStack> loot = new ObjectArrayList<>();
            loot.add(new ItemStack(Items.MAP, 3));
            loot.add(new ItemStack(Items.FILLED_MAP));
            loot.add(new ItemStack(Items.PAPER, 2));
            loot.add(new ItemStack(Items.STONE, 5));
            ObjectArrayList<ItemStack> filtered = modifier.apply(loot, context);
            helper.assertTrue(filtered.get(0).is(Items.PAPER) && filtered.get(0).getCount() == 1,
                    "map stack must become exactly one paper, got " + filtered.get(0));
            helper.assertTrue(filtered.get(1).is(Items.PAPER) && filtered.get(1).getCount() == 1,
                    "filled map stack must become exactly one paper, got " + filtered.get(1));
            helper.assertTrue(filtered.get(2).is(Items.PAPER) && filtered.get(2).getCount() == 2,
                    "existing paper stack must keep its count, got " + filtered.get(2));
            helper.assertTrue(filtered.get(3).is(Items.STONE) && filtered.get(3).getCount() == 5,
                    "non-map stack must pass through unchanged, got " + filtered.get(3));

            Config.LOOT_MAP_FILTER.set(false);
            ObjectArrayList<ItemStack> passthrough = new ObjectArrayList<>();
            passthrough.add(new ItemStack(Items.MAP));
            ObjectArrayList<ItemStack> result = modifier.apply(passthrough, context);
            helper.assertTrue(result.get(0).is(Items.MAP),
                    "lootMapFilter=false must leave maps alone, got " + result.get(0));
            helper.succeed();
        } finally {
            Config.LOOT_MAP_FILTER.set(saved);
        }
    }

    private static TagKey<Structure> structureTag(String namespace, String path) {
        return TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(namespace, path));
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
                helper.assertFalse(MapLootEntryRewrite.containsMapEntry(entry),
                        key.location() + " still contains a map entry " + entry);
                remaining++;
            }
        }
        helper.assertTrue(remaining > 0, key.location() + " must keep its non-map entries");
    }

    private LootMapFilterGameTests() {
    }
}
