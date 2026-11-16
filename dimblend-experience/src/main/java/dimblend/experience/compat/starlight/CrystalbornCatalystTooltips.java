package dimblend.experience.compat.starlight;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Gray mode hint under the crystalborn catalyst item. The item id is a plain string lookup, so
 * this class never resolves Eternal Starlight types and stays safe without the mod installed.
 */
public final class CrystalbornCatalystTooltips {

    public static final String TOOLTIP_KEY = "tooltip.dimblend_experience.crystalborn_catalyst.mode";

    private static final ResourceLocation CRYSTALBORN_CATALYST_ID =
            ResourceLocation.fromNamespaceAndPath("eternal_starlight", "crystalborn_catalyst");

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(CRYSTALBORN_CATALYST_ID)) {
            return;
        }
        event.getToolTip().add(Component.translatable(TOOLTIP_KEY).withStyle(ChatFormatting.GRAY));
    }

    private CrystalbornCatalystTooltips() {
    }
}
