package dimblend.experience.compat.coldsweat;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 恒温石物品提示：补一行"用冰/熔岩块右键设置模式"的说明。
 *
 * <p>按物品注册名匹配（{@code cold_sweat:thermolith}），不引用 Cold Sweat 的类，
 * Cold Sweat 缺席时本类照常可加载、事件永不命中。</p>
 */
public final class ThermolithTooltips {

    /** 提示文案翻译键（由 lang 文件提供）。 */
    public static final String TOOLTIP_KEY = "tooltip.dimblend_experience.thermolith.mode";

    private static final ResourceLocation THERMOLITH_ID =
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "thermolith");

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(THERMOLITH_ID)) {
            return;
        }
        event.getToolTip().add(Component.translatable(TOOLTIP_KEY).withStyle(ChatFormatting.GRAY));
    }

    private ThermolithTooltips() {
    }
}
