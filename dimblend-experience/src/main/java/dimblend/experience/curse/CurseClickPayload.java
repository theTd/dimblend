package dimblend.experience.curse;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 消失诅咒 C2S：客户端每次物理左/右键按下（含 GUI 点击与空挥）上报一次，不带数据。
 *
 * <p>是否命中由服务端自己掷骰，客户端不能指定槽位或数值；服务端只把这条 payload 当作
 * "玩家又点了一下"的触发信号。</p>
 */
public record CurseClickPayload() implements CustomPacketPayload {

    public static final Type<CurseClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "curse_click"));

    public static final StreamCodec<ByteBuf, CurseClickPayload> STREAM_CODEC =
            StreamCodec.unit(new CurseClickPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CurseClickPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CurseEvents.rollVanishingOnClick(player);
            }
        });
    }
}
