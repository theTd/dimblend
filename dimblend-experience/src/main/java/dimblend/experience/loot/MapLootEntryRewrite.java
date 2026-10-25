package dimblend.experience.loot;

import java.util.ArrayList;
import java.util.List;

import dimblend.experience.mixin.CompositeEntryBaseAccessor;
import dimblend.experience.mixin.LootItemAccessor;
import dimblend.experience.mixin.LootPoolEntryContainerAccessor;
import dimblend.experience.mixin.LootPoolSingletonContainerAccessor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.EntryGroup;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.SequentialEntry;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * A10 战利品地图过滤的数据层：把战利品表里的地图条目<b>替换</b>成纸条目（不是移除）。
 *
 * <p>判据只认物品：{@code minecraft:map}、{@code minecraft:filled_map} 以及任何
 * {@link MapItem} 子类，无论有没有挂 {@code exploration_map} 函数、目的地是哪个结构
 * tag，一律替换。替换保留原条目的 weight / quality / conditions（含复合条目自身的
 * conditions），<b>丢弃全部 functions</b>——因此产出恒为 1 张纸（原版 {@code LootItem}
 * 的默认 count=1），不会继承 {@code set_count} 等函数，也不会再触发 exploration_map
 * 的结构搜索。</p>
 *
 * <p>复合条目（alternatives / group / sequence）递归处理：子条目里有地图才重建父条目
 * （保留父条目 conditions）；{@link CompositeEntryBase} 在构造时把 children 固化成
 * {@code composedChildren}（1.21.1 源码 13/19 行），不能原地改 children，故用公开
 * Builder 重建。叶子同理：{@code LootPoolSingletonContainer} 的 functions 与
 * {@code compositeFunction} 在构造时定型（25/36-42 行），改 functions 字段不会影响
 * 已缓存的函数链，所以纸条目必须重建而不是原地改字段。
 * 模组自定义的其它 {@code CompositeEntryBase} 子类没有公开重建入口，保持原样——
 * 运行时 {@code ExplorationMapFunctionBanMixin} 短路 + 全局战利品修饰器仍保证不产地图
 * （只是 JEI/JER 展示层可能仍显示表内条目）。</p>
 *
 * <p>纯递归算法经 {@link Tree} 抽象，单测可用假条目树覆盖嵌套；真实条目的读写走
 * mixin accessor（GameTest 覆盖）。</p>
 */
public final class MapLootEntryRewrite {

    /**
     * 条目树操作抽象（生产实现见 {@link #LOOT_TREE}；单测用假节点覆盖嵌套递归）。
     * {@link #children} 返回 null 表示叶子/不支持递归；{@link #rebuild} 返回 null
     * 表示无法重建（保持原条目）。
     */
    public interface Tree<E> {
        boolean isMapLeaf(E entry);

        E replaceLeaf(E entry);

        List<E> children(E entry);

        E rebuild(E entry, List<E> children);
    }

    /** 生产条目树：LootItem 叶子 + 原版三种复合材料。 */
    private static final Tree<LootPoolEntryContainer> LOOT_TREE = new Tree<>() {
        @Override
        public boolean isMapLeaf(LootPoolEntryContainer entry) {
            return entry instanceof LootItem item
                    && isMapItem(((LootItemAccessor) item).dimblend$getItem().value());
        }

        @Override
        public LootPoolEntryContainer replaceLeaf(LootPoolEntryContainer entry) {
            LootPoolSingletonContainerAccessor singleton = (LootPoolSingletonContainerAccessor) entry;
            return paperEntry(singleton.dimblend$getWeight(), singleton.dimblend$getQuality(),
                    ((LootPoolEntryContainerAccessor) entry).dimblend$getConditions());
        }

        @Override
        public List<LootPoolEntryContainer> children(LootPoolEntryContainer entry) {
            return entry instanceof CompositeEntryBase composite
                    ? ((CompositeEntryBaseAccessor) composite).dimblend$getChildren()
                    : null;
        }

        @Override
        public LootPoolEntryContainer rebuild(LootPoolEntryContainer entry, List<LootPoolEntryContainer> children) {
            return rebuildComposite(entry, children);
        }
    };

    /**
     * 递归改写：地图叶子换纸；复合材料仅在子树确有替换时重建。
     * 返回同一实例表示未改动；调用方可据此跳过整体列表换引用。
     */
    public static <E> E rewrite(E entry, Tree<E> tree) {
        if (tree.isMapLeaf(entry)) {
            return tree.replaceLeaf(entry);
        }
        List<E> children = tree.children(entry);
        if (children == null || children.isEmpty()) {
            return entry;
        }
        List<E> rewritten = null;
        for (int i = 0; i < children.size(); i++) {
            E child = children.get(i);
            E next = rewrite(child, tree);
            if (next != child) {
                if (rewritten == null) {
                    rewritten = new ArrayList<>(children);
                }
                rewritten.set(i, next);
            }
        }
        if (rewritten == null) {
            return entry;
        }
        E rebuilt = tree.rebuild(entry, List.copyOf(rewritten));
        return rebuilt == null ? entry : rebuilt;
    }

