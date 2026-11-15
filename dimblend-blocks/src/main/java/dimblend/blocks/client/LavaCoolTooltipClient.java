package dimblend.blocks.client;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.fragile.WaterBucketLavaQuench;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 为水桶与蓝冰追加「右键岩浆可使其冷却」客户端描述。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID, value = Dist.CLIENT)
public final class LavaCoolTooltipClient {

    public static final String TOOLTIP_KEY = "tooltip.dimblend_blocks.lava_cool";

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!WaterBucketLavaQuench.isLavaCoolant(event.getItemStack())) {
            return;
        }
        event.getToolTip().add(Component.translatable(TOOLTIP_KEY).withStyle(ChatFormatting.GRAY));
    }

    private LavaCoolTooltipClient() {
    }
}
