package dimblend.experience.compat.sable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

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
 */
public final class SodiumSpriteAnimationBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend_experience/sodium-sprite-animation");
    private static final MethodHandle MARK_ACTIVE;
    private static final MethodHandle HAS_ANIMATION;

    static {
        MethodHandle markActive = null;
        MethodHandle hasAnimation = null;
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
            LOGGER.warn("Sodium 动画贴图 API 不可用，Sable 子层级上的贴图动画可能仍被 Sodium 冻结", exception);
        }
        MARK_ACTIVE = markActive;
        HAS_ANIMATION = hasAnimation;
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
            // 单张贴图判定失败不应打断渲染，当作无动画处理。
            return false;
        }
    }

    /** 让这张贴图在当前刻保持活跃；Sodium 会在下一次贴图 tick 末尾清回 false。 */
    public static void markActive(TextureAtlasSprite sprite) {
        if (MARK_ACTIVE == null) {
            return;
        }
        try {
            MARK_ACTIVE.invokeExact(sprite);
        } catch (Throwable throwable) {
            // 标记失败只意味着该贴图此刻不推进，不能影响渲染。
        }
    }

    private SodiumSpriteAnimationBridge() {
    }
}
