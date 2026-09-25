package dimblend.experience.nickname;

import java.util.HashMap;
import java.util.Map;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A8 昵称表 S2C 同步：登录与每次变更时全量推送（表很小，全量最简单可靠）。
 */
public record NicknameSyncPayload(Map<ResourceLocation, String> nicknames) implements CustomPacketPayload {

    public static final Type<NicknameSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "nickname_sync"));

    public static final StreamCodec<ByteBuf, NicknameSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.STRING_UTF8),
            NicknameSyncPayload::nicknames,
            NicknameSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端：向所有在线玩家推送当前昵称表（开关关闭时不推送）。 */
    public static void broadcast(MinecraftServer server) {
        if (!Config.NICKNAME.get()) {
            return;
        }
        NicknameSyncPayload payload = new NicknameSyncPayload(NicknameStore.get(server).view());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** 客户端应用入口：仅由 S2C 处理器在客户端线程触达。 */
    public static void applyOnClient(NicknameSyncPayload payload) {
        ClientNicknames.replaceAll(payload.nicknames());
    }
}
