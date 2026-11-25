package dimblend.experience.compat.simurail;

import java.util.List;
import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：当前"正在牵引但低于最低速"的载具集合（2026-11-25 LOCKED #2）。
 *
 * <p>服务端每 tick 对比上一轮快照，只在集合变化时发一次。客户端拿它做两件事：
 * 强调幻纱宽轨粒子的密度（只在真正"在牵引且速度不足"时喷），以及让 HUD/调试读数
 * 与服务端口径一致。集合上限 64，超出的极端情况（一列 64 节以上同时牵引）宁可不发。</p>
 */
public record MinimumSpeedActivePayload(ResourceLocation dimension, List<UUID> subLevelIds)
        implements CustomPacketPayload {

    public static final Type<MinimumSpeedActivePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("dimblend_experience", "minimum_speed_active"));

    private static final int MAX_ENTRIES = 64;

    public static final StreamCodec<FriendlyByteBuf, MinimumSpeedActivePayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, MinimumSpeedActivePayload payload) -> {
                        buf.writeResourceLocation(payload.dimension());
                        buf.writeVarInt(payload.subLevelIds().size());
                        for (UUID id : payload.subLevelIds()) {
                            buf.writeUUID(id);
                        }
                    },
                    (FriendlyByteBuf buf) -> {
                        ResourceLocation dimension = buf.readResourceLocation();
                        int size = buf.readVarInt();
                        int safeSize = Math.min(size, MAX_ENTRIES);
                        List<UUID> ids = new java.util.ArrayList<>(safeSize);
                        for (int i = 0; i < size; i++) {
                            UUID id = buf.readUUID();
                            if (i < safeSize) {
                                ids.add(id);
                            }
                        }
                        return new MinimumSpeedActivePayload(dimension, List.copyOf(ids));
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端落地口（与 {@code DeadmanLatchPayload#applyOnClient} 同型）：
     * 只在客户端线程执行，服务端永远不会触达 {@code ClientMinimumSpeedState}。
     */
    public static void applyOnClient(MinimumSpeedActivePayload payload) {
        ClientMinimumSpeedState.accept(payload);
    }
}
