package dimblend.experience.compat.copycat;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 全体伪装方块统一黑曜石硬度（全局功能，与维度无关）。
 *
 * <p>Copycats（Create / Copycats+ / Create Connected）把挖掘速度与爆炸抗性转发给贴图材质，
 * 该路径是 {@code Block} 重写且从不调 {@code super}，{@code BlockBehaviour} 层面的 mixin
 * 拦截不到。调用方在 {@code BlockState} / {@code IBlockStateExtension} 分发前拦截，
 * 直接代入原版黑曜石数值（含钻石镐采集校验）。
 *
 * <p>开关：{@code copycatObsidianHardness}（SERVER，默认开；关闭即走原版逻辑）。
 * NeoForge 会把 SERVER 配置同步到客户端，客户端挖掘裂纹与服务端一致。
 *
 * <p>与 {@code dimblend-blocks} 的 C0 共存：C0 是基类构造器注入的静态硬度，
 * 本机制是 {@code BlockState} 层的 HEAD 拦截；两者都装时以本拦截为准（先分发先命中），
 * 行为一致（黑曜石硬度），无冲突。
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
     * 命名空间/路径匹配器，供 {@link #isCopycat(BlockState)} 与单测使用。
     * 路径含 {@code copycat} 且命名空间为已知伪装模组；内部包装器
     * （{@code wrapped_copycat}、{@code copycat_base}）有意命中。
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
