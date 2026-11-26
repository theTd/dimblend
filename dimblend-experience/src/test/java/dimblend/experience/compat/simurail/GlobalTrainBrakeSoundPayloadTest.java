package dimblend.experience.compat.simurail;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 全图刹车播报包 ({@link GlobalTrainBrakeSoundPayload}) 与持续鸣响去重签名的回归
 * （2026-11-27 Slice 10）。
 *
 * <p>两条不变量：
 * <ol>
 * <li>编解码精确往返，且「音量 / 音调 / 种子」三个参数不得互相串位（0.9 与 0.5 这类
 *     不对称取值才能暴露交换写入）；种子含 {@code Long.MIN_VALUE} 这类边界值也要原样过网——
 *     服务端与客户端必须对同一个事件播同一条样本。</li>
 * <li>{@code TrainBrakeSoundSync} 的去重签名必须含音调：轻刹 {@code 1.0} 的包刚发出，
 *     同一 tick 内穿进龙息又发 {@code 0.5} 急刹包，2 tick 合并窗不能把后者当成重复吞掉。</li>
 * </ol>
 */
class GlobalTrainBrakeSoundPayloadTest {

    private static final ResourceLocation ROTATING =
            ResourceLocation.fromNamespaceAndPath("dimblend", "rotating");
    private static final ResourceLocation OTHER =
            ResourceLocation.fromNamespaceAndPath("dimblend", "other");

    @Test
    void codecRoundTripsVolumePitchAndSeedWithoutSwappingThem() {
        long[] seeds = {0L, 1L, -1L, 0x0123_4567_89AB_CDEFL, Long.MIN_VALUE, Long.MAX_VALUE};
        float[][] sounds = {{0.65F, 1.0F}, {0.9F, 0.5F}, {0.0F, 0.0F}, {1.0F, 2.0F}};
        for (long seed : seeds) {
            for (float[] pair : sounds) {
                GlobalTrainBrakeSoundPayload original =
                        new GlobalTrainBrakeSoundPayload(pair[0], pair[1], seed);
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    GlobalTrainBrakeSoundPayload.STREAM_CODEC.encode(buffer, original);
                    // 2×float + long，定长 16 字节
                    assertEquals(16, buffer.readableBytes());
                    GlobalTrainBrakeSoundPayload decoded =
                            GlobalTrainBrakeSoundPayload.STREAM_CODEC.decode(buffer);
                    assertEquals(original, decoded);
                    assertEquals(0, buffer.readableBytes(), "解码必须吃完整个包");
                    assertEquals(pair[0], decoded.volume(), 0.0F, "音量必须原样过网");
                    assertEquals(pair[1], decoded.pitch(), 0.0F, "音调必须原样过网（0.5 就是末影急刹）");
                    assertEquals(seed, decoded.seed(), "种子必须原样过网，两端播同一变体");
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void dedupeSignatureIncludesPitchSoTheEmergencyStartSurvivesTheMergeWindow() {
        BlockPos left = new BlockPos(20_480_000, 70, 20_480_010);
        BlockPos right = new BlockPos(20_480_000, 70, 20_480_020);
        List<BlockPos> ordered = List.of(left, right);
        List<BlockPos> reversed = List.of(right, left);

        TrainBrakeSoundSync.Signature normal =
                TrainBrakeSoundSync.signatureOf(ROTATING, true, ordered, 1.0F);
        TrainBrakeSoundSync.Signature emergency =
                TrainBrakeSoundSync.signatureOf(ROTATING, true, ordered, 0.5F);
        assertNotEquals(normal, emergency,
                "同 2 tick 内 0.5 急刹包不能被 1.0 常规包的签名吞掉");

        assertEquals(normal, TrainBrakeSoundSync.signatureOf(ROTATING, true, reversed, 1.0F),
                "同一张车架表从任何一架触发（传参顺序不同）必须得到同一签名");
        assertNotEquals(normal, TrainBrakeSoundSync.signatureOf(ROTATING, true, ordered, 0.25F),
                "音调不同就是不同事件");
        assertNotEquals(normal, TrainBrakeSoundSync.signatureOf(OTHER, true, ordered, 1.0F),
                "不同维度不得共用一个去重位");
        assertNotEquals(normal, TrainBrakeSoundSync.signatureOf(ROTATING, false, ordered, 1.0F),
                "停止包必须与开始包分开");
        assertNotEquals(normal, TrainBrakeSoundSync.signatureOf(ROTATING, true, List.of(left), 1.0F),
                "少一架车架就是另一条事件");
    }
}
