package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

class SliceTickShiftTest {
    private static final String SPRING = "spring";
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    private static ProtoChunkTicks<String> ticksAt(int... ys) {
        ProtoChunkTicks<String> ticks = new ProtoChunkTicks<>();
        for (int y : ys) {
            ticks.schedule(new ScheduledTick<>(SPRING, new BlockPos(5, y, 7), 0L, TickPriority.NORMAL, 0L));
        }
        return ticks;
    }

    @Test
    void undergroundTickMovesWithTheBlock() {
        ProtoChunkTicks<String> shifted = SliceTickShift.shifted(ticksAt(10), OverworldSlice.UNDERGROUND, MIN_Y, MAX_Y);

        assertEquals(1, shifted.count());
        assertTrue(shifted.hasScheduledTick(new BlockPos(5, 74, 7), SPRING));
        assertFalse(shifted.hasScheduledTick(new BlockPos(5, 10, 7), SPRING));
    }

    @Test
    void ticksOutsideTheSliceSourceRangeAreDropped() {
        // sourceMin -64 .. sourceMaxExclusive 32: -65 is below, 32 is the first sealed row.
        ProtoChunkTicks<String> shifted = SliceTickShift.shifted(
                ticksAt(-65, -64, 31, 32, 100), OverworldSlice.UNDERGROUND, MIN_Y, MAX_Y);

        assertEquals(2, shifted.count());
        assertTrue(shifted.hasScheduledTick(new BlockPos(5, 0, 7), SPRING));
        assertTrue(shifted.hasScheduledTick(new BlockPos(5, 95, 7), SPRING));
    }

    @Test
    void ticksLandingOutsideBuildHeightAreDropped() {
        ProtoChunkTicks<String> shifted = SliceTickShift.shifted(ticksAt(31), OverworldSlice.UNDERGROUND, MIN_Y, 90);

        assertEquals(0, shifted.count());
    }

    @Test
    void priorityAndHorizontalPositionSurvive() {
        ProtoChunkTicks<String> source = new ProtoChunkTicks<>();
        source.schedule(new ScheduledTick<>(SPRING, new BlockPos(-3, 0, 12), 0L, TickPriority.HIGH, 0L));

        SavedTick<String> moved = SliceTickShift.shifted(source, OverworldSlice.UNDERGROUND, MIN_Y, MAX_Y)
                .scheduledTicks().get(0);

        assertEquals(new BlockPos(-3, 64, 12), moved.pos());
        assertEquals(TickPriority.HIGH, moved.priority());
    }
}
