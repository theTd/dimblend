package dimblend.experience.tuning;

import dimblend.experience.DimBlend;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = DimBlend.MODID)
public final class TuningEvents {
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dbx")
                .then(Commands.literal("tune").requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            TuningNetwork.open(context.getSource().getPlayerOrException());
                            return 1;
                        })));
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TuningNetwork.send(player, false, "", "");
        }
    }

    private TuningEvents() {
    }
}
