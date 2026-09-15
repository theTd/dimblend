package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class YShiftedStructureElevationTest {
    @Test
    void sampleWithoutAbsoluteYShiftClearsOffsetDuringSample() {
        RecordingExt ext = new RecordingExt(64);
        int[] seen = {-1};
        int result = YShiftedStructureElevation.sampleWithoutAbsoluteYShift(ext, () -> {
            seen[0] = ext.dimblend$absoluteOffset();
            return -3;
        });
        assertEquals(0, seen[0]);
        assertEquals(-3, result);
        assertEquals(64, ext.dimblend$absoluteOffset());
    }

    @Test
    void sampleWithoutAbsoluteYShiftIsNoopWhenUnshifted() {
        RecordingExt ext = new RecordingExt(0);
        int result = YShiftedStructureElevation.sampleWithoutAbsoluteYShift(ext, () -> {
            assertEquals(0, ext.dimblend$absoluteOffset());
            return 12;
        });
        assertEquals(12, result);
        assertEquals(0, ext.dimblend$absoluteOffset());
    }

    @Test
    void sampleWithoutAbsoluteYShiftRestoresOffsetWhenSampleThrows() {
        RecordingExt ext = new RecordingExt(64);
        assertThrows(IllegalStateException.class, () -> YShiftedStructureElevation.sampleWithoutAbsoluteYShift(ext, () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(64, ext.dimblend$absoluteOffset());
    }

    private static final class RecordingExt implements WorldGenerationContextExtension {
        private int offset;

        private RecordingExt(int offset) {
            this.offset = offset;
        }

        @Override
        public int dimblend$absoluteOffset() {
            return this.offset;
        }

        @Override
        public void dimblend$setAbsoluteOffset(int offset) {
            this.offset = offset;
        }
    }
}
