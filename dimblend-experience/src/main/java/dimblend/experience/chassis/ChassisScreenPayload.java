package dimblend.experience.chassis;

import java.util.UUID;
import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ChassisScreenPayload(UUID token, int selected, int waitMillis) implements CustomPacketPayload {
    public static final Type<ChassisScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "chassis_screen"));
    public static final StreamCodec<ByteBuf, ChassisScreenPayload> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, ChassisScreenPayload::token,
            ByteBufCodecs.VAR_INT, ChassisScreenPayload::selected,
            ByteBufCodecs.VAR_INT, ChassisScreenPayload::waitMillis, ChassisScreenPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
