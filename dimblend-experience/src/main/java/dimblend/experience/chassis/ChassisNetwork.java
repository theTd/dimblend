package dimblend.experience.chassis;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChassisNetwork {
    private ChassisNetwork() {}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        // Bumped to 2 when the choice payload became a preset id and the confirmation left the
        // wire format (2026-11-15): a 1.x peer cannot decode 2.x payloads.
        var registrar = event.registrar("2");
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
