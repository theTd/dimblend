package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RotatingBandRangesTest {
    @Test
    void mergesAdjacentRegionsWithTheSameDelegate() {
        List<RotatingBandRanges.Range> ranges = RotatingBandRanges.split(
                -200, 200, BandIndex.DEFAULT_BAND_SIZE, blockX -> 0);

        assertEquals(List.of(new RotatingBandRanges.Range(0, -200, 200)), ranges);
    }

    @Test
    void splitsAtBandBoundariesAroundZero() {
        List<RotatingBandRanges.Range> ranges = RotatingBandRanges.split(
                -3, 3, BandIndex.DEFAULT_BAND_SIZE, blockX -> blockX < 0 ? 1 : 0);

        assertEquals(List.of(
                new RotatingBandRanges.Range(1, -3, -1),
                new RotatingBandRanges.Range(0, 0, 3)), ranges);
    }

    @Test
    void clipsRangesToTheWindowAndKeepsOneChunkPerBand() {
        List<RotatingBandRanges.Range> ranges = RotatingBandRanges.split(
                -2, 2, 16, blockX -> blockX < 0 ? 1 : 0);

        assertEquals(List.of(
                new RotatingBandRanges.Range(1, -2, -1),
                new RotatingBandRanges.Range(0, 0, 2)), ranges);
    }

    @Test
    void doesNotMergeDifferentDelegates() {
        List<RotatingBandRanges.Range> ranges = RotatingBandRanges.split(
                -2, 2, 16, blockX -> Math.floorMod(blockX / 16, 2));

        assertEquals(List.of(
                new RotatingBandRanges.Range(0, -2, -2),
                new RotatingBandRanges.Range(1, -1, -1),
                new RotatingBandRanges.Range(0, 0, 0),
                new RotatingBandRanges.Range(1, 1, 1),
                new RotatingBandRanges.Range(0, 2, 2)), ranges);
    }

    @Test
    void clipsTheFirstAndLastRegionWhenTheWindowIsNotBandAligned() {
        List<RotatingBandRanges.Range> ranges = RotatingBandRanges.split(
                -10, 200, BandIndex.DEFAULT_BAND_SIZE, blockX -> Math.floorDiv(blockX, 2048) + 10);

        assertEquals(List.of(
                new RotatingBandRanges.Range(9, -10, -1),
                new RotatingBandRanges.Range(10, 0, 127),
                new RotatingBandRanges.Range(11, 128, 200)), ranges);
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> RotatingBandRanges.split(0, 1, 24, blockX -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> RotatingBandRanges.split(5, 1, 16, blockX -> 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RotatingBandRanges.Range(-1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RotatingBandRanges.Range(0, 5, 1));
    }
}
