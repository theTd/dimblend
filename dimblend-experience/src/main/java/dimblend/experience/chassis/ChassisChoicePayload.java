package dimblend.experience.chassis;

import java.util.UUID;
import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 玩家在选择页点下的预设。
 *
 * <p>2026-11-15：取消二次确认后一次点击就生成，包里只剩“哪个会话、哪个预设”。{@code preset}
 * 用 {@link ChassisPreset#id()}（字符串）而不是序号，两端对预设表的增删不会错位；空串表示玩家关闭
 * 页面、只丢弃会话。
 */
public record ChassisChoicePayload(UUID token, String preset) implements CustomPacketPayload {
    public static final Type<ChassisChoicePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "chassis_choice"));
    public static final StreamCodec<ByteBuf, ChassisChoicePayload> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.core.UUIDUtil.STREAM_CODEC, ChassisChoicePayload::token,
            ByteBufCodecs.STRING_UTF8, ChassisChoicePayload::preset, ChassisChoicePayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
