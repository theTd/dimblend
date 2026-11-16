package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The lock-free {@code delegateIndex} fast path must never change what the sequential walk
 * assigns: the published arrays are a cache of {@code cache}, not a second source of truth.
 */
class BandLaneAssignerCacheTest {
    private static final byte[] PRODUCTION_KINDS = {
            BandLaneAssigner.SURFACE,
            BandLaneAssigner.UNDERGROUND,
            BandLaneAssigner.NETHER,
            BandLaneAssigner.END,
            BandLaneAssigner.AETHER,
            BandLaneAssigner.TWILIGHT,
            BandLaneAssigner.STARLIGHT,
            BandLaneAssigner.OTHERSIDE,
            BandLaneAssigner.VOIDSCAPE
    };
    private static final long SEED = 424242L;
    private static final int RANGE = 200;
    private static final int WIDTH = RANGE * 2 + 1;

    /** Ascending {@code -RANGE..RANGE}: every call is a first-time walk from spawn. */
    @Test
    void ascendingOrderMatchesItselfAcrossFreshInstances() {
        int[] first = new int[WIDTH];
        int[] second = new int[WIDTH];
        BandLaneAssigner a = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        BandLaneAssigner b = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        for (int region = -RANGE; region <= RANGE; region++) {
            first[region + RANGE] = a.delegateIndex(region);
            second[region + RANGE] = b.delegateIndex(region);
        }
        assertArrayEquals(first, second);
    }

    @Test
    void descendingOrderMatchesAscendingOrder() {
        assertMatchesCanonical(descending());
    }

    @Test
    void randomJumpsMatchAscendingOrder() {
        for (long seed = 0; seed < 8; seed++) {
            List<Integer> regions = ascending();
            Collections.shuffle(regions, new Random(seed));
            assertMatchesCanonical(regions);
        }
    }

    @Test
    void interleavedSignsMatchAscendingOrder() {
        List<Integer> regions = new ArrayList<>(WIDTH);
        for (int distance = 0; distance <= RANGE; distance++) {
            regions.add(distance);
            if (distance != 0) {
                regions.add(-distance);
            }
        }
        assertMatchesCanonical(regions);
    }

    @Test
    void repeatedLookupsStayStable() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        int[] expected = canonical();
        List<Integer> regions = ascending();
        Collections.shuffle(regions, new Random(7L));
        for (int round = 0; round < 4; round++) {
            for (int region : regions) {
                assertEquals(expected[region + RANGE], assigner.delegateIndex(region), "region " + region);
            }
        }
    }

    @Test
    void concurrentLookupsMatchSingleThreadedAssignments() throws Exception {
        int[] expected = canonical();
        BandLaneAssigner shared = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                List<Integer> regions = ascending();
                Collections.shuffle(regions, new Random(thread));
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int region : regions) {
                            int actual = shared.delegateIndex(region);
                            int want = expected[region + RANGE];
                            if (actual != want) {
                                throw new AssertionError("region " + region + ": expected " + want + ", got " + actual);
                            }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finished.await(120, TimeUnit.SECONDS), "workers should finish");
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent lookup mismatch", failure.get());
        }
    }

    @Test
    void everyAssignedDelegateIsAValidIndex() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        for (int region : ascending()) {
            int index = assigner.delegateIndex(region);
            assertTrue(index >= 0 && index < PRODUCTION_KINDS.length, "region " + region + " -> " + index);
        }
    }

    @Test
    void outOfBoundsRegionsFailFastInsteadOfWalkingTwoBillionSteps() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.delegateIndex(Integer.MIN_VALUE));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.delegateIndex(Integer.MAX_VALUE));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.delegateIndex(BandLaneAssigner.MAX_REGION_DISTANCE + 1));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.delegateIndex(-BandLaneAssigner.MAX_REGION_DISTANCE - 1));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.scriptedOutwardDelegate(Integer.MAX_VALUE));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.scriptedOutwardDelegate(BandLaneAssigner.MAX_REGION_DISTANCE));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> assigner.scriptedOutwardDelegate(-BandLaneAssigner.MAX_REGION_DISTANCE));
    }

    private static void assertMatchesCanonical(List<Integer> regions) {
        int[] expected = canonical();
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        for (int region : regions) {
            assertEquals(expected[region + RANGE], assigner.delegateIndex(region), "region " + region);
        }
    }

    private static int[] canonical() {
        BandLaneAssigner assigner = new BandLaneAssigner(PRODUCTION_KINDS, SEED);
        int[] results = new int[WIDTH];
        for (int region = -RANGE; region <= RANGE; region++) {
            results[region + RANGE] = assigner.delegateIndex(region);
        }
        return results;
    }

    private static List<Integer> ascending() {
        List<Integer> regions = new ArrayList<>(WIDTH);
        for (int region = -RANGE; region <= RANGE; region++) {
            regions.add(region);
        }
        return regions;
    }

    private static List<Integer> descending() {
        List<Integer> regions = ascending();
        Collections.reverse(regions);
        return regions;
    }
}
