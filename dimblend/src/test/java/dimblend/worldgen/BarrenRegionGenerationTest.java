package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.RegionOverrides;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The override → delegate decision that makes a forced region generate as barren End, and the
 * {@link RegionOverrides} read path that feeds it. The End delegate index is discovered from the
 * assigner kind array, which is exactly what {@link BandLayout} passes in.
 */
class BarrenRegionGenerationTest {
    /** Production shape of a rotating layout: END is at index 3, VOIDSCAPE at 4. */
    private static final byte[] KINDS = {
            BandLaneAssigner.SURFACE,
            BandLaneAssigner.UNDERGROUND,
            BandLaneAssigner.NETHER,
            BandLaneAssigner.END,
            BandLaneAssigner.VOIDSCAPE
    };
    private static final int END_INDEX = 3;

    @AfterEach
    void detachFakeOverrides() {
        RegionOverrides.Impl.install(RegionOverrides.Impl.NONE);
    }

    @Test
    void forcedRegionResolvesToTheFirstEndDelegate() {
        force(40);
        assertTrue(BarrenRegionGeneration.isBarrenRegion(40));
        assertEquals(END_INDEX, BarrenRegionGeneration.delegateIndexFor(KINDS, 40, 0),
                "the seeded draw is replaced by the End delegate");
        assertEquals(END_INDEX, BarrenRegionGeneration.delegateIndexFor(KINDS, 40, 4),
                "even when the draw would have been another lane");
    }

    @Test
    void neighbourAndOtherRegionsKeepTheirOwnDelegate() {
        force(40);
        assertFalse(BarrenRegionGeneration.isBarrenRegion(39));
        assertFalse(BarrenRegionGeneration.isBarrenRegion(41));
        assertEquals(1, BarrenRegionGeneration.delegateIndexFor(KINDS, 39, 1));
        assertEquals(4, BarrenRegionGeneration.delegateIndexFor(KINDS, 41, 4));
    }

    @Test
    void noOverrideMeansNoChange() {
        assertFalse(BarrenRegionGeneration.isBarrenRegion(40));
        assertEquals(2, BarrenRegionGeneration.delegateIndexFor(KINDS, 40, 2));
    }

    @Test
    void endIndexIsTheFirstEndLaneInTheArray() {
        assertEquals(END_INDEX, BarrenRegionGeneration.endDelegateIndex(KINDS));
        assertEquals(0, BarrenRegionGeneration.endDelegateIndex(
                new byte[] {BandLaneAssigner.END, BandLaneAssigner.END, BandLaneAssigner.SURFACE}));
    }

    @Test
    void layoutWithoutAnEndDelegateKeepsTheAssignedIndex() {
        byte[] noEnd = {BandLaneAssigner.SURFACE, BandLaneAssigner.UNDERGROUND, BandLaneAssigner.NETHER};
        assertEquals(-1, BarrenRegionGeneration.endDelegateIndex(noEnd));
        force(40);
        assertEquals(2, BarrenRegionGeneration.delegateIndexFor(noEnd, 40, 2),
                "a layout that cannot generate End keeps its normal delegate instead of crashing");
    }

    /** Installs a fake api implementation: writes always fail, only {@code regions} are barren. */
    private static void force(int... regions) {
        Set<Integer> barren = new HashSet<>();
        for (int region : regions) {
            barren.add(region);
        }
        RegionOverrides.Impl.install(new RegionOverrides.Impl() {
            @Override
            public boolean forceLane(MinecraftServer server, int region, RegionOverrides.Override override) {
                return false;
            }

            @Override
            public Optional<RegionOverrides.Override> overrideOf(int region) {
                return barren.contains(region)
                        ? Optional.of(RegionOverrides.Override.END_BARREN)
                        : Optional.empty();
            }
        });
    }
}
