package dimblend.experience.exploration;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A3/A5 远行诅咒层级 S2C 同步：z256 进度条按 {@code (|z| − 256×层级) / 256} 显示，
 * 层级以服务端 {@link ExplorationAttachments#FAR_CURSE_TIER} 为唯一来源。
 */
public record FarCurseTierPayload(int tier) implements CustomPacketPayload {

    public static final Type<FarCurseTierPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "far_curse_tier"));

    public static final StreamCodec<ByteBuf, FarCurseTierPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            FarCurseTierPayload::tier,
            FarCurseTierPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端：向该玩家下发当前层级。 */
    public static void sendTo(ServerPlayer player, int tier) {
        PacketDistributor.sendToPlayer(player, new FarCurseTierPayload(tier));
    }

    /** 客户端应用入口：仅由 S2C 处理器在客户端线程触达。 */
    public static void applyOnClient(FarCurseTierPayload payload) {
        ClientFarCurseTier.set(payload.tier());
    }
}
