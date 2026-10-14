package dimblend.experience.compat.simurail;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * E7' 无人值守刹车锁存状态 S2C 同步：每维度发送完整的已锁存车厢 UUID 快照，
 * 供锁存粒子（portal，E6 参数）读取。维度内玩家收广播；进服/换维度由
 * {@link TrainDeadmanSync} 补发当前状态。
 */
public record DeadmanLatchPayload(ResourceKey<Level> dim, List<UUID> carriages) implements CustomPacketPayload {

    public DeadmanLatchPayload {
        carriages = List.copyOf(carriages);
    }

    public static final Type<DeadmanLatchPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "deadman_latch"));

    public static final StreamCodec<ByteBuf, DeadmanLatchPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            DeadmanLatchPayload::dim,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()),
            DeadmanLatchPayload::carriages,
            DeadmanLatchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端：向该维度内所有玩家广播锁存状态变化。 */
    public static void sendToDimension(ServerLevel level, Collection<UUID> carriages) {
        PacketDistributor.sendToPlayersInDimension(level, new DeadmanLatchPayload(level.dimension(), List.copyOf(carriages)));
    }

    /** 服务端：向单个玩家下发指定维度当前锁存状态（进服/换维度补发）。 */
    public static void sendTo(ServerPlayer player, ResourceKey<Level> dim, Collection<UUID> carriages) {
        PacketDistributor.sendToPlayer(player, new DeadmanLatchPayload(dim, List.copyOf(carriages)));
    }

    /** 客户端应用入口：仅由 S2C 处理器在客户端线程触达。 */
    public static void applyOnClient(DeadmanLatchPayload payload) {
        ClientDeadmanLatch.set(payload.dim(), payload.carriages());
    }
}
