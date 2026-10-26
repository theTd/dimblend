package dimblend.experience.headlight;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loaded-only block reads for the shared lit-lava scan and the observer blaze's lamp lists.
 *
 * <p>On a {@code ClientLevel} a read of an unloaded cell simply answers void air, but on a {@code ServerLevel}
 * {@code Level.getBlockState} goes through {@code ServerChunkCache.getChunk(..., FULL, true)}: it adds a ticket and
 * blocks the tick until the chunk is generated ({@code Level.java:201-211}, {@code ServerChunkCache.java:136-190}).
 * A scan that touches the edge of the loaded region -- the plot-side lamp of a vehicle, or the DDA of an occlusion
 * trace that starts at a lens in an unloaded chunk -- would therefore stall the server, or throw when the chunk
 * cannot be created at all. Every server-side read asks the level first; a cell in an unloaded chunk is reported as
 * unknown and the scan treats it as it treats client-side void air: no block, no occluder, no forced load.</p>
 */
public final class LoadedChunks {
    /**
     * The cell's state when its chunk is already loaded, else {@code null} ("unknown"). Above and below the build
     * height the vanilla void-air answer is returned, so a scan at the top of the world behaves exactly as it does
     * on the client, where {@code Level.getBlockState} never loads and answers void air outside the build height.
     */
    @Nullable
    public static BlockState state(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        return level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }

    /**
     * Whether the 3x3 chunk neighbourhood of a cell is already loaded. Vanilla shapes of fences, walls, panes and
     * similar blocks read their horizontal neighbours when asked whether they are a full block, so a
     * {@code isSolidRender} answer near the edge of the loaded region could otherwise pull a chunk in. On the client
     * this always answers true ({@code ClientLevel.hasChunk} answers true for every position), so the outline there
     * is unchanged.
     */
    public static boolean shapeNeighbourhoodLoaded(Level level, int x, int y, int z) {
        for (int dx = -16; dx <= 16; dx += 16) {
            for (int dz = -16; dz <= 16; dz += 16) {
                if (!level.isLoaded(new BlockPos(x + dx, y, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private LoadedChunks() {
    }
}
