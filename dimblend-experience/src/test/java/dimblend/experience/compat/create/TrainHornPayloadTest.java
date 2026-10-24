package dimblend.experience.compat.create;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrainHornPayloadTest {
    @Test
    void startAndStopRoundTrip() {
        for (TrainHornPayload original : new TrainHornPayload[]{
                new TrainHornPayload(new BlockPos(12, 60, -34), (byte) 2, 1.25F, true),
                new TrainHornPayload(new BlockPos(-8, 72, 19), (byte) 0, 1.0F, false)}) {
            var buffer = Unpooled.buffer();
            try {
                TrainHornPayload.STREAM_CODEC.encode(buffer, original);
                assertEquals(original, TrainHornPayload.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
