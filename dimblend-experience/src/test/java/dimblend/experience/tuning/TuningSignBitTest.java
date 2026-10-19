package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class TuningSignBitTest {
    @Test
    void everyAvailabilityBitIsIndependentIncludingOptionsBeyondThirtyTwo() {
        assertTrue(TuningOption.values().length <= Long.SIZE);
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
                | (1L << TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.ordinal());
        var original = new TuningSnapshotPayload(true, true, mask,
                Arrays.stream(TuningOption.values()).map(TuningOption::defaultValue).toList(), "sable_headlight_fog_samples", "saved");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TuningSnapshotPayload.STREAM_CODEC.encode(buffer, original);
            assertEquals(original, TuningSnapshotPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void headlightsHaveExperienceOwnershipAndTheRequestedSteps() {
        for (var option : TuningOption.values()) {
            if (!option.headlight()) continue;
            assertEquals(TuningOption.Section.SABLE, option.section());
            assertFalse(option.sable(), "headlights must not be routed into Sable's physics config");
            assertTrue(option.isValid(option.defaultValue()));
        }
        assertEquals(0.5, TuningOption.SABLE_HEADLIGHT_RANGE.step());
        assertTrue(TuningOption.SABLE_HEADLIGHT_RANGE.isValid(24.5));
        assertFalse(TuningOption.SABLE_HEADLIGHT_RANGE.isValid(24.1));
        assertEquals(0.05, TuningOption.SABLE_HEADLIGHT_INTENSITY.step());
        assertTrue(TuningOption.SABLE_HEADLIGHT_INTENSITY.isValid(1.05));
        assertFalse(TuningOption.SABLE_HEADLIGHT_INTENSITY.isValid(1.01));
        assertFalse(TuningOption.SABLE_HEADLIGHT_ANGLE.isValid(50.5));
        assertTrue(TuningOption.SABLE_HEADLIGHT_FOG.isCheckbox());
        assertEquals(1.0, TuningOption.SABLE_HEADLIGHT_FOG.defaultValue());
        assertEquals(0.35, TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.defaultValue());
        assertEquals(0.01, TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.step());
        assertTrue(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.isValid(0));
        assertTrue(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.isValid(2));
        assertFalse(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.isValid(2.01));
        assertFalse(TuningOption.SABLE_HEADLIGHT_FOG_DENSITY.isValid(0.355));
        assertEquals(8, TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.defaultValue());
        assertEquals(1, TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.step());
        assertTrue(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.isValid(4));
        assertTrue(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.isValid(24));
        assertFalse(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.isValid(3));
        assertFalse(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.isValid(25));
        assertFalse(TuningOption.SABLE_HEADLIGHT_FOG_SAMPLES.isValid(8.5));
    }
}
