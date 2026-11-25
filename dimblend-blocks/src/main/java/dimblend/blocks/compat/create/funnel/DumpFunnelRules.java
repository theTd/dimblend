package dimblend.blocks.compat.create.funnel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import net.minecraft.world.item.ItemStack;

/**
 * 卸料漏斗的纯规则（无 {@code Level}、无注册表访问，可单测）。
 *
 * <p><b>锁定判定 #17</b>：阈值按「容器内<b>同一物品</b>（{@code Item + DataComponents}）
 * 的<b>总数</b>算——不是按槽位、不是按全容器总量。每个候选物品各自比较。</p>
 *
 * <p><b>锁定判定 #18</b>：阈值单位是<b>组（stacks）</b>，范围 {@value #MIN_STACKS}..{@value #MAX_STACKS}，
 * 默认 {@value #DEFAULT_STACKS} 组，里程碑间隔 {@value #MILESTONE_INTERVAL}；小于 1 一律夹到 1
 * （永不允许 0 组，否则就是"无条件抽干容器"，与"卸料漏斗"语义相反）。</p>
 *
 * <p>触发后永远只抽 <b>{@value #EXTRACT_AMOUNT}</b> 个物品（{@code EXACTLY}），
 * 一件一件地卸，让下游传送带/漏斗自己排队。</p>
 */
public final class DumpFunnelRules {

    /** 阈值下限（组）。 */
    public static final int MIN_STACKS = 1;
    /** 阈值上限（组）。 */
    public static final int MAX_STACKS = 64;
    /** 默认阈值（组）。 */
    public static final int DEFAULT_STACKS = 1;
    /** 滑条里程碑间隔（组）。 */
    public static final int MILESTONE_INTERVAL = 16;
    /** 每次触发抽取的物品个数。 */
    public static final int EXTRACT_AMOUNT = 1;

    /**
     * 一种物品在容器里的汇总（{@code sample} 只保留 1 个，用于
     * {@link ItemStack#isSameItemSameComponents} 比对）。
     */
    public record ItemCount(ItemStack sample, long total) {
    }

    /** 泛型无注册表汇总条目，与 {@link ItemCount} 共用同一套线性表合并/查找算法。 */
    public record BucketCount<T>(T sample, long total) {
    }

    /** 阈值夹取：{@code <1} 一律 1，{@code >64} 一律 64。 */
    public static int clampStacks(int stacks) {
        if (stacks < MIN_STACKS) {
            return MIN_STACKS;
        }
        return Math.min(stacks, MAX_STACKS);
    }

    /**
     * 某个候选物品的阈值<b>物品数</b>：{@code configuredStacks * maxStackSize}。
     *
     * <p>用 {@code long} 防溢出（64 组 × 64 个 = 4096，留足余量）。</p>
     */
    public static long thresholdItems(int stacks, int maxStackSize) {
        int clamped = clampStacks(stacks);
        int perStack = maxStackSize <= 0 ? 1 : maxStackSize;
        return (long) clamped * (long) perStack;
    }

    /**
     * 是否应该卸料：容器内该物品总数<b>严格大于</b>阈值（等于阈值时停手，保证"剩 N 组不动"）。
     */
    public static boolean shouldDump(long countOfItem, int stacks, int maxStackSize) {
        if (countOfItem <= 0L) {
            return false;
        }
        return countOfItem > thresholdItems(stacks, maxStackSize);
    }

    /**
     * 按 {@code Item + DataComponents} 汇总一组成物品栈。
     *
     * <p>刻意不用 {@code HashMap<ItemStack, Long>}：{@code ItemStack} 的
     * {@code equals/hashCode} 与 {@code isSameItemSameComponents} 不是同一口径，
     * 用错会在组件差异上静默合并。这里改成小规模线性表（不同物品数通常 ≤ 槽位数），
     * 查找用官方比对方法。</p>
     */
    public static List<ItemCount> aggregate(Iterable<ItemStack> stacks) {
        List<ItemCount> counts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            merge(counts, stack);
        }
        return counts;
    }

    /** 把单个物品栈并入汇总表（不存在则新建条目）。 */
    public static void merge(List<ItemCount> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < counts.size(); i++) {
            ItemCount entry = counts.get(i);
            if (ItemStack.isSameItemSameComponents(entry.sample(), stack)) {
                counts.set(i, new ItemCount(entry.sample(), entry.total() + stack.getCount()));
                return;
            }
        }
        counts.add(new ItemCount(stack.copyWithCount(1), stack.getCount()));
    }

    /** 汇总表里某物品的总件数；没有则为 0。 */
    public static long totalOf(List<ItemCount> counts, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return 0L;
        }
        for (ItemCount entry : counts) {
            if (ItemStack.isSameItemSameComponents(entry.sample(), candidate)) {
                return entry.total();
            }
        }
        return 0L;
    }

    /** 泛型线性表汇总（与 {@link #aggregate} 同口径，供无 MC 注册表环境的纯单测直接覆盖合并与分桶语义）。 */
    public static <T> List<BucketCount<T>> aggregateBuckets(
            Iterable<T> entries,
            Predicate<T> isEmpty,
            BiPredicate<T, T> sameKey,
            ToIntFunction<T> countOf,
            UnaryOperator<T> normalizeSample) {
        List<BucketCount<T>> counts = new ArrayList<>();
        for (T item : entries) {
            if (item == null || isEmpty.test(item)) {
                continue;
            }
            int count = countOf.applyAsInt(item);
            if (count <= 0) {
                continue;
            }
            boolean merged = false;
            for (int i = 0; i < counts.size(); i++) {
                BucketCount<T> entry = counts.get(i);
                if (sameKey.test(entry.sample(), item)) {
                    counts.set(i, new BucketCount<>(entry.sample(), entry.total() + count));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                counts.add(new BucketCount<>(normalizeSample.apply(item), count));
            }
        }
        return counts;
    }

    /** 泛型汇总表按同键查总数。 */
    public static <T> long totalOfBucket(
            List<BucketCount<T>> counts,
            T candidate,
            Predicate<T> isEmpty,
            BiPredicate<T, T> sameKey) {
        if (candidate == null || isEmpty.test(candidate)) {
            return 0L;
        }
        for (BucketCount<T> entry : counts) {
            if (sameKey.test(entry.sample(), candidate)) {
                return entry.total();
            }
        }
        return 0L;
    }

    private DumpFunnelRules() {
    }
}
