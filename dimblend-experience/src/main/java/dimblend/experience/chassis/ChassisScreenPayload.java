package dimblend.experience.chassis;

import java.util.UUID;
import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端对选择页的唯一指令：打开或关闭。
 *
 * <p>2026-11-15：二次确认与冷却期删除后，服务端不再回传“选中第几项/还要等多久”，客户端本地
 * 一次点击就决定，所以包里只留 token 与布尔开关。
 */
public record ChassisScreenPayload(UUID token, boolean open) implements CustomPacketPayload {
    public static final Type<ChassisScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "chassis_screen"));
    public static final StreamCodec<ByteBuf, ChassisScreenPayload> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, ChassisScreenPayload::token,
            ByteBufCodecs.BOOL, ChassisScreenPayload::open, ChassisScreenPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
