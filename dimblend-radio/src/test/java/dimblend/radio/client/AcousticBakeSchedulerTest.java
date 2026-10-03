package dimblend.radio.client;

import dimblend.radio.acoustics.ReflectionGeometry;
import dimblend.radio.acoustics.bake.PathingBake;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticBakeSchedulerTest {
    private static final PathingBake BAKE = new PathingBake(BlockPos.ZERO, new long[] {10, 11, 12, 13},
            new long[] {100, ReflectionGeometry.AIR, 300, 400}, new float[3], 4, new byte[1]);

    @Test
    void onlySectionsWhoseBlocksDifferCountAsChanged() {
        assertArrayEquals(new long[0], AcousticBakeScheduler.changedSections(BAKE, BAKE.sectionKeys(), BAKE.sectionStates()));
        assertArrayEquals(new long[] {11, 13}, AcousticBakeScheduler.changedSections(BAKE, new long[] {10, 11, 12, 13},
                new long[] {100, 200, 300, 401}), "a block placed in air, one edited");
    }

    @Test
    void anUnloadedSectionIsUnknownAndANewOneChanged() {
        assertArrayEquals(new long[0], AcousticBakeScheduler.changedSections(BAKE, new long[] {10, 11, 12, 13},
                new long[] {ReflectionGeometry.UNLOADED, ReflectionGeometry.AIR, ReflectionGeometry.UNCAPTURED, 400}));
        assertArrayEquals(new long[] {14}, AcousticBakeScheduler.changedSections(BAKE, new long[] {10, 14},
                new long[] {100, ReflectionGeometry.AIR}), "not in the bake at all");
    }
}
