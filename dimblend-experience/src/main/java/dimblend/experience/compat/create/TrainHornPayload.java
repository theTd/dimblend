package dimblend.experience.compat.create;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 列车汽笛 S2C 动作包：服务端在特殊模式红石上升沿广播开始、下降沿广播停止，
 * 向投影后世界坐标 528 格内玩家发送。客户端据此起播/淡出
 * {@code TrainHornSoundInstance} 并登记/清除「张嘴 + 音符粒子」窗口。
 *
 * @param pos 汽笛所在方块位置（plot 坐标——Sable 结构上为子层级坐标，客户端每 tick
 *            投影到结构当前世界位置，声源随结构移动）
 * @param hornKind {@link TrainHorn.HornKind} 序号（停止包忽略）
 * @param pitch 服务端按 Config（音效 × 尺寸 9 键）解出的最终音高（停止包忽略）
 * @param start true = 起播，false = 断电淡出
 */
public record TrainHornPayload(BlockPos pos, byte hornKind, float pitch, boolean start)
        implements CustomPacketPayload {

    public static final Type<TrainHornPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "train_horn"));

    public static final StreamCodec<ByteBuf, TrainHornPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            TrainHornPayload::pos,
            ByteBufCodecs.BYTE,
            TrainHornPayload::hornKind,
            ByteBufCodecs.FLOAT,
            TrainHornPayload::pitch,
            ByteBufCodecs.BOOL,
            TrainHornPayload::start,
            TrainHornPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
