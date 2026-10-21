package dimblend.experience.compat.create;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 列车汽笛 S2C 触发包：服务端在蒸汽笛红石上升沿（锁定窗口外且六面邻接引擎）时，
 * 向投影后世界坐标 528 格内玩家广播。客户端据此起播
 * {@code TrainHornSoundInstance} 并登记「张嘴 + 音符粒子」窗口。
 *
 * @param pos 汽笛所在方块位置（plot 坐标——Sable 结构上为子层级坐标，客户端每 tick
 *            投影到结构当前世界位置，声源随结构移动）
 * @param hornKind {@link TrainHorn.HornKind} 序号
 * @param pitch 服务端按 Config（音效 × 尺寸 9 键）解出的最终音高
 */
public record TrainHornPayload(BlockPos pos, byte hornKind, float pitch) implements CustomPacketPayload {

    public static final Type<TrainHornPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "train_horn"));

    public static final StreamCodec<ByteBuf, TrainHornPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            TrainHornPayload::pos,
            ByteBufCodecs.BYTE,
            TrainHornPayload::hornKind,
            ByteBufCodecs.FLOAT,
            TrainHornPayload::pitch,
            TrainHornPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
