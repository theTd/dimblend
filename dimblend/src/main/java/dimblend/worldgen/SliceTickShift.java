package dimblend.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * Moves worldgen-scheduled block/fluid ticks together with the blocks of an
 * {@link OverworldSlice} relocation. Features such as springs place a block and then
 * {@code scheduleTick(pos, fluid, 0)} at the source Y; without moving the tick too, the
 * relocated spring cell never gets its first fluid tick and stays a single block.
 */
final class SliceTickShift {
    private SliceTickShift() {
    }

    /**
     * Rebuilds {@code source} with every tick whose source Y lies inside the slice moved by
     * the slice offset. Ticks outside the slice source range or whose target Y falls outside
     * {@code [minBuildY, maxBuildY)} are dropped, matching the blocks that are cleared or sealed.
     */
    static <T> ProtoChunkTicks<T> shifted(ProtoChunkTicks<T> source, OverworldSlice slice, int minBuildY, int maxBuildY) {
        ProtoChunkTicks<T> result = new ProtoChunkTicks<>();
        for (SavedTick<T> tick : source.scheduledTicks()) {
            BlockPos pos = tick.pos();
            if (!slice.containsSourceY(pos.getY())) {
                continue;
            }
            int targetY = slice.toTargetY(pos.getY());
            if (targetY < minBuildY || targetY >= maxBuildY) {
                continue;
            }
            result.schedule(new ScheduledTick<>(
                    tick.type(),
                    new BlockPos(pos.getX(), targetY, pos.getZ()),
                    tick.delay(),
                    tick.priority(),
                    0L));
        }
        return result;
    }
}
