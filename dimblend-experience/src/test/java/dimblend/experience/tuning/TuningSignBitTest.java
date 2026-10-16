package dimblend.experience.tuning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TuningSignBitTest {
    @Test
    void lastOptionUsesSignBitWithoutAliasingEarlierOptions() {
        assertTrue(TuningOption.values().length <= Integer.SIZE, "availability mask cannot hold more than 32 options");
        var sink = TuningOption.COPPER_SINK_INFINITE;
        var snapshot = new TuningSnapshotPayload(false, false, 1 << sink.ordinal(),
                Arrays.stream(TuningOption.values()).map(TuningOption::defaultValue).toList(), "", "");
        for (var option : TuningOption.values()) {
            assertEquals(option == sink, snapshot.available(option));
        }
    }
}
