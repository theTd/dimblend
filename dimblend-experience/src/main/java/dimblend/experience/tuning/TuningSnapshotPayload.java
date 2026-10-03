package dimblend.experience.tuning;

import dimblend.experience.DimBlend;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TuningSnapshotPayload(boolean open, boolean editable, int availableMask,
                                    List<Double> values, String acknowledged, String message) implements CustomPacketPayload {
    public static final Type<TuningSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "tuning_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, TuningSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.open);
                buffer.writeBoolean(payload.editable);
                buffer.writeInt(payload.availableMask);
                for (double value : payload.values) {
                    buffer.writeDouble(value);
                }
                buffer.writeUtf(payload.acknowledged, 32);
                buffer.writeUtf(payload.message, 64);
            }, buffer -> {
                boolean open = buffer.readBoolean();
                boolean editable = buffer.readBoolean();
                int availableMask = buffer.readInt();
                List<Double> values = new ArrayList<>();
                for (TuningOption option : TuningOption.values()) {
                    values.add(buffer.readDouble());
                }
                return new TuningSnapshotPayload(open, editable, availableMask, List.copyOf(values),
                        buffer.readUtf(32), buffer.readUtf(64));
            });

    public TuningSnapshotPayload {
        if (values.size() != TuningOption.values().length) {
            throw new IllegalArgumentException("Invalid tuning snapshot size");
        }
        values = List.copyOf(values);
    }

    public double value(TuningOption option) { return values.get(option.ordinal()); }
    public boolean available(TuningOption option) { return (availableMask & (1 << option.ordinal())) != 0; }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
