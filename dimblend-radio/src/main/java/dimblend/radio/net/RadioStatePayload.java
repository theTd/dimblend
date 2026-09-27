package dimblend.radio.net;

import dimblend.radio.DimBlendRadio;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C 电台状态：服务端权威（曲 hash + 起始钟 + 站台 + 音量 + 位置 + 维度 + 发包时服务端钟）。
 * 音频字节不走网络，客户端按 hash 在本地 {@code dimblend_radio/} 找文件。
 *
 * <p>serverNow：发包瞬间服务端的 gameTime。客户端 offset =
 * (serverNow - startTick)/20 + 本地解码耗时，玩家 A/B/C 无论何时加入、走近，
 * 都对齐到同一服务钟，进度一致。9 字段手写 codec（composite 上限 6 元）。</p>
 */
public record RadioStatePayload(
        ResourceLocation dimension,
        BlockPos pos,
        int station,
        int side,
        String trackHash,
        long startTick,
        int nonce,
        boolean playing,
        long serverNow) implements CustomPacketPayload {

    public static final Type<RadioStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio_state"));

    public static final StreamCodec<ByteBuf, RadioStatePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RadioStatePayload decode(ByteBuf buf) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            return new RadioStatePayload(
                    friendly.readResourceLocation(),
                    friendly.readBlockPos(),
                    friendly.readVarInt(),
                    friendly.readVarInt(),
                    friendly.readUtf(),
                    friendly.readVarLong(),
                    friendly.readVarInt(),
                    friendly.readBoolean(),
                    friendly.readVarLong());
        }

        @Override
        public void encode(ByteBuf buf, RadioStatePayload value) {
            FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
            friendly.writeResourceLocation(value.dimension());
            friendly.writeBlockPos(value.pos());
            friendly.writeVarInt(value.station());
            friendly.writeVarInt(value.side());
            friendly.writeUtf(value.trackHash());
            friendly.writeVarLong(value.startTick());
            friendly.writeVarInt(value.nonce());
            friendly.writeBoolean(value.playing());
            friendly.writeVarLong(value.serverNow());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端应用入口：仅由 S2C 处理器在客户端线程触达。 */
    public static void applyOnClient(RadioStatePayload payload) {
        ClientRadioState.apply(payload);
    }
}
