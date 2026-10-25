package dimblend.experience.exploration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dimblend.experience.DimBlend;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * A12 diagnostic command: {@code /dbx mobcensus [radius]}, permission 2, radius
 * defaults to 128 (1-512). Counts loaded MONSTERs around the executing player and
 * prints the top types with their spawn-source breakdown plus totals.
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class MonsterCensusCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dbx")
                .then(Commands.literal("mobcensus")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> report(context.getSource(), MonsterCensus.DEFAULT_RADIUS))
                        .then(Commands.argument("radius",
                                        IntegerArgumentType.integer(MonsterCensus.MIN_RADIUS, MonsterCensus.MAX_RADIUS))
                                .executes(context -> report(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius"))))));
    }

    private static int report(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        for (String line : MonsterCensus.report(player.serverLevel(), player.position(), radius)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private MonsterCensusCommand() {
    }
}
