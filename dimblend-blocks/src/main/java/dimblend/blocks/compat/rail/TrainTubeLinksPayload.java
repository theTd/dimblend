package dimblend.blocks.compat.rail;

import java.util.ArrayList;
import java.util.List;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.rail.client.ClientTrainTubeLinks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 跨车厢连接表的服务端 → 客户端同步包。
 *
 * <p>只发"当前维度的全部连接"这一份快照（连接表最多几十条），客户端收到后整体替换，
 * 因此不需要增量协议，也不存在乱序问题。登录、换维度、建/断连接成功时各推一次。</p>
 */
public record TrainTubeLinksPayload(List<TrainTubeLink> links) implements CustomPacketPayload {

    public static final Type<TrainTubeLinksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "train_tube_links"));

    public static final StreamCodec<FriendlyByteBuf, TrainTubeLinksPayload> STREAM_CODEC =
            StreamCodec.of(TrainTubeLinksPayload::encode, TrainTubeLinksPayload::decode);

    public static TrainTubeLinksPayload of(List<TrainTubeLink> links) {
        return new TrainTubeLinksPayload(List.copyOf(links));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buffer, TrainTubeLinksPayload payload) {
        buffer.writeVarInt(payload.links.size());
        for (TrainTubeLink link : payload.links) {
            buffer.writeUUID(link.subA());
            buffer.writeBlockPos(link.posA());
            buffer.writeUUID(link.subB());
            buffer.writeBlockPos(link.posB());
        }
    }

    private static TrainTubeLinksPayload decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<TrainTubeLink> links = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            links.add(new TrainTubeLink(buffer.readUUID(), buffer.readBlockPos(),
                    buffer.readUUID(), buffer.readBlockPos()));
        }
        return new TrainTubeLinksPayload(List.copyOf(links));
    }

    private static void handle(TrainTubeLinksPayload payload, IPayloadContext context) {
        // 只有客户端会收到这个包：进到这里说明肯定有 ClientLevel
        ClientTrainTubeLinks.accept(payload.links());
    }

    // ------------------------------------------------------------------ 注册 / 推送时机

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(TrainTubeLinksPayload::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(DimBlendBlocks.MODID);
        registrar.playToClient(TYPE, STREAM_CODEC, TrainTubeLinksPayload::handle);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TrainTubeNetworkManager.syncTo(player);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TrainTubeNetworkManager.syncTo(player);
        }
    }
}
