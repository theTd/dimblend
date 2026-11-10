package dimblend.experience.client;

import dimblend.experience.compat.dolls.VanillaDollItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class SupplementTooltips {
    public static void onItemTooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String suffix = null;
        if (stack.is(Items.TOTEM_OF_UNDYING)) suffix = "totem";
        else if (id.equals(ResourceLocation.fromNamespaceAndPath("aeronautics", "levitite_blend_bucket"))) suffix = "levitite_bucket";
        else if (VanillaDollItems.contains(stack)) suffix = "doll";
        else if (Screen.hasShiftDown() && id.equals(ResourceLocation.fromNamespaceAndPath("create", "wrench"))) suffix = "wrench";
        if (suffix != null) {
            event.getToolTip().add(Component.translatable("item.dimblend_experience.supplement." + suffix)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private SupplementTooltips() {}
}
