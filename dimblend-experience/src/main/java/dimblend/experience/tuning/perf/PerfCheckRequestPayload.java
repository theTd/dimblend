package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Asks the server for its checklist results; a non-empty {@code fix} first applies that step's server fix. */
public record PerfCheckRequestPayload(String fix) implements CustomPacketPayload {
    public static final Type<PerfCheckRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "perf_check_request"));
    public static final StreamCodec<FriendlyByteBuf, PerfCheckRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.fix, 64),
            buffer -> new PerfCheckRequestPayload(buffer.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
