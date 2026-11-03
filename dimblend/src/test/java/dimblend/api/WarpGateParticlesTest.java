package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 折跃门穿过时的粒子量（用户 2026-11-03：粒子量 x2）。 */
class WarpGateParticlesTest {

    @Test
    void twoParticlesPerOverlappingCell() {
        assertEquals(2, WarpGateParticles.totalParticles(1));
        assertEquals(6, WarpGateParticles.totalParticles(3));
    }

    @Test
    void noOverlapEmitsNothing() {
        assertEquals(0, WarpGateParticles.totalParticles(0));
        assertEquals(0, WarpGateParticles.totalParticles(-4));
        assertArrayEquals(new int[0], WarpGateParticles.sampledIndices(0));
    }

    @Test
    void aWideSlabIsCappedInsteadOfFloodingTheClient() {
        // A big sub-level overlapping the whole vault must not emit thousands per tick.
        int cells = 900;
        assertTrue(WarpGateParticles.totalParticles(cells) <= WarpGateParticles.MAX_PARTICLES,
                "超过上限必须被夹住");
        assertEquals(WarpGateParticles.MAX_PARTICLES, WarpGateParticles.totalParticles(cells));
    }

    @Test
    void samplesSpreadAcrossTheWholeIntersection() {
        // Sampling the first N cells would put every particle at the slab's low corner.
        int[] picked = WarpGateParticles.sampledIndices(400);
        assertEquals(WarpGateParticles.sampleCount(400), picked.length);
        assertTrue(picked[picked.length - 1] > 200,
                "最后一个采样必须落在交集的远半段，否则粒子全堆在西侧角落");
        for (int i = 1; i < picked.length; i++) {
            assertTrue(picked[i] > picked[i - 1], "采样下标必须递增");
        }
    }

    @Test
    void aSmallIntersectionSamplesEveryCellExactlyOnce() {
        int[] picked = WarpGateParticles.sampledIndices(3);
        assertArrayEquals(new int[] {0, 1, 2}, picked);
        assertArrayEquals(new int[] {0}, WarpGateParticles.sampledIndices(1));
    }
}