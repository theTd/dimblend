package dimblend.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class VoidscapeMachineTooltips {
    private VoidscapeMachineTooltips() {}

    public static void onItemTooltip(ItemTooltipEvent event) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (id.equals(ResourceLocation.fromNamespaceAndPath("voidscape", "machine_infuser"))) {
            event.getToolTip().add(Component.translatable("tooltip.dimblend.voidscape_infuser").withStyle(ChatFormatting.GRAY));
        } else if (id.equals(ResourceLocation.fromNamespaceAndPath("voidscape", "machine_collector"))) {
            event.getToolTip().add(Component.translatable("tooltip.dimblend.voidscape_collector").withStyle(ChatFormatting.GRAY));
        }
    }
}
