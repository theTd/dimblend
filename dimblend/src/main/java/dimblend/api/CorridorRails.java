package dimblend.api;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.CorridorTrackProfile;
import dimblend.worldgen.OakTrackCorridor;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.StarterDepot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Public boundary for features which require the generated main line. */
public final class CorridorRails {
    private CorridorRails() {}

    public static boolean isGeneratedTrack(ServerLevel level, BlockPos pos) {
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL
                || pos.getY() != OakTrackCorridor.TRACK_Y || pos.getZ() != OakTrackCorridor.CORRIDOR_Z
                || !(level.getChunkSource().getGenerator() instanceof RotatingChunkGenerator generator)
                || (generator.hasStarterDepot() && StarterDepot.removesTrack(pos.getX()))) {
            return false;
        }
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof TrackBlock && state.getValue(TrackBlock.SHAPE) == TrackShape.XO
                && BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(
                        CorridorTrackProfile.forDelegate(generator.delegateForBlockX(pos.getX())).trackId());
    }
}
