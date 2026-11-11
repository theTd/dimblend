package dimblend.experience.compat.create;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Adds the pairing hint without changing Create's own language files. */
public final class SlidingDoorPairingTooltip {
    private static final ResourceLocation[] DOORS = {
            ResourceLocation.parse("create:andesite_door"),
            ResourceLocation.parse("create:brass_door"),
            ResourceLocation.parse("create:copper_door"),
            ResourceLocation.parse("create:train_door")
    };

    private SlidingDoorPairingTooltip() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SlidingDoorPairingTooltip::onTooltip);
    }

    private static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        for (ResourceLocation id : DOORS) {
            if (BuiltInRegistries.ITEM.getOptional(id).filter(stack::is).isPresent()) {
                event.getToolTip().add(Component.translatable("item.dimblend_experience.create_door_pairing"));
                return;
            }
        }
    }
}
