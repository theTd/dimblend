package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.server.SpyglassSourceService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = DimBlendSpyglass.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SpyglassNetwork {
    /**
     * Bumped to 5 when the snapshot gained the per-structure predicted flag: a protocol-4 client
     * would decode the trailing byte as the next structure's id, so the two versions must not talk
     * to each other. NeoForge refuses connections between differing versions of a modded payload,
     * which is what we want here.
     *
     * <p>Bumped to 6 when the display ring's inner edge became the fixed 128 blocks and the
     * per-player reveal-distance payload was removed: a protocol-5 client would still send a packet
     * this version no longer registers.
     *
     * <p>Bumped to 7 when the server started sending template-preview shapes for predicted
     * structures: a protocol-6 client would store them but never draw them.
     *
     * <p>Bumped to 8 when the shape payload was removed entirely: the spyglass no longer sends
     * structure block data, and a protocol-7 client would wait for packets this version never sends.
     * Version 8 also adds the {@link SpyglassBusyPayload} scan flag.
     */
    private static final String PROTOCOL_VERSION = "8";

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
                SpyglassBusyPayload.TYPE,
                SpyglassBusyPayload.STREAM_CODEC,
                (payload, context) -> SpyglassBusyHandlers.handle(payload));
        registrar.playToClient(
                SpyglassSourcesPayload.TYPE,
                SpyglassSourcesPayload.STREAM_CODEC,
                (payload, context) -> SpyglassSourceHandlers.handle(payload));
        registrar.playToServer(
                SpyglassSourceEditPayload.TYPE,
                SpyglassSourceEditPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        if (payload.open()) {
                            SpyglassSourceService.open(player);
                        } else {
                            SpyglassSourceService.edit(player, payload.namespace(), payload.enabled());
                        }
                    }
                });
    }

    private SpyglassNetwork() {
    }
}
