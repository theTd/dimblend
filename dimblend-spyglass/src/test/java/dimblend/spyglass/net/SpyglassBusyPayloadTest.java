package dimblend.spyglass.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class SpyglassBusyPayloadTest {
    @Test
    void roundTripsTheFlag() {
        for (boolean busy : new boolean[] {true, false}) {
            SpyglassBusyPayload original = new SpyglassBusyPayload(busy);
            ByteBuf buffer = Unpooled.buffer();
            try {
                SpyglassBusyPayload.STREAM_CODEC.encode(buffer, original);
                SpyglassBusyPayload decoded = SpyglassBusyPayload.STREAM_CODEC.decode(buffer);
                assertEquals(original, decoded);
                assertEquals(busy, decoded.busy());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void decodesTheTwoWireValues() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeBoolean(true);
            assertTrue(SpyglassBusyPayload.STREAM_CODEC.decode(buffer).busy());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
