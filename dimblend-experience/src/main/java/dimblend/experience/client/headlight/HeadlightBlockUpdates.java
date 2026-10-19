package dimblend.experience.client.headlight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Single fan-out for client block writes (FrontlightBlockChangeMixin): lamp index and occlusion grids. */
public final class HeadlightBlockUpdates {
    public static void clientBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
        FrontlightIndex.blockChanged(chunk, pos, state);
        HeadlightOcclusion.blockChanged(chunk, pos, state);
    }

    private HeadlightBlockUpdates() {}
}
