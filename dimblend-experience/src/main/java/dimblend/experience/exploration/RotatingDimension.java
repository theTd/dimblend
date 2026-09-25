package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 探索限制规则的作用域：dimblend 的旋转维度（默认 dimblend:rotating）。
 * A1-A6 的所有规则只在其中生效。
 */
public final class RotatingDimension {

    private static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("dimblend", "rotating");
    private static volatile boolean warnedInvalid;

    public static ResourceKey<Level> key() {
        String raw = Config.ROTATING_DIMENSION_ID.get();
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            // 非法字符或缺命名空间：log-once 并回退默认，避免运行期崩溃或静默失效
            if (!warnedInvalid) {
                DimBlend.LOGGER.error("Config rotatingDimensionId '{}' is not a valid namespaced id, falling back to {}", raw, DEFAULT_ID);
                warnedInvalid = true;
            }
            id = DEFAULT_ID;
        }
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    public static boolean is(ServerPlayer player) {
        return player.level().dimension().equals(key());
    }

    public static boolean is(Level level) {
        return level.dimension().equals(key());
    }

    private RotatingDimension() {
    }
}
