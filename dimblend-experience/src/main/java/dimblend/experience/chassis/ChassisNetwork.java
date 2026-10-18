package dimblend.experience.chassis;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisNetwork {
    private ChassisNetwork() {}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(ChassisChoicePayload.TYPE, ChassisChoicePayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (ChassisEvents.available() && context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        ChassisSelection.choose(player, payload);
                    }
                }));
        registrar.playToClient(ChassisScreenPayload.TYPE, ChassisScreenPayload.STREAM_CODEC, (payload, context) ->
                context.enqueueWork(() -> dimblend.experience.client.ChassisScreen.apply(payload)));
    }
}
