package dimblend.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

public final class VoidscapeMachineTooltips {
    /** Voidscape 8 台机器 → 说明文案键（按机器注册顺序，供事件与自检共用）。 */
    private static final Map<ResourceLocation, String> TOOLTIP_KEYS = build();

    private VoidscapeMachineTooltips() {}

    public static Map<ResourceLocation, String> tooltipKeys() {
        return TOOLTIP_KEYS;
    }

    public static void onItemTooltip(ItemTooltipEvent event) {
        String key = TOOLTIP_KEYS.get(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()));
        if (key != null) {
            event.getToolTip().add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        }
    }

    private static Map<ResourceLocation, String> build() {
        Map<ResourceLocation, String> keys = new LinkedHashMap<>();
        machine(keys, "liquifier");
        machine(keys, "defuser");
        machine(keys, "germinator");
        machine(keys, "well");
        machine(keys, "coop");
        machine(keys, "hatchery");
        machine(keys, "infuser");
        machine(keys, "collector");
        return Collections.unmodifiableMap(keys);
    }

    private static void machine(Map<ResourceLocation, String> keys, String machine) {
        keys.put(ResourceLocation.fromNamespaceAndPath("voidscape", "machine_" + machine),
                "tooltip.dimblend.voidscape_" + machine);
    }
}
