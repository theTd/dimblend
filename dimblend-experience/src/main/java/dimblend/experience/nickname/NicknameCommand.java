package dimblend.experience.nickname;

import com.mojang.brigadier.arguments.StringArgumentType;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * A8 物品昵称命令：{@code /dbx nickname <名称…>} 与 {@code /dbx nickname clear}。
 * 玩家入口是按键对话框；命令保留给已经绑定习惯和没有客户端按键的场合。
 * 昵称与物品 id 绑定、全服显示层生效。
 *
 * <p>Brigadier 取舍（有意为之）：昵称文本恰为 "clear" 时会路由到清除子命令，
 * 该文本无法经命令设置（对话框没有这个限制）；clear 子命令后不得再跟参数。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class NicknameCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dbx")
                .requires(source -> Config.NICKNAME.get() && source.hasPermission(Config.NICKNAME_PERMISSION.get()))
                .then(Commands.literal("nickname")
                        .then(Commands.argument("名称", StringArgumentType.greedyString())
                                .executes(context -> NicknameActions.set(context.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(context, "名称"))))
                        .then(Commands.literal("clear")
                                .executes(context -> NicknameActions.clear(
                                        context.getSource().getPlayerOrException())))));
    }

    private NicknameCommand() {
    }
}
