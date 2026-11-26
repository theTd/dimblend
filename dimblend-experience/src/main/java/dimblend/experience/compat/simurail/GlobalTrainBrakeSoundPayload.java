package dimblend.experience.compat.simurail;

import dimblend.experience.compat.simurail.client.TrainBrakeSoundClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * S2C：全服一次性的 {@code train_brake} 播报（末影刹车 / 常规施闸各一条），
 * 只带音量、音调与共享种子（2026-11-27 Slice 10）。
 *
 * <p><b>为什么不复用原版 {@code ClientboundSoundPacket}</b>：原版 {@code ClientLevel#playSound}
 * 会把它做成固定世界坐标、{@code Attenuation.LINEAR}（{@code train_brake} 衰减距离 16 格）、
 * {@code relative = false} 的 {@code SimpleSoundInstance}。末影龙追逐里列车以 15–32 m/s 前进，
 * 0.5 秒内玩家就离开那个定点 &gt; 16 格，{@code pitch = 0.5} 的 10 秒末影刹车播报在刚起音时
 * 就被线性衰减到静音；玩家坐在 Sable 座椅上时服务端 {@code player.getX()/Y()/Z()} 落在
 * {@code ~1.6×10^7} 的 plot 空间，坐标还会整体错位。</p>
 *
 * <p>本包只负责「该播什么、多大声、多高音」，真正的实例由客户端
 * {@link TrainBrakeSoundClient#playGlobal} 用 {@code Attenuation.NONE + relative} 建在原点：
 * 不随车速衰减、不被 Sable {@code SoundEngineMixin} 包成 {@code MovingSoundInstanceDelegate}、
 * 不依赖任何世界坐标，同时仍尊重 {@code SoundSource.BLOCKS} 的音量条。</p>
 */
public record GlobalTrainBrakeSoundPayload(float volume, float pitch, long seed)
        implements CustomPacketPayload {

    public static final Type<GlobalTrainBrakeSoundPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("dimblend_experience", "global_train_brake_sound"));

    public static final StreamCodec<FriendlyByteBuf, GlobalTrainBrakeSoundPayload> STREAM_CODEC =
            StreamCodec.of(
                    (FriendlyByteBuf buf, GlobalTrainBrakeSoundPayload payload) -> {
                        buf.writeFloat(payload.volume());
                        buf.writeFloat(payload.pitch());
                        buf.writeLong(payload.seed());
                    },
                    (FriendlyByteBuf buf) -> new GlobalTrainBrakeSoundPayload(
                            buf.readFloat(), buf.readFloat(), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端落地口：与 {@code TrainBrakeSoundPayload#applyOnClient} 同型。
     *
     * <p>先用 {@code FMLEnvironment.dist == Dist.CLIENT} 钉死物理侧，再按 Simurail 存在与否门控：
     * 专用服务器上 {@code TrainBrakeSoundClient}（{@code @OnlyIn(Dist.CLIENT)}，会被
     * {@code RuntimeDistCleaner} 剥掉）绝不能被解析或加载，所以调用只能落在两重门之后。</p>
     */
    public static void applyOnClient(GlobalTrainBrakeSoundPayload payload) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        if (!ModList.get().isLoaded("simurail")) {
            return;
        }
        TrainBrakeSoundClient.playGlobal(payload.volume(), payload.pitch(), payload.seed());
    }
}
