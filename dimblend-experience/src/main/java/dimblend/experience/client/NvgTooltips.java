package dimblend.experience.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * hardcore_nvg 夜视仪物品提示：所有 8 款夜视仪统一追加一行灰色描述
 * 「纯手搓 需要定期更换镜片」。
 *
 * <p>按物品注册名命名空间 {@code hardcore_nvg} 匹配（该 mod 仅注册了 8 个夜视仪变体：
 * nvg / blue_nvg / orange_nvg / pink_nvg / purple_nvg / red_nvg / white_nvg / yellow_nvg），
 * 即使在物品标签尚未同步的阶段也能正常显示，且不引入任何第三方编译依赖。</p>
 */
public final class NvgTooltips {

    private static final String NVG_NAMESPACE = "hardcore_nvg";
    private static final String HANDCRAFTED_KEY = "tooltip.dimblend_experience.nvg.handcrafted";

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        if (!NVG_NAMESPACE.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())) {
            return;
        }
        event.getToolTip().add(Component.translatable(HANDCRAFTED_KEY).withStyle(ChatFormatting.GRAY));
    }

    private NvgTooltips() {
    }
}
