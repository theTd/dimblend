package dimblend.experience.nickname;

import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * A8 客户端侧昵称表：由 S2C 同步包维护，供显示层 mixin 查询。
 * 仅物理客户端加载；服务端代码不引用本类。
 */
public final class ClientNicknames {

    private static volatile Map<ResourceLocation, String> nicknames = Map.of();

    public static void replaceAll(Map<ResourceLocation, String> fromServer) {
        nicknames = Map.copyOf(fromServer);
    }

    /** 当前同步表里该物品 id 的昵称；没有则空串。供对话框预填。 */
    public static String get(ResourceLocation itemId) {
        String nickname = nicknames.get(itemId);
        return nickname == null ? "" : nickname;
    }

    /**
     * 若该物品 id 有昵称且实例没有更具体的显示名组件（原版优先级链
     * CUSTOM_NAME → ITEM_NAME → Item.getName，两者都让位），返回昵称组件；
     * 否则返回 null 走原版显示。
     */
    public static Component override(ItemStack stack) {
        Map<ResourceLocation, String> map = nicknames;
        if (map.isEmpty() || stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.ITEM_NAME)) {
            return null;
        }
        String nickname = map.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return nickname == null ? null : Component.literal(nickname);
    }

    private ClientNicknames() {
    }
}
