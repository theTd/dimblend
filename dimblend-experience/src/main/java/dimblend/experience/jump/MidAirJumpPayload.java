package dimblend.experience.jump;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 空包体 C2S：客户端已经权威地做完了二段跳位移，这个包只让服务端做两件事——
 * 重置该玩家的下落高度（覆盖「被天花板完全挡住、没有向上位移包」的角落）并向
 * 其他玩家广播 cloud 粒子。
 *
 * <p>服务端**不得**改 {@code deltaMovement} 或置 {@code hurtMarked}：那会让
 * {@code ClientboundSetEntityMotionPacket} 覆写客户端预测，复现旧第三方二段跳
 * 兼容的跳跃抖动/回弹。</p>
 */
public record MidAirJumpPayload() implements CustomPacketPayload {

    public static final Type<MidAirJumpPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "mid_air_jump"));

    public static final StreamCodec<ByteBuf, MidAirJumpPayload> STREAM_CODEC =
            StreamCodec.unit(new MidAirJumpPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
