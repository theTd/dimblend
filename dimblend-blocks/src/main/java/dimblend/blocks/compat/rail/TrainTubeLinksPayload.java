package dimblend.blocks.compat.rail;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.rail.client.ClientTrainTubeLinks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 跨车厢连接表的服务端 → 客户端同步包。
 *
 * <p>只发"当前维度的全部连接 + 收件人自己的挂起选择"这一份快照（连接表最多几十条），客户端收到后整体替换，
 * 因此不需要增量协议，也不存在乱序问题。登录、换维度、建/断连接、选中/取消端点、切走扳手时各推一次。</p>
 *
 * <p>维度键<b>放在载荷里</b>而不是让客户端在收包时读 {@code Minecraft#level}：这个包在 Netty 线程落地，
 * 读客户端字段既不是线程安全的，也可能在 {@code ClientLevel} 就位前拿到 {@code null}——那一份快照会被永久判成
 * "不是本维度"而整根线缆不画（旧实现正是这个坑）。</p>
 */
public record TrainTubeLinksPayload(ResourceKey<Level> dimension, List<TrainTubeLink> links,
        @Nullable TrainTubeSelection selection) implements CustomPacketPayload {

    public static final Type<TrainTubeLinksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "train_tube_links"));

    public static final StreamCodec<FriendlyByteBuf, TrainTubeLinksPayload> STREAM_CODEC =
            StreamCodec.of(TrainTubeLinksPayload::encode, TrainTubeLinksPayload::decode);

    public TrainTubeLinksPayload {
        links = links == null ? List.of() : List.copyOf(links);
    }

    public static TrainTubeLinksPayload of(ResourceKey<Level> dimension, List<TrainTubeLink> links,
            @Nullable TrainTubeSelection selection) {
        return new TrainTubeLinksPayload(dimension, List.copyOf(links), selection);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buffer, TrainTubeLinksPayload payload) {
        buffer.writeResourceLocation(payload.dimension.location());
        buffer.writeVarInt(payload.links.size());
        for (TrainTubeLink link : payload.links) {
            buffer.writeUUID(link.subA());
            buffer.writeBlockPos(link.posA());
            buffer.writeUUID(link.subB());
            buffer.writeBlockPos(link.posB());
        }
        TrainTubeSelection selection = payload.selection;
        buffer.writeBoolean(selection != null);
        if (selection != null) {
            buffer.writeUUID(selection.subLevel());
            buffer.writeBlockPos(selection.pos());
        }
    }

    private static TrainTubeLinksPayload decode(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension =
                ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
        int size = buffer.readVarInt();
        List<TrainTubeLink> links = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            links.add(new TrainTubeLink(buffer.readUUID(), buffer.readBlockPos(),
                    buffer.readUUID(), buffer.readBlockPos()));
        }
        TrainTubeSelection selection = null;
        if (buffer.readBoolean()) {
            selection = new TrainTubeSelection(buffer.readUUID(), buffer.readBlockPos());
        }
        return new TrainTubeLinksPayload(dimension, List.copyOf(links), selection);
    }

    private static void handle(TrainTubeLinksPayload payload, IPayloadContext context) {
        // 客户端状态只能在主线程改：渲染线程每帧都在读这份表
        context.enqueueWork(() -> ClientTrainTubeLinks.accept(payload.dimension(), payload.links(),
                payload.selection()));
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
