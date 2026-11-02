package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Adds one gray line to the vanilla spyglass's tooltip. The wording says the location is shown,
 * not the building: the spyglass marks where a never-loaded structure will be, it does not draw its
 * blocks.
 */
// ItemTooltipEvent is a game-bus event, so no bus attribute here.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassTooltips {
    static final String HINT_KEY = "dimblend_spyglass.tooltip.spyglass_hint";

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(Items.SPYGLASS)) {
            event.getToolTip().add(Component.translatable(HINT_KEY).withStyle(ChatFormatting.GRAY));
        }
    }

    private SpyglassTooltips() {
    }
}
