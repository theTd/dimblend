package dimblend.experience.nickname;

import dimblend.experience.DimBlend;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A8 昵称编辑 C2S：对话框只提交「设置文本」或「清除」。
 * 作用目标由服务端按主手 / 注视重新解析，客户端不能指定物品 id。
 */
public record NicknameEditPayload(boolean clear, String name) implements CustomPacketPayload {

    public static final Type<NicknameEditPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "nickname_edit"));

    public static final StreamCodec<ByteBuf, NicknameEditPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL,
            NicknameEditPayload::clear,
            ByteBufCodecs.stringUtf8(NicknameStore.MAX_LENGTH),
            NicknameEditPayload::name,
            NicknameEditPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NicknameEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (NicknameActions.rejectIfUnavailable(player)) {
                return;
            }
            if (payload.clear()) {
                NicknameActions.clear(player);
            } else {
                NicknameActions.set(player, payload.name());
            }
        });
    }
}
