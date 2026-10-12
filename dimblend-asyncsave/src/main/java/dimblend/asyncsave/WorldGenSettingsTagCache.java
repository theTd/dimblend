package dimblend.asyncsave;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;

/**
 * level.dat 的 WorldGenSettings 段缓存。原版每次存档都在服务器线程上把整个维度注册表
 * （各维度的 ChunkGenerator + BiomeSource）重新走一遍 Codec 编码；rotating 维度内嵌了
 * Eternal Starlight 的 11365 条直接 multi_noise 参数，单次编码 270～490 ms（2026-10-12
 * 慢 tick 采样里每次自动存档都卡一下）。
 *
 * <p>编码输入在一个世界的生命周期内不变：WorldOptions 是 PrimaryLevelData 的 final 字段，
 * LEVEL_STEM 注册表在服务器启动后即冻结。因此按（WorldOptions 实例, 冻结注册表实例）缓存
 * 编码结果，之后每次返回副本——NBT 树拷贝比 Codec 编码便宜一个数量级，且调用方拿到的
 * 是私有副本，不会改到缓存。换世界后两个弱引用都指向新实例，缓存自然失效。
 */
public final class WorldGenSettingsTagCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static WeakReference<WorldOptions> cachedOptions = new WeakReference<>(null);
    private static WeakReference<Registry<LevelStem>> cachedDimensions = new WeakReference<>(null);
    private static Tag cachedTag;

    /**
     * 返回 WorldGenSettings 的编码结果；{@code encoder} 只在缓存未命中时调用。
     * 非 NBT 结果（别的 DynamicOps）与编码失败一律不缓存、原样返回。
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> DataResult<T> encode(WorldOptions options, RegistryAccess access,
                                                        Supplier<DataResult<T>> encoder) {
        Registry<LevelStem> dimensions = access.registryOrThrow(Registries.LEVEL_STEM);
        if (cachedTag != null && cachedOptions.get() == options && cachedDimensions.get() == dimensions) {
            return DataResult.success((T) cachedTag.copy());
        }
        long started = System.nanoTime();
        DataResult<T> result = encoder.get();
        T value = result.result().orElse(null);
        if (!(value instanceof Tag tag) || result.error().isPresent()) {
            return result;
        }
        cachedOptions = new WeakReference<>(options);
        cachedDimensions = new WeakReference<>(dimensions);
        cachedTag = tag.copy();
        LOGGER.info("WorldGenSettings encoded in {} ms; later level.dat saves reuse it",
                (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    private WorldGenSettingsTagCache() {
    }
}
