package dimblend.experience.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Three gray hint lines under the CameraCapture camera: paper reloads the film, and every picture
 * remembers where it was taken so the player can find the way back.
 *
 * <p>Pure item-id check, safe on any client; CameraCapture classes are never resolved here.
 */
public final class CameraTooltips {
    private static final String PREFIX = "tooltip.dimblend_experience.camera.";
    private static final ResourceLocation CAMERA_ID =
            ResourceLocation.fromNamespaceAndPath("camerapture", "camera");

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(CAMERA_ID)) {
            return;
        }
        add(event, PREFIX + "line1");
        add(event, PREFIX + "line2");
        add(event, PREFIX + "line3");
    }

    private static void add(ItemTooltipEvent event, String key) {
        event.getToolTip().add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }

    private CameraTooltips() {
    }
}
