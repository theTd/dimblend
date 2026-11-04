package dimblend.spyglass.net;

import dimblend.spyglass.DimBlendSpyglass;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server → client "a scan is in flight" flag.
 *
 * <p>The snapshot only arrives when the scan has finished, so a scoping player would otherwise look
 * at an empty lens with no feedback while the server reads region files. The client shows the
 * {@code 测算中 / Measuring...} line while this flag is set and the player is scoping. Wire format:
 * one boolean.
 *
 * <p>Added in protocol version 8, the same version that removed the shape payload.
 */
public record SpyglassBusyPayload(boolean busy) implements CustomPacketPayload {
    public static final Type<SpyglassBusyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "scan_busy"));

    public static final StreamCodec<ByteBuf, SpyglassBusyPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpyglassBusyPayload decode(ByteBuf buf) {
            return new SpyglassBusyPayload(new FriendlyByteBuf(buf).readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, SpyglassBusyPayload value) {
            new FriendlyByteBuf(buf).writeBoolean(value.busy());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void sendTo(ServerPlayer player, boolean busy) {
        PacketDistributor.sendToPlayer(player, new SpyglassBusyPayload(busy));
    }
}