    /** 表加载路径入口：把单个 pool 条目树里的地图条目全部换成纸。 */
    public static LootPoolEntryContainer rewriteEntry(LootPoolEntryContainer entry) {
        return rewrite(entry, LOOT_TREE);
    }

    /** 递归判断条目树里是否还有地图条目（GameTest 断言 / 日志用）。 */
    public static boolean containsMapEntry(LootPoolEntryContainer entry) {
        if (entry instanceof LootItem item
                && isMapItem(((LootItemAccessor) item).dimblend$getItem().value())) {
            return true;
        }
        if (entry instanceof CompositeEntryBase composite) {
            for (LootPoolEntryContainer child : ((CompositeEntryBaseAccessor) composite).dimblend$getChildren()) {
                if (containsMapEntry(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 地图物品判据：{@code minecraft:map}（原版 {@code EmptyMapItem}，<b>不是</b>
     * {@link MapItem} 子类：1.21.1 {@code EmptyMapItem.java} 10 行 {@code extends ComplexItem}）
     * 与 {@code minecraft:filled_map}（{@code MapItem.java} 38 行 {@code extends ComplexItem}）
     * 以及任何 {@link MapItem} 子类。只看 {@code instanceof MapItem} 会漏掉空地图。
     */
    public static boolean isMapItem(Item item) {
        return item == Items.MAP || item == Items.FILLED_MAP || item instanceof MapItem;
    }

    /**
     * 纸条目构造（纯函数，单测可达）：保留 weight/quality/conditions，不挂任何 functions。
     * {@code LootItem} 构造器私有，公开 Builder 是唯一入口；conditions 用
     * {@code when(() -> condition)} 原样回填（Builder 是函数式接口）。
     */
    static LootPoolEntryContainer paperEntry(int weight, int quality, List<LootItemCondition> conditions) {
        LootPoolSingletonContainer.Builder<?> builder = LootItem.lootTableItem(Items.PAPER);
        builder.setWeight(weight);
        builder.setQuality(quality);
        applyConditions(builder, conditions);
        return builder.build();
    }

    /** 用公开 Builder 重建原版复合材料；未知子类返回 null（调用方保持原条目）。 */
    private static LootPoolEntryContainer rebuildComposite(LootPoolEntryContainer entry,
            List<LootPoolEntryContainer> children) {
        List<LootItemCondition> conditions = ((LootPoolEntryContainerAccessor) entry).dimblend$getConditions();
        // 只重建原版三个精确类；模组子类可能带自定义 codec/行为，转成原版类反而破坏语义
        if (entry.getClass() == AlternativesEntry.class) {
            AlternativesEntry.Builder builder = AlternativesEntry.alternatives();
            applyConditions(builder, conditions);
            for (LootPoolEntryContainer child : children) {
                builder.otherwise(new PrebuiltEntryBuilder(child));
            }
            return builder.build();
        }
        if (entry.getClass() == EntryGroup.class) {
            EntryGroup.Builder builder = EntryGroup.list();
            applyConditions(builder, conditions);
            for (LootPoolEntryContainer child : children) {
                builder.append(new PrebuiltEntryBuilder(child));
            }
            return builder.build();
        }
        if (entry.getClass() == SequentialEntry.class) {
            SequentialEntry.Builder builder = SequentialEntry.sequential();
            applyConditions(builder, conditions);
            for (LootPoolEntryContainer child : children) {
                builder.then(new PrebuiltEntryBuilder(child));
            }
            return builder.build();
        }
        return null;
    }

    private static void applyConditions(LootPoolEntryContainer.Builder<?> builder, List<LootItemCondition> conditions) {
        for (LootItemCondition condition : conditions) {
            builder.when(() -> condition);
        }
    }

    /** 把已建好的子条目重新包成 Builder，供原版复合材料 Builder 组装。 */
    private static final class PrebuiltEntryBuilder extends LootPoolEntryContainer.Builder<PrebuiltEntryBuilder> {
        private final LootPoolEntryContainer entry;

        private PrebuiltEntryBuilder(LootPoolEntryContainer entry) {
            this.entry = entry;
        }

        @Override
        protected PrebuiltEntryBuilder getThis() {
            return this;
        }

        @Override
        public LootPoolEntryContainer build() {
            return this.entry;
        }
    }

    private MapLootEntryRewrite() {
    }
}
