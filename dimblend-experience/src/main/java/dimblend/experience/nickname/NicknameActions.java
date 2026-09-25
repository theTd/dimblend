package dimblend.experience.nickname;

import dimblend.experience.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A8 物品昵称的服务端落库。命令与按键对话框的 C2S 包都走这里。
 *
 * <p>反馈必须用字符串（或 Component / 数字 / 布尔）做翻译参数。
 * {@link ResourceLocation} 在开发环境会直接被
 * {@code TranslatableContents} 拒绝；正式环境会放过构造、
 * 等到编码 {@code clientbound/minecraft:system_chat} 时才抛
 * {@code EncoderException} 把玩家踢下线——此时昵称其实已经写入存档。
 */
public final class NicknameActions {

    private static final String KEY = "message.dimblend_experience.nickname.";
    private static final String KEY_EMPTY = KEY + "empty";
    private static final String KEY_TOO_LONG = KEY + "too_long";
    private static final String KEY_NO_ITEM_FORM = KEY + "no_item_form";
    private static final String KEY_TARGET_PROMPT = KEY + "target_prompt";
    private static final String KEY_SET = KEY + "set";
    private static final String KEY_CLEARED = KEY + "cleared";
    private static final String KEY_NOTHING_TO_CLEAR = KEY + "nothing_to_clear";
    private static final String KEY_DISABLED = KEY + "disabled";
    private static final String KEY_NO_PERMISSION = KEY + "no_permission";

    /** 调用方必须已经确认功能开启且玩家有权限。命令由 Brigadier {@code requires} 把关，数据包由 {@link #rejectIfUnavailable} 把关。 */
    public static int set(ServerPlayer player, String rawName) {
        String name = rawName.trim();
        if (name.isEmpty()) {
            tell(player, Component.translatable(KEY_EMPTY));
            return 0;
        }
        if (name.length() > NicknameStore.MAX_LENGTH) {
            tell(player, Component.translatable(KEY_TOO_LONG, NicknameStore.MAX_LENGTH));
            return 0;
        }
        NicknameTarget.Result target = NicknameTarget.resolve(player);
        ResourceLocation itemId = acceptedId(player, target);
        if (itemId == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        NicknameStore.get(server).set(server, itemId, name);
        tell(player, Component.translatable(KEY_SET, itemId.toString(), name));
        return 1;
    }

    /** 同 {@link #set}：不再重复做权限判断。 */
    public static int clear(ServerPlayer player) {
        NicknameTarget.Result target = NicknameTarget.resolve(player);
        ResourceLocation itemId = acceptedId(player, target);
        if (itemId == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        boolean changed = NicknameStore.get(server).clear(server, itemId);
        tell(player, Component.translatable(changed ? KEY_CLEARED : KEY_NOTHING_TO_CLEAR, itemId.toString()));
        return changed ? 1 : 0;
    }

    /**
     * 数据包入口的开关与权限。返回 true 表示应拒绝，且已经告诉玩家原因。
     * 命令不走这里，避免 {@code /execute as} 的权限来源被玩家自身权限覆盖。
     */
    public static boolean rejectIfUnavailable(ServerPlayer player) {
        if (!Config.NICKNAME.get()) {
            tell(player, Component.translatable(KEY_DISABLED));
            return true;
        }
        if (!player.hasPermissions(Config.NICKNAME_PERMISSION.get())) {
            tell(player, Component.translatable(KEY_NO_PERMISSION));
            return true;
        }
        return false;
    }

    /** 目标不成立时已向玩家反馈，返回 null。 */
    private static ResourceLocation acceptedId(ServerPlayer player, NicknameTarget.Result target) {
        return switch (target.status()) {
            case FOUND -> target.itemId();
            case NO_ITEM_FORM -> {
                tell(player, Component.translatable(KEY_NO_ITEM_FORM));
                yield null;
            }
            case NO_TARGET -> {
                tell(player, Component.translatable(KEY_TARGET_PROMPT));
                yield null;
            }
        };
    }

    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    private NicknameActions() {
    }
}
