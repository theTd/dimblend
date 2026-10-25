package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.server.SpyglassServerService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = DimBlendSpyglass.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SpyglassNetwork {
    /**
     * Bumped to 4 when the display ring's inner edge became the client's terrain fog start: the
     * server now takes a per-player reveal distance from {@link SpyglassRevealPayload}, and a
     * protocol-3 client would never send it (the server would fall back to its own default). NeoForge
     * refuses connections between differing versions of a modded payload, which is what we want here.
     */
    private static final String PROTOCOL_VERSION = "4";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // PayloadRegistrar wraps handlers in MainThreadPayloadHandler by default, so these already
        // run on the client/server main thread.
        registrar.playToClient(
                SpyglassSnapshotPayload.TYPE,
                SpyglassSnapshotPayload.STREAM_CODEC,
                (payload, context) -> SpyglassSnapshotHandlers.handle(payload));
        registrar.playToClient(
                SpyglassShapePayload.TYPE,
                SpyglassShapePayload.STREAM_CODEC,
                (payload, context) -> SpyglassShapeHandlers.handle(payload));
        registrar.playToServer(
                SpyglassRevealPayload.TYPE,
                SpyglassRevealPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        SpyglassServerService.acceptReveal(player, payload.blocks());
                    }
                });
    }

    private SpyglassNetwork() {
    }
}
