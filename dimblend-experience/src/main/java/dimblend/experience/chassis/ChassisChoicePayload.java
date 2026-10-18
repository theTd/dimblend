package dimblend.experience.chassis;

import java.util.UUID;
import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ChassisChoicePayload(UUID token, int kind, boolean confirm) implements CustomPacketPayload {
    public static final Type<ChassisChoicePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "chassis_choice"));
    public static final StreamCodec<ByteBuf, ChassisChoicePayload> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, ChassisChoicePayload::token,
            ByteBufCodecs.VAR_INT, ChassisChoicePayload::kind,
            ByteBufCodecs.BOOL, ChassisChoicePayload::confirm, ChassisChoicePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
