package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The server's checklist results plus an optional status message key suffix (e.g. {@code fixed}). */
public record PerfCheckReportPayload(List<PerfCheckResult> results, String message) implements CustomPacketPayload {
    private static final int MAX_RESULTS = 64;
    public static final Type<PerfCheckReportPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "perf_check_report"));
    public static final StreamCodec<FriendlyByteBuf, PerfCheckReportPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.results.size());
                payload.results.forEach(result -> result.write(buffer));
                buffer.writeUtf(payload.message, 64);
            }, buffer -> {
                int count = Math.min(buffer.readVarInt(), MAX_RESULTS);
                List<PerfCheckResult> results = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    PerfCheckResult result = PerfCheckResult.read(buffer);
                    if (result != null) {
                        results.add(result);
                    }
                }
                return new PerfCheckReportPayload(results, buffer.readUtf(64));
            });

    public PerfCheckReportPayload {
        results = List.copyOf(results);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
