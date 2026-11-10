package dimblend.experience.compat.sable;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * 按对象身份记账的「代次缓存」，供 Sable 子层级的动画贴图集合使用。
 *
 * <p>三个问题必须同时解决，这也是它存在的唯一理由：</p>
 * <ul>
 *   <li><b>资源重载</b>：{@code TextureAtlas#upload} 会整体换掉 {@code texturesByName} 里的
 *       {@code TextureAtlasSprite} 实例，旧实例连同 {@code SpriteContents}/{@code Ticker} 一起失效
 *       （见 vanilla {@code TextureAtlas#upload}、{@code #clearTextureData}）。任何按方块状态或区块
 *       缓存下来的贴图数组若不随重载作废，就会一直引用旧实例。这里用
 *       {@link #invalidate()} 整体作废：条目清空、代次 {@link #epoch()} 自增。</li>
 *   <li><b>失败读取</b>：模型/图集尚未就绪时 compute 可能抛异常或返回 null。永久缓存那个空结果
 *       会让该方块状态/区块在本次进程内再也不产生动画；所以失败只记一个重试窗口，到期自动重算，
 *       绝不「一次失败永久生效」。</li>
 *   <li><b>不完整结果</b>：区块级集合由多个方块状态拼成。若其中一个状态此刻读取失败，而其它状态
 *       成功，naive 的实现会得到一个「非空」的结果并永久缓存 —— 那个失败状态之后就<b>再也没有
 *       机会被重算</b>，它自己的重试窗口永远不会开始计时。因此 {@link #getPartial} 接收
 *       {@link Computed#complete()}：不完整结果按窗口重试，完整结果才长期有效。</li>
 * </ul>
 *
 * <p>本类不引用任何 Minecraft 类型，纯对象身份语义，可直接单测。键与 stamp 一律按<b>引用</b>比较
 * （{@code BlockState} 是驻留实例、{@code CompiledSection} 每次重建换实例），不复写 equals。</p>
 *
 * @param <K> 缓存键（按引用比较）
 * @param <V> 缓存值（通常贴图数组）
 */
public final class SableAnimationSpriteCache<K, V> {

    /** 计算回调；返回 {@code null} 视为「本次读取失败」，不当作有效值缓存。 */
    @FunctionalInterface
    public interface Compute<K, V> {
        @Nullable
        V compute(K key);
    }

    /**
     * 带完整性标记的计算回调：{@link Computed#complete()} 为 false 表示「这次拿到的是残缺结果」，
     * 只缓存一个重试窗口，到期必须重算。
     */
    @FunctionalInterface
    public interface PartialCompute<K, V> {
        @Nullable
        Computed<V> compute(K key);
    }

    /**
     * 计算结果。
     *
     * @param value    结果值
     * @param complete 是否完整；false 表示其中有部分读取失败，需要按窗口重试
     */
    public record Computed<V>(V value, boolean complete) {
        public static <V> Computed<V> complete(V value) {
            return new Computed<>(value, true);
        }

        public static <V> Computed<V> partial(V value) {
            return new Computed<>(value, false);
        }
    }

    /** 读取失败后的重试间隔（游戏刻）：等下一次图集/模型就绪，又不至于每帧重扫。 */
    public static final long DEFAULT_FAILURE_RETRY_TICKS = 20L;
    /**
     * 不完整结果的重试间隔（游戏刻，2 秒）：给里面的失败项留出自愈机会，但不至于每帧重扫 16³ 方块。
     * 只作用于「里面有状态确实读失败」的区块，正常区块是完整结果、不计时。
     */
    public static final long DEFAULT_INCOMPLETE_RETRY_TICKS = 40L;
    /** 判定「时间真的倒退了（换世界/换维度）」的最小回退量（游戏刻）。 */
    public static final long BACKWARD_JUMP_TICKS = 200L;

    private final Map<K, Entry<V>> entries = new IdentityHashMap<>();
    private final V emptyValue;
    private final long incompleteRetryTicks;
    private final long failureRetryTicks;

    private long tick;
    private long epoch;
    private long computeCount;
    private long failureCount;

    public SableAnimationSpriteCache(V emptyValue) {
        this(emptyValue, DEFAULT_INCOMPLETE_RETRY_TICKS, DEFAULT_FAILURE_RETRY_TICKS);
    }

    public SableAnimationSpriteCache(V emptyValue, long incompleteRetryTicks, long failureRetryTicks) {
        this.emptyValue = emptyValue;
        this.incompleteRetryTicks = incompleteRetryTicks;
        this.failureRetryTicks = failureRetryTicks;
    }

    /**
     * 推进游戏刻。
     *
     * <p>大幅回退（换世界/换维度后 gameTime 归零）说明旧条目的重试时刻已不可比，整体作废，
     * 否则它们会卡在遥远的未来。小幅回退只可能是不同维度的时间差异（子层级分属不同
     * {@code ClientLevel}），这时<b>不动</b>缓存：否则两维度交替时会每帧清空一次。</p>
     */
    public void advance(long gameTime) {
        if (gameTime < this.tick) {
            if (this.tick - gameTime <= BACKWARD_JUMP_TICKS) {
                return;
            }
            this.entries.clear();
            this.epoch++;
        }
        this.tick = gameTime;
    }

    /** 资源重载等「旧值整体失效」的场合：清空全部条目并推进代次。 */
    public void invalidate() {
        this.entries.clear();
        this.epoch++;
    }

    /** 只保留 live 中的键（按引用比较），用于清掉已释放的渲染区块。 */
    public void retain(Collection<K> live) {
        if (this.entries.isEmpty()) {
            return;
        }
        IdentityHashMap<K, Boolean> keep = new IdentityHashMap<>(Math.max(16, live.size() * 2));
        for (K key : live) {
            keep.put(key, Boolean.TRUE);
        }
        this.entries.keySet().removeIf(key -> !keep.containsKey(key));
    }

    /** 代次；重载一次加一，用于诊断日志里区分「哪一代的贴图」。 */
    public long epoch() {
        return this.epoch;
    }

    public int cachedCount() {
        return this.entries.size();
    }

    public long computeCount() {
        return this.computeCount;
    }

    public long failureCount() {
        return this.failureCount;
    }

    /**
     * 该键当前的条目是否「等待重试」。失败与不完整结果都为 true，完整结果与未缓存为 false。
     *
     * <p>区块级缓存的调用方用它把「里面的某个状态还没恢复」传播成区块结果的
     * {@link Computed#complete()}，否则那个状态永远不会再被重算。</p>
     */
    public boolean isPending(K key) {
        Entry<V> cached = this.entries.get(key);
        return cached != null && cached.retryAt != 0L;
    }

    /**
     * 取缓存值（无完整性信息）。{@code stamp} 是该键的「内容代次」（如区块的
     * {@code CompiledSection} 实例），引用不同即重算。
     *
     * <p>读取失败（抛异常 / 返回 null）按 {@link #DEFAULT_FAILURE_RETRY_TICKS} 重试；
     * <b>成功的空结果视为完整</b>——「这个方块确实没有动画贴图」是合法答案，若也当成待重试，
     * 每个含普通石头的区块都会每 40 tick 重扫一次 16³。</p>
     */
    public V get(K key, @Nullable Object stamp, Compute<K, V> compute) {
        return resolve(key, stamp, () -> {
            V computed = compute.compute(key);
            return computed == null ? null : Computed.complete(computed);
        });
    }

    /**
     * 取缓存值（带完整性标记）。{@code complete=false} 的结果只缓存一个
     * {@link #DEFAULT_INCOMPLETE_RETRY_TICKS} 窗口，到期重算，因此残缺集合里的失败项能恢复。
     */
    public V getPartial(K key, @Nullable Object stamp, PartialCompute<K, V> compute) {
        return resolve(key, stamp, () -> compute.compute(key));
    }

    @Nullable
    private V resolve(K key, @Nullable Object stamp, Supplier<Computed<V>> supplier) {
        Entry<V> cached = this.entries.get(key);
        if (cached != null) {
            if (cached.stamp == stamp) {
                if (cached.retryAt == 0L || this.tick < cached.retryAt) {
                    return cached.value;
                }
            }
            // stamp 变了，或重试窗口到期：丢弃后重算
            this.entries.remove(key);
        }

        this.computeCount++;
        Computed<V> computed;
        try {
            computed = supplier.get();
        } catch (RuntimeException | LinkageError failure) {
            this.failureCount++;
            this.entries.put(key, new Entry<>(this.emptyValue, stamp, this.tick + this.failureRetryTicks));
            return this.emptyValue;
        }

        if (computed == null) {
            this.failureCount++;
            this.entries.put(key, new Entry<>(this.emptyValue, stamp, this.tick + this.failureRetryTicks));
            return this.emptyValue;
        }

        V value = computed.value() == null ? this.emptyValue : computed.value();
        long retryAt = computed.complete() ? 0L : this.tick + this.incompleteRetryTicks;
        this.entries.put(key, new Entry<>(value, stamp, retryAt));
        return value;
    }

    /**
     * {@code retryAt == 0} 表示「一直有效到 {@link #invalidate()}」；非 0 表示失败/不完整结果，
     * 到期后必须重算。
     */
    private record Entry<V>(V value, @Nullable Object stamp, long retryAt) {
    }
}
