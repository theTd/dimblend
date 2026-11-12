package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 代次缓存的行为测试（{@link SableAnimationSpriteCache}）。
 *
 * <p>针对的是已确认的「动画贴图资源重载失效」缺陷的两个可测不变量：
 * 重载后必须丢掉旧贴图数组；读取失败/空结果不能永久生效。测试用递增的数组实例代表
 * 「每次重算都取到一张新贴图」，因此「是否发生了重算」直接被观测到，不必镜像实现细节。</p>
 */
class SableAnimationSpriteCacheTest {

    private static final int[] EMPTY = new int[0];

    /** 每次 compute 返回一个新实例并记录调用次数。 */
    private static final class Source implements SableAnimationSpriteCache.Compute<Object, int[]> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public int[] compute(Object key) {
            this.calls.incrementAndGet();
            return new int[] {this.calls.get()};
        }
    }

    @Test
    void reusesValueWhileStampUnchanged() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object key = new Object();
        Object stamp = new Object();

        int[] first = cache.get(key, stamp, source);
        int[] second = cache.get(key, stamp, source);

        assertSame(first, second, "同一 stamp 下不应重算");
        assertEquals(1, source.calls.get());
    }

    @Test
    void reusesUntilInvalidatedButNeverAfterReload() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object key = new Object();
        Object stamp = new Object();

        int[] beforeReload = cache.get(key, stamp, source);
        long epochBefore = cache.epoch();
        cache.invalidate();
        int[] afterReload = cache.get(key, stamp, source);

        assertNotSame(beforeReload, afterReload, "重载后必须丢掉旧贴图数组，即使区块/状态身份没变");
        assertEquals(2, source.calls.get());
        assertEquals(epochBefore + 1, cache.epoch(), "重载必须推进代次");
    }

    @Test
    void failedComputeIsNotCachedPermanently() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY, 200L, 20L);
        AtomicInteger calls = new AtomicInteger();
        Object key = new Object();

        // 前两次读取失败（模型/图集尚未就绪），之后成功。
        int[] first = cache.get(key, key, k -> {
            calls.incrementAndGet();
            throw new IllegalStateException("图集尚未就绪");
        });
        assertSame(EMPTY, first);
        assertEquals(1, cache.failureCount());

        cache.advance(10L);
        int[] duringWindow = cache.get(key, key, k -> new int[] {calls.incrementAndGet()});
        assertSame(EMPTY, duringWindow, "失败后的短窗口内不再重扫");
        assertEquals(1, calls.get());

        // 过了失败重试窗口就必须重新尝试，而不是把空集合永久缓存。
        cache.advance(20L);
        int[] retried = cache.get(key, key, k -> new int[] {calls.incrementAndGet()});
        assertEquals(1, retried.length);
        assertEquals(2, calls.get());
    }

    @Test
    void nullResultCountsAsFailure() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY, 200L, 20L);
        Object key = new Object();
        assertSame(EMPTY, cache.get(key, key, k -> null));
        assertEquals(1, cache.failureCount());

        cache.advance(20L);
        int[] recovered = cache.get(key, key, k -> new int[] {7});
        assertEquals(1, recovered.length);
        assertEquals(7, recovered[0]);
    }

    @Test
    void completeEmptyResultIsCachedForGood() {
        // 「这个方块确实没有动画贴图」是完整答案：不能当成待重试，否则每个普通石头区块都会定期重扫。
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY, 200L, 20L);
        AtomicInteger calls = new AtomicInteger();
        Object key = new Object();

        assertSame(EMPTY, cache.getPartial(key, key, k -> {
            calls.incrementAndGet();
            return SableAnimationSpriteCache.Computed.complete(EMPTY);
        }));
        assertFalse(cache.isPending(key), "完整结果不该处于待重试状态");

        cache.advance(10_000L);
        assertSame(EMPTY, cache.getPartial(key, key, k -> {
            calls.incrementAndGet();
            return SableAnimationSpriteCache.Computed.complete(EMPTY);
        }));
        assertEquals(1, calls.get(), "完整空结果不重算");
    }

    @Test
    void incompleteSectionRecoversAFailedStateAfterWindow() {
        // 复现双层缓存的失败传播：一个状态成功、另一个暂时失败，区块整体非空。
        // 若区块结果被当成完整缓存，失败状态就再也不会被 get，它自己的重试窗口永不生效。
        // 用默认窗口（失败 20 tick / 不完整 40 tick），测的就是线上那组参数。
        SableAnimationSpriteCache<Object, int[]> states = new SableAnimationSpriteCache<>(EMPTY);
        SableAnimationSpriteCache<Object, int[]> sections = new SableAnimationSpriteCache<>(EMPTY);
        Object goodState = new Object();
        Object flakyState = new Object();
        Object section = new Object();

        states.getPartial(goodState, goodState, k -> SableAnimationSpriteCache.Computed.complete(new int[] {1}));
        states.getPartial(flakyState, flakyState, k -> null); // 这一刻模型没就绪
        assertTrue(states.isPending(flakyState));

        AtomicInteger sectionComputes = new AtomicInteger();
        java.util.function.Supplier<SableAnimationSpriteCache.Computed<int[]>> collect = () -> {
            sectionComputes.incrementAndGet();
            int[] fromGood = states.getPartial(goodState, goodState,
                    k -> SableAnimationSpriteCache.Computed.complete(new int[] {1}));
            int[] fromFlaky = states.getPartial(flakyState, flakyState,
                    k -> SableAnimationSpriteCache.Computed.complete(new int[] {2}));
            java.util.LinkedHashSet<Integer> merged = new java.util.LinkedHashSet<>();
            for (int value : fromGood) merged.add(value);
            for (int value : fromFlaky) merged.add(value);
            int[] mergedArray = merged.stream().mapToInt(Integer::intValue).toArray();
            boolean complete = !states.isPending(goodState) && !states.isPending(flakyState);
            return complete
                    ? SableAnimationSpriteCache.Computed.complete(mergedArray)
                    : SableAnimationSpriteCache.Computed.partial(mergedArray);
        };

        // 第一次：区块非空（只有成功状态的那张），但必须标成不完整。
        int[] first = sections.getPartial(section, section, k -> collect.get());
        assertEquals(1, first.length, "这一刻区块拿到的确是残缺集合");
        assertTrue(sections.isPending(section), "残缺集合不得被永久缓存");

        // 不完整窗口内区块仍复用旧集合（不每帧重扫）。
        states.advance(20L);
        sections.advance(20L);
        int[] cached = sections.getPartial(section, section, k -> collect.get());
        assertEquals(1, cached.length, "不完整窗口内不重扫");
        assertEquals(1, sectionComputes.get());

        // 不完整窗口（40 tick）到期：失败状态已被重算并恢复，区块集合随之补全。
        states.advance(40L);
        sections.advance(40L);
        int[] second = sections.getPartial(section, section, k -> collect.get());
        assertEquals(2, second.length, "失败状态恢复后必须体现在集合里");
        assertEquals(2, sectionComputes.get());

        // 恢复后两个状态都不再 pending，区块结果转为完整。
        assertFalse(states.isPending(flakyState));
        assertFalse(sections.isPending(section), "全部恢复后区块结果才会长期缓存");
    }

    @Test
    void stampChangeForcesRecompute() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object key = new Object();
        Object firstStamp = new Object();
        Object secondStamp = new Object();

        int[] first = cache.get(key, firstStamp, source);
        int[] second = cache.get(key, secondStamp, source);

        assertNotSame(first, second);
        assertEquals(2, source.calls.get());
    }

    @Test
    void retainDropsReleasedSectionsByIdentity() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object live = new Object();
        Object released = new Object();

        cache.get(live, live, source);
        cache.get(released, released, source);
        assertEquals(2, cache.cachedCount());

        cache.retain(List.of(live));
        assertEquals(1, cache.cachedCount());

        // 被清掉的键下次会重算；仍在的键不会。
        int callsBefore = source.calls.get();
        cache.get(live, live, source);
        assertEquals(callsBefore, source.calls.get());
        cache.get(released, released, source);
        assertEquals(callsBefore + 1, source.calls.get());
    }

    @Test
    void backwardsGameTimeInvalidatesStaleRetryDeadlines() {
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY, 200L, 20L);
        Object key = new Object();
        cache.get(key, key, k -> new int[] {1});
        cache.advance(5_000L);

        // 换世界后 gameTime 归零：旧条目不能靠「未来的重试时刻」赖着不走。
        cache.advance(0L);
        assertEquals(0, cache.cachedCount());
    }

    @Test
    void smallBackwardJitterBetweenDimensionsKeepsCache() {
        // 子层级可能分属不同 ClientLevel，gameTime 有小幅差异；这种抖动不得触发清空。
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY, 200L, 20L);
        Source source = new Source();
        Object key = new Object();
        int[] value = cache.get(key, key, source);

        cache.advance(500L);
        cache.advance(420L); // 回退 80 tick，仍在容忍窗口内

        assertSame(value, cache.get(key, key, source));
        assertEquals(1, source.calls.get());
        assertEquals(0, cache.epoch());
    }

    @Test
    void singleBlockEntryIsDroppedWhenTheResourceGenerationAdvances() {
        // 单方块子层级（VanillaSingleSubLevelRenderData）只有一个方块，缓存里最多一条：
        // 键是方块位置、stamp 是 BlockState 实例。资源重载时代次推进必须把这一条也作废，
        // 否则重载后仍会去标记已经 close 的旧 TextureAtlasSprite 实例。
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object pos = new Object();
        Object state = new Object();

        int[] beforeReload = cache.getPartial(pos, state,
                k -> SableAnimationSpriteCache.Computed.complete(source.compute(k)));
        assertSame(beforeReload, cache.getPartial(pos, state,
                k -> SableAnimationSpriteCache.Computed.complete(source.compute(k))), "同一位置+状态不应重算");
        assertEquals(1, cache.cachedCount(), "单方块缓存只留一条");

        long epochBefore = cache.epoch();
        cache.invalidate(); // 探针贴图实例变了 => 代次推进

        int[] afterReload = cache.getPartial(pos, state,
                k -> SableAnimationSpriteCache.Computed.complete(source.compute(k)));
        assertNotSame(beforeReload, afterReload, "重载后单方块条目必须重算");
        assertEquals(2, source.calls.get());
        assertEquals(1, cache.cachedCount(), "重算后仍然只有一条");
        assertEquals(epochBefore + 1, cache.epoch(), "代次必须可观测地推进");
    }

    @Test
    void singleBlockEntryIsRecomputedWhenTheBlockOrStateChanges() {
        // 方块位置换实例（plot 移动）或方块状态换实例（拆/放方块）都必须立即重算，
        // 且旧位置条目要被 retain 清掉——否则单方块载具会在缓存里堆历史位置。
        SableAnimationSpriteCache<Object, int[]> cache = new SableAnimationSpriteCache<>(EMPTY);
        Source source = new Source();
        Object pos = new Object();
        Object otherPos = new Object();
        Object state = new Object();
        Object otherState = new Object();

        int[] first = cache.get(pos, state, source);
        assertEquals(1, cache.cachedCount());

        int[] afterStateChange = cache.get(pos, otherState, source);
        assertNotSame(first, afterStateChange, "状态换实例必须重算");
        assertEquals(1, cache.cachedCount(), "同一位置只保留一条");

        int[] afterMove = cache.get(otherPos, otherState, source);
        assertNotSame(afterStateChange, afterMove, "位置变了必须重算");
        assertEquals(2, cache.cachedCount(), "移动后新旧位置各一条，等待 retain 清理");

        cache.retain(List.of(otherPos));
        assertEquals(1, cache.cachedCount(), "retain 只留当前方块位置");
        assertEquals(3, source.calls.get(), "位置+状态各变一次，共算三次");
    }
}
