package dimblend.compat;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Copycats (Create / Copycats+ / Create Connected) forward mining speed and blast
 * resistance to the applied material. That path is a Block override that never
 * calls {@code super}, so {@link dimblend.mixin.BlockBehaviourMixin} cannot see it.
 *
 * <p>Callers intercept {@code BlockState} / {@code IBlockStateExtension} before
 * virtual dispatch and substitute vanilla obsidian's values (destroyTime 50,
 * explosionResistance 1200, including diamond-pick harvest checks).
 */
public final class CopycatObsidianHardness {

    private static final ConcurrentHashMap<Block, Boolean> CACHE = new ConcurrentHashMap<>();

    private CopycatObsidianHardness() {
    }

    public static boolean isCopycat(BlockState state) {
        Block block = state.getBlock();
        Boolean cached = CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        ResourceLocation id = block.builtInRegistryHolder().key().location();
        boolean result = isCopycatId(id.getNamespace(), id.getPath());
        CACHE.put(block, result);
        return result;
    }

    /**
     * Namespace/path matcher used by {@link #isCopycat(BlockState)} and unit tests.
     * {@code copycat} in the path plus a known copycat-mod namespace; internal
     * wrappers ({@code wrapped_copycat}, {@code copycat_base}) match on purpose.
     */
    public static boolean isCopycatId(String namespace, String path) {
        if (path.indexOf("copycat") < 0) {
            return false;
        }
        return "copycats".equals(namespace)
                || "create".equals(namespace)
                || "create_connected".equals(namespace);
    }

    public static float obsidianDestroyTime() {
        return Blocks.OBSIDIAN.defaultDestroyTime();
    }

    public static float obsidianExplosionResistance() {
        return Blocks.OBSIDIAN.getExplosionResistance();
    }
}
