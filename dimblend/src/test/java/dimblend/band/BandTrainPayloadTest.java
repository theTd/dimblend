package dimblend.band;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * {@link BandTrainPayload} codec: the marker override needs the alert bit on the wire, and the
 * chase writes it every sync (red line to the nest, requirements §3.3).
 */
class BandTrainPayloadTest {
    @Test
    void roundTripsPositionAndAlert() {
        for (boolean alert : new boolean[] {false, true}) {
            BandTrainPayload original = new BandTrainPayload(12345.5, alert);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                BandTrainPayload.STREAM_CODEC.encode(buffer, original);
                BandTrainPayload decoded = BandTrainPayload.STREAM_CODEC.decode(buffer);
                assertEquals(original, decoded);
                assertEquals(alert, decoded.alert(), "the alert bit must survive the wire");
                assertFalse(buffer.isReadable(), "the codec must consume exactly one payload");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void alertIsPartOfPayloadIdentitySoTheSyncResendsOnColourChange() {
        // BandTrainSync's dedupe stores whole payloads: a colour flip at the same x must count as
        // a change, otherwise the line stays white while the nest is alarmed.
        assertNotEquals(new BandTrainPayload(1000.0, false), new BandTrainPayload(1000.0, true));
        assertEquals(new BandTrainPayload(1000.0, true), new BandTrainPayload(1000.0, true));
    }

    @Test
    void overridePushesAreDistinctFromOrdinaryPushes() {
        Set<BandTrainPayload> distinct = new HashSet<>();
        assertTrue(distinct.add(new BandTrainPayload(0.0, false)));
        assertTrue(distinct.add(new BandTrainPayload(0.0, true)));
        assertTrue(distinct.add(new BandTrainPayload(1.0, false)));
        assertEquals(3, distinct.size());
    }
}
