package dimblend.radio.net;

import java.util.Map;

import dimblend.radio.RadioCatalog;
import dimblend.radio.server.RadioSync;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import dimblend.radio.DimBlendRadio;
import io.netty.buffer.ByteBuf;

/**
 * C2S 曲库 hello：客户端把本地 {@code dimblend_radio/<station>/} 的（hash → 时长秒）
 * 上报给服务端，服务端取并集做权威选曲与切歌时钟。
 */
public record RadioHelloPayload(Map<Integer, Map<String, Double>> stations) implements CustomPacketPayload {

    public static final Type<RadioHelloPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio_hello"));

    public static final StreamCodec<ByteBuf, RadioHelloPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RadioHelloPayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            int stationCount = friendly.readVarInt();
            java.util.Map<Integer, Map<String, Double>> stations = new java.util.HashMap<>();
            for (int i = 0; i < stationCount; i++) {
                int station = friendly.readVarInt();
                int trackCount = friendly.readVarInt();
                java.util.Map<String, Double> tracks = new java.util.HashMap<>();
                for (int j = 0; j < trackCount; j++) {
                    tracks.put(friendly.readUtf(), friendly.readDouble());
                }
                stations.put(station, tracks);
            }
            return new RadioHelloPayload(stations);
        }

        @Override
        public void encode(ByteBuf buf, RadioHelloPayload value) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeVarInt(value.stations().size());
            for (var entry : value.stations().entrySet()) {
                friendly.writeVarInt(entry.getKey());
                friendly.writeVarInt(entry.getValue().size());
                for (var track : entry.getValue().entrySet()) {
                    friendly.writeUtf(track.getKey());
                    friendly.writeDouble(track.getValue());
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RadioHelloPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RadioSync.onHello(player.server, payload);
            }
        });
    }
}
