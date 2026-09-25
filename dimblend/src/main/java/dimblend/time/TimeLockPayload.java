package dimblend.time;

import dimblend.client.ClientDayTimeWrite;
import dimblend.client.ClientTimeLock;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server → client push telling one client which time target applies at the
 * band it currently stands in. {@code time} is the pin for locked modes and
 * the live world day time for {@link TimeLockTarget.Mode#NONE} (unlock restore).
 * Registered on both sides via {@link #register(PayloadRegistrar)}; the
 * handler only ever runs on the client, so the {@link ClientTimeLock}
 * reference is resolved lazily and is safe on a dedicated server.
 */
public record TimeLockPayload(int modeId, long time) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TimeLockPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("dimblend", "time_lock"));

    public static final StreamCodec<FriendlyByteBuf, TimeLockPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, TimeLockPayload::modeId,
            ByteBufCodecs.VAR_LONG, TimeLockPayload::time,
            TimeLockPayload::new);

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, STREAM_CODEC, TimeLockPayload::handle);
    }

    @Override
    public CustomPacketPayload.Type<TimeLockPayload> type() {
        return TYPE;
    }

    private static void handle(TimeLockPayload payload, IPayloadContext context) {
        // PayloadRegistrar already wraps handlers onto the main thread.
        TimeLockTarget.Mode[] modes = TimeLockTarget.Mode.values();
        if (payload.modeId() < 0 || payload.modeId() >= modes.length) {
            return;
        }
        TimeLockTarget.Mode mode = modes[payload.modeId()];
        ClientTimeLock.apply(mode, payload.time());
        mode.restoreDayTime(payload.time()).ifPresent(dayTime ->
                ClientDayTimeWrite.restore(context.player().level(), dayTime));
    }
}
