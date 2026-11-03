package dimblend.radio.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 电台状态包的线与字段对齐（2026-11-03 追加 volumePercent）。
 *
 * <p>这个包是手写 codec，读写顺序必须逐字段对称：字段加在记录末尾、codec 读写也加在
 * 末尾，任何一处错位都不会在编译期报错，只会让客户端把音量读成别的字段。所以用往返
 * 测试把顺序钉死。
 */
class RadioStatePayloadTest {

    @Test
    void roundTripsWithTheVolumeFieldLast() {
        RadioStatePayload original = new RadioStatePayload(
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"),
                new BlockPos(123, 64, -7), 5, 12, "abcdef012345", 987654321L, 3, true, 111222333L, 120);
        var buffer = Unpooled.buffer();
        try {
            RadioStatePayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, RadioStatePayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes(), "编码后必须恰好读完，多出字节说明少写了一条字段");
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsTheDynamicAndSilentCases() {
        // 0% 是「动态」这个哨兵值，也是「本刻不播」；两者都必须原样过线。
        for (int volume : new int[] {0, 10, 150}) {
            RadioStatePayload original = new RadioStatePayload(
                    ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO,
                    1, 1, "", 0L, 0, false, 0L, volume);
            var buffer = Unpooled.buffer();
            try {
                RadioStatePayload.STREAM_CODEC.encode(buffer, original);
                assertEquals(original, RadioStatePayload.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}