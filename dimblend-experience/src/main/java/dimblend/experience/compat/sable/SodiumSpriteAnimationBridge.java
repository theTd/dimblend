package dimblend.experience.compat.sable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sodium 0.8 的「动画贴图活跃标记」API 桥。
 *
 * <p>Sodium 的 {@code performance.animate_only_visible_textures} 只会推进被
 * {@code net.caffeinemc.mods.sodium.api.texture.SpriteUtil#markSpriteActive} 标记过的贴图；
 * 官方 API 类在 Sodium 的 jarjar 内层 jar 里，公开 Maven 只有 loader 外壳，因此这里用
 * {@link MethodHandle} 软链接（与 TerraBlender 兼容层的 {@code Class.forName} 同型）：
 * Sodium 缺席或 API 变更时降级为「不标记」，绝不影响渲染主流程。</p>
 *
 * <p>调用失败不是「每帧刷屏」的理由：首次记录一条 WARN，之后按 {@link #FAILURE_LOG_INTERVAL_MS}
 * 合并计数，避免贴图 tick 里每张贴图每刻都打一行。Sodium 缺席时 {@link #isAvailable()} 直接为
 * false，整条补标记路径不进入（原版渲染器本就不冻结动画）。</p>
 */
public final class SodiumSpriteAnimationBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend_experience/sodium-sprite-animation");
    /** 失败日志的最小间隔：首次立即记录，之后同一条合并计数。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

    private static final MethodHandle MARK_ACTIVE;
    private static final MethodHandle HAS_ANIMATION;
    /**
     * Sodium 内部接口 {@code SpriteContentsExtension#sodium$isActive()} 的回读句柄。
     * 只在诊断模式用：用来区分「反射调用没抛异常」与「贴图真的被标成活跃」——
     * {@code markSpriteActive} 只是把 {@code SpriteContents} 的标记位置 true，回读才是证据。
     */
    private static final MethodHandle IS_ACTIVE;

    private static final AtomicLong MARK_FAILURES = new AtomicLong();
    private static final AtomicLong QUERY_FAILURES = new AtomicLong();
    private static final AtomicLong NEXT_FAILURE_LOG = new AtomicLong();

    static {
        MethodHandle markActive = null;
        MethodHandle hasAnimation = null;
        MethodHandle isActive = null;
        try {
            Class<?> api = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil");
            Object instance = api.getField("INSTANCE").get(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            markActive = lookup
                    .findVirtual(api, "markSpriteActive", MethodType.methodType(void.class, TextureAtlasSprite.class))
                    .bindTo(instance);
            hasAnimation = lookup
                    .findVirtual(api, "hasAnimation", MethodType.methodType(boolean.class, TextureAtlasSprite.class))
                    .bindTo(instance);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            // 只记录一次：Sodium 缺席是正常情况（软兼容），不是每局都要刷的故障。
            LOGGER.info("Sodium 动画贴图 API 不可用，Sable 子层级上的贴图动画保持原版行为: {}",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        try {
            // 回读句柄是诊断专用，缺席不影响补标记主路径。
            Class<?> extension = Class.forName("net.caffeinemc.mods.sodium.client.render.texture.SpriteContentsExtension");
            isActive = MethodHandles.publicLookup()
                    .findVirtual(extension, "sodium$isActive", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            isActive = null;
        }
        MARK_ACTIVE = markActive;
        HAS_ANIMATION = hasAnimation;
        IS_ACTIVE = isActive;
    }

    /** Sodium 的动画贴图 API 是否可用；缺席时整条补标记路径不工作（原版动画本就正常）。 */
    public static boolean isAvailable() {
        return MARK_ACTIVE != null && HAS_ANIMATION != null;
    }

    /** 贴图是否带动画（Sodium 的判定，与它自己的活跃标记集合一致）。 */
    public static boolean hasAnimation(TextureAtlasSprite sprite) {
        if (HAS_ANIMATION == null) {
            return false;
        }
        try {
            return (boolean) HAS_ANIMATION.invokeExact(sprite);
        } catch (Throwable throwable) {
            // 单张贴图判定失败不应打断渲染，当作无动画处理；日志限频。
            recordFailure(QUERY_FAILURES, "hasAnimation", throwable);
            return false;
        }
    }

    /** 让这张贴图在当前刻保持活跃；Sodium 会在下一次贴图 tick 末尾清回 false。 */
    public static boolean markActive(TextureAtlasSprite sprite) {
        if (MARK_ACTIVE == null) {
            return false;
        }
        try {
            MARK_ACTIVE.invokeExact(sprite);
            return true;
        } catch (Throwable throwable) {
            // 标记失败只意味着该贴图此刻不推进，不能影响渲染；日志限频。
            recordFailure(MARK_FAILURES, "markSpriteActive", throwable);
            return false;
        }
    }

    /**
     * 回读 Sodium 的活跃标记（诊断专用）。
     *
     * @return {@code TRUE}/{@code FALSE} 是真实回读值；{@code null} 表示 Sodium 内部接口不可用，
     *         <b>不是</b> false——不可回读不能被当成「没被标记」。
     */
    public static Boolean readActive(TextureAtlasSprite sprite) {
        if (IS_ACTIVE == null || sprite == null) {
            return null;
        }
        try {
            return (boolean) IS_ACTIVE.invokeExact(sprite.contents());
        } catch (Throwable throwable) {
            recordFailure(QUERY_FAILURES, "sodium$isActive", throwable);
            return null;
        }
    }

    /** 累计失败次数；诊断日志用，不参与任何判定。 */
    public static long markFailureCount() {
        return MARK_FAILURES.get();
    }

    public static long queryFailureCount() {
        return QUERY_FAILURES.get();
    }

    private static void recordFailure(AtomicLong counter, String method, Throwable throwable) {
        long total = counter.incrementAndGet();
        long now = System.currentTimeMillis();
        long next = NEXT_FAILURE_LOG.get();
        if (now < next || !NEXT_FAILURE_LOG.compareAndSet(next, now + FAILURE_LOG_INTERVAL_MS)) {
            return;
        }
        LOGGER.warn("Sodium 动画贴图 API 调用 {} 失败（累计 {} 次，后续每分钟合并一次）: {}",
                method, total, throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    private SodiumSpriteAnimationBridge() {
    }
}
