package dimblend.experience.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Searchlight tooltip lines: two always-visible gray lines under every dndecor frontlight and under Create's blaze
 * burner (never on the empty burner, which is a separate item id). Registered on the game bus from the client
 * setup; nothing here resolves a Create or DnDecor type, so it is safe on any client.
 */
public final class SearchlightTooltips {
    private static final String PREFIX = "item.dimblend_experience.searchlight.";
    private static final String FRONTLIGHT_NAMESPACE = "dndecor";
    private static final String FRONTLIGHT_SUFFIX = "_frontlight";
    private static final ResourceLocation BLAZE_BURNER =
            ResourceLocation.fromNamespaceAndPath("create", "blaze_burner");

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (isFrontlight(id)) {
            add(event, PREFIX + "frontlight_hint");
            add(event, PREFIX + "frontlight_lava_hint");
        } else if (BLAZE_BURNER.equals(id)) {
            add(event, PREFIX + "observer_hint");
            add(event, PREFIX + "observer_signal_hint");
        }
    }

    private static boolean isFrontlight(ResourceLocation id) {
        return id.getNamespace().equals(FRONTLIGHT_NAMESPACE) && id.getPath().endsWith(FRONTLIGHT_SUFFIX);
    }

    private static void add(ItemTooltipEvent event, String key) {
        event.getToolTip().add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }

    private SearchlightTooltips() {
    }
}
