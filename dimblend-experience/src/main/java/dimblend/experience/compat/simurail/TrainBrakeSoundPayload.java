package dimblend.experience.compat.simurail;

import java.util.List;

import dimblend.experience.compat.simurail.client.TrainBrakeSoundClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * S2C：某次施闸的持续鸣响开始/停止，携带受控车架的 plot 坐标（2026-11-25 LOCKED #4）。
 *
 * <p>为什么不复用原版的 {@code ClientboundSoundPacket}：引擎的 sound 包只够播一次性样本，
 * 既拿不到客户端实例句柄，也无法在 75% 帧驻留循环、按实时车速淡出、松闸瞬停。
 * 本包只负责「哪些车架该起播 / 该停」，音流本身由客户端
 * {@code TrainBrakeSoundInstance#getStream} 走 NeoForge 的流式钩子自建。</p>
 *
 * <p>{@code volume}/{@code pitch} 跟着开始包走：音量是用户口径的 75%，
 * 音调沿用 {@code TrainSpeedGateMath#brakePitch}（无人值守锁存的急刹降到 0.5）。</p>
 */
public record TrainBrakeSoundPayload(ResourceLocation dimension, List<BlockPos> bogeyPositions,
                                     boolean start, float volume, float pitch) implements CustomPacketPayload {

    public static final Type<TrainBrakeSoundPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("dimblend_experience", "train_brake_sound"));

    /** 上限与 {@code TrainBrakeBroadcast.BROADCAST_CAP} 同量级：超长编组宁可不发。 */
    private static final int MAX_ENTRIES = 64;

    public static final StreamCodec<FriendlyByteBuf, TrainBrakeSoundPayload> STREAM_CODEC = StreamCodec.of(
            (FriendlyByteBuf buf, TrainBrakeSoundPayload payload) -> {
                buf.writeResourceLocation(payload.dimension());
                buf.writeBoolean(payload.start());
                buf.writeFloat(payload.volume());
                buf.writeFloat(payload.pitch());
                buf.writeVarInt(payload.bogeyPositions().size());
                for (BlockPos pos : payload.bogeyPositions()) {
                    buf.writeBlockPos(pos);
                }
            },
            (FriendlyByteBuf buf) -> {
                ResourceLocation dimension = buf.readResourceLocation();
                boolean start = buf.readBoolean();
                float volume = buf.readFloat();
                float pitch = buf.readFloat();
                int size = buf.readVarInt();
                int keep = Math.min(size, MAX_ENTRIES);
                List<BlockPos> positions = new java.util.ArrayList<>(keep);
                for (int i = 0; i < size; i++) {
                    BlockPos pos = buf.readBlockPos();
                    if (i < keep) {
                        positions.add(pos);
                    }
                }
                return new TrainBrakeSoundPayload(dimension, List.copyOf(positions), start, volume, pitch);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端落地口（与 {@code MinimumSpeedActivePayload#applyOnClient} 同型）：
     * Simurail 缺席时直接返回，绝不触碰客户端音效类。
     */
    public static void applyOnClient(TrainBrakeSoundPayload payload) {
        if (!ModList.get().isLoaded("simurail")) {
            return;
        }
        TrainBrakeSoundClient.accept(payload);
    }
}
