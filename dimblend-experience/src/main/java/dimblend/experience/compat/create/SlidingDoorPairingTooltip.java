package dimblend.experience.compat.create;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Adds the pairing hint to the 4 target Create sliding door items without duplicating lines. */
public final class SlidingDoorPairingTooltip {
    public static final String TRANSLATION_KEY = "item.dimblend_experience.create_door_pairing";

    private SlidingDoorPairingTooltip() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SlidingDoorPairingTooltip::onTooltip);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!SlidingDoorPairing.isTargetDoorId(itemId)) {
            return;
        }
        for (Component line : event.getToolTip()) {
            if (line.getContents() instanceof TranslatableContents contents
                    && TRANSLATION_KEY.equals(contents.getKey())) {
                return;
            }
        }
        event.getToolTip().add(Component.translatable(TRANSLATION_KEY).withStyle(ChatFormatting.GRAY));
    }
}
