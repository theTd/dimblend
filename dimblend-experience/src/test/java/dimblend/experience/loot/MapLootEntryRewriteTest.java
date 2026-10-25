package dimblend.experience.loot;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A10 数据层：地图条目替换为纸的递归算法（纯逻辑，不启动游戏、不碰注册表）。
 *
 * <p>用假条目树覆盖 {@code minecraft:map} / {@code minecraft:filled_map} 叶子与
 * alternatives/group/sequence 任意嵌套：地图叶子换纸、非地图叶子保实例、
 * 无地图分支不重建、有地图的父链逐层重建。真实 {@code LootItem}/复合材料条目的
 * 读写与 weight/quality/conditions/functions 保留由 {@code LootMapFilterGameTests}
 * 的 GameTest 覆盖（原版物品/方块注册表在纯 JUnit 里不可用：FML LoadingModList 为空、
 * 测试运行时也没有 assets，Bootstrap 会在 FeatureFlags/Language 处失败，故不在此重复造注册表）。</p>
 */
class MapLootEntryRewriteTest {

    /** 假条目节点：item 为物品 id（非叶子节点用种类名），children 为空表示叶子。 */
    private record FakeEntry(String item, String kind, List<FakeEntry> children) {
    }

    /** 假条目树：map/filled_map 叶子换 paper 叶子，复合材料改名标记重建。 */
    private static final MapLootEntryRewrite.Tree<FakeEntry> FAKE_TREE = new MapLootEntryRewrite.Tree<>() {
        @Override
        public boolean isMapLeaf(FakeEntry entry) {
            return ("minecraft:map".equals(entry.item()) || "minecraft:filled_map".equals(entry.item()))
                    && entry.children().isEmpty();
        }

        @Override
        public FakeEntry replaceLeaf(FakeEntry entry) {
            return new FakeEntry("minecraft:paper", entry.kind(), List.of());
        }

        @Override
        public List<FakeEntry> children(FakeEntry entry) {
            return entry.children().isEmpty() ? null : entry.children();
        }

        @Override
        public FakeEntry rebuild(FakeEntry entry, List<FakeEntry> children) {
            return new FakeEntry(entry.item(), "rebuilt-" + entry.kind(), List.copyOf(children));
        }
    };

    private static FakeEntry leaf(String item) {
        return new FakeEntry(item, "leaf", List.of());
    }

    private static boolean containsMap(FakeEntry entry) {
        if ("minecraft:map".equals(entry.item()) || "minecraft:filled_map".equals(entry.item())) {
            return true;
        }
        for (FakeEntry child : entry.children()) {
            if (containsMap(child)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void mapLeafBecomesPaper() {
        FakeEntry result = MapLootEntryRewrite.rewrite(leaf("minecraft:map"), FAKE_TREE);
        assertEquals("minecraft:paper", result.item());
    }

    @Test
    void filledMapLeafBecomesPaper() {
        FakeEntry result = MapLootEntryRewrite.rewrite(leaf("minecraft:filled_map"), FAKE_TREE);
        assertEquals("minecraft:paper", result.item());
    }

    @Test
    void nonMapLeafIsUntouched() {
        FakeEntry stick = leaf("minecraft:stick");
        assertSame(stick, MapLootEntryRewrite.rewrite(stick, FAKE_TREE));
    }

    @Test
    void compositeWithoutMapsIsNotRebuilt() {
        FakeEntry group = new FakeEntry("group", "group",
                List.of(leaf("minecraft:paper"), leaf("minecraft:stick")));
        assertSame(group, MapLootEntryRewrite.rewrite(group, FAKE_TREE));
    }

    @Test
    void nestedCompositesAreRebuiltRecursively() {
        FakeEntry paper = leaf("minecraft:paper");
        FakeEntry sequence = new FakeEntry("sequence", "sequence",
                List.of(leaf("minecraft:map"), paper));
        FakeEntry group = new FakeEntry("group", "group",
                List.of(sequence, leaf("minecraft:filled_map")));
        FakeEntry alternatives = new FakeEntry("alternatives", "alternatives",
                List.of(paper, group));

        FakeEntry result = MapLootEntryRewrite.rewrite(alternatives, FAKE_TREE);

        assertFalse(containsMap(result), "all nested map leaves must be replaced");
        assertEquals("rebuilt-alternatives", result.kind());
        FakeEntry rebuiltGroup = result.children().get(1);
        assertEquals("rebuilt-group", rebuiltGroup.kind());
        FakeEntry rebuiltSequence = rebuiltGroup.children().get(0);
        assertEquals("rebuilt-sequence", rebuiltSequence.kind());
        assertEquals("minecraft:paper", rebuiltSequence.children().get(0).item());
        assertSame(paper, rebuiltSequence.children().get(1), "unchanged paper leaf keeps identity");
        assertEquals("minecraft:paper", rebuiltGroup.children().get(1).item());
        // 原本就是纸的分支不重建，保持实例
        assertSame(paper, result.children().get(0));
    }

    @Test
    void rebuildFailureFallsBackToOriginalEntry() {
        // 未知复合材料：树操作返回 null 表示无法重建，算法必须保留原条目而不是丢子树
        MapLootEntryRewrite.Tree<FakeEntry> unsupported = new MapLootEntryRewrite.Tree<>() {
            @Override
            public boolean isMapLeaf(FakeEntry entry) {
                return "minecraft:map".equals(entry.item());
            }

            @Override
            public FakeEntry replaceLeaf(FakeEntry entry) {
                return new FakeEntry("minecraft:paper", entry.kind(), List.of());
            }

            @Override
            public List<FakeEntry> children(FakeEntry entry) {
                return entry.children();
            }

            @Override
            public FakeEntry rebuild(FakeEntry entry, List<FakeEntry> children) {
                return null;
            }
        };
        FakeEntry custom = new FakeEntry("custom", "custom", List.of(leaf("minecraft:map")));
        assertSame(custom, MapLootEntryRewrite.rewrite(custom, unsupported));
    }
}
