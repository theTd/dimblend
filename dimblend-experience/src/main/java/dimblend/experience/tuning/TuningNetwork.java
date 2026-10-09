package dimblend.experience.tuning;

import dimblend.experience.DimBlend;
import java.util.Arrays;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class TuningNetwork {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // The snapshot carries one value per option: adding options changes the protocol.
        var registrar = event.registrar("3");
        registrar.playToServer(TuningEditPayload.TYPE, TuningEditPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handle(player, payload);
                    }
                }));
        registrar.playToClient(TuningSnapshotPayload.TYPE, TuningSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dimblend.experience.client.TuningClient.receive(payload)));
    }

    public static void open(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.translatable(TuningOption.PREFIX + "no_permission"));
            return;
        }
        send(player, true, "", "");
    }

    private static void handle(ServerPlayer player, TuningEditPayload payload) {
        if (payload.option().isEmpty()) {
            open(player);
            return;
        }
        if (!player.hasPermissions(2)) {
            send(player, false, payload.option(), "no_permission");
            return;
        }
        TuningOption option = TuningOption.byId(payload.option());
        if (option == null || !option.isValid(payload.value())) {
            send(player, false, payload.option(), "invalid");
            return;
        }
        if (!TuningSettings.available(option)) {
            send(player, false, payload.option(), "unavailable");
            return;
        }
        double previous = TuningSettings.get(option);
        try {
            TuningSettings.set(option, payload.value());
        } catch (RuntimeException failure) {
            try {
                TuningSettings.set(option, previous);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            DimBlend.LOGGER.error("Could not save tuning option {}", option.id(), failure);
            send(player, false, option.id(), "save_failed");
            return;
        }
        for (ServerPlayer recipient : player.server.getPlayerList().getPlayers()) {
            send(recipient, false, recipient == player ? option.id() : "", recipient == player ? "saved" : "");
        }
    }

    public static void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, false, "", "");
        }
    }

    public static void send(ServerPlayer player, boolean open, String acknowledged, String message) {
        int mask = 0;
        for (TuningOption option : TuningOption.values()) {
            if (TuningSettings.available(option)) {
                mask |= 1 << option.ordinal();
            }
        }
        PacketDistributor.sendToPlayer(player, new TuningSnapshotPayload(open, player.hasPermissions(2), mask,
                Arrays.stream(TuningOption.values()).map(TuningSettings::get).toList(), acknowledged, message));
    }

    private TuningNetwork() {
    }
}
