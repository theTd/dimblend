package dimblend.blocks.compat.copycats;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 创造伪装方块放置兜底：非创造玩家（及一切非玩家来源）放置三创造变体一律取消。
 *
 * <p>背景：上游粱 helper 的物品谓词是 {@code instanceof CopycatBeamBlock}，
 * 创造粱天然命中——生存玩家手持创造粱右键原版粱会走上游 helper 的无检查
 * {@code placeInWorld} 链直接放下（C0 遗留向量）。本守卫覆盖一切放置路径
 * （helper/物品/机器），只放行创造玩家。依赖只出现在方法体内 + isLoaded
 * 守卫先行，Copycats+ 未加载时直接返回，不解析任何依赖类。</p>
 */
public final class CreativeCopycatGuard {

    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        Entity placer = event.getEntity();
        if (placer instanceof Player player && player.isCreative()) {
            return;
        }
        if (isCreativeCopycat(event.getPlacedBlock().getBlock())) {
            event.setCanceled(true);
        }
    }

    private static boolean isCreativeCopycat(Block block) {
        return block == CreativeCopycats.CREATIVE_COPYCAT_SLAB.get()
                || block == CreativeCopycats.CREATIVE_COPYCAT_BEAM.get()
                || block == CreativeCopycats.CREATIVE_COPYCAT_PANEL.get();
    }

    private CreativeCopycatGuard() {
    }
}
