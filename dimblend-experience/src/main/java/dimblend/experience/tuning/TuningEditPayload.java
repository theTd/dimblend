package dimblend.experience.tuning;

import dimblend.experience.DimBlend;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Empty option requests the panel; edits carry one setting to avoid stale whole-form writes. */
public record TuningEditPayload(String option, double value) implements CustomPacketPayload {
    public static final Type<TuningEditPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "tuning_edit"));
    public static final StreamCodec<FriendlyByteBuf, TuningEditPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.option, 32);
                buffer.writeDouble(payload.value);
            }, buffer -> new TuningEditPayload(buffer.readUtf(32), buffer.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
