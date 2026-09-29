package dimblend.blocks.client;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.api.CreativeCopycatHiding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * 隐藏伪装方块的选中框抑制（GAME 总线，纯客户端）：未持扳手时，准星指向隐藏方块
 * 不画描边——隐藏方块应当完全不可察觉；持扳手幽灵显现时保留描边（可正常瞄准恢复）。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID, value = Dist.CLIENT)
public final class CreativeCopycatHidingClient {

    @SubscribeEvent
    public static void onBlockHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        BlockPos pos = event.getTarget().getBlockPos();
        if (CreativeCopycatHiding.isHidden(level, pos) && !CreativeCopycatGhostRenderer.holdsWrench(player)) {
            event.setCanceled(true);
        }
    }

    private CreativeCopycatHidingClient() {
    }
}
