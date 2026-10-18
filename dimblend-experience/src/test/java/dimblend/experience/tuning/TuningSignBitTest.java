package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class TuningSignBitTest {
    @Test
    void everyAvailabilityBitIsIndependentIncludingOptionsBeyondThirtyTwo() {
        assertTrue(TuningOption.values().length <= Long.SIZE, "availability mask cannot hold more than 64 options");
        assertTrue(TuningOption.SABLE_HEADLIGHT.ordinal() >= Integer.SIZE);
        for (var enabled : TuningOption.values()) {
            var snapshot = new TuningSnapshotPayload(false, false, 1L << enabled.ordinal(),
                    Arrays.stream(TuningOption.values()).map(TuningOption::defaultValue).toList(), "", "");
            for (var option : TuningOption.values()) {
                assertEquals(option == enabled, snapshot.available(option), enabled + " aliases " + option);
            }
        }
    }

    @Test
    void snapshotRoundTripPreservesHighBitsAndEveryValue() {
        long mask = (1L << TuningOption.COPPER_SINK_INFINITE.ordinal())
                | (1L << TuningOption.SABLE_HEADLIGHT_RADIUS.ordinal());
        var original = new TuningSnapshotPayload(true, true, mask,
                Arrays.stream(TuningOption.values()).map(TuningOption::defaultValue).toList(), "sable_headlight_radius", "saved");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TuningSnapshotPayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, TuningSnapshotPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
