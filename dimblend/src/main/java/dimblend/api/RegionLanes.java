package dimblend.api;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Public boundary for server rules that depend on a rotating region's identity.
 * Positions must already be projected to world coordinates by the caller.
 */
public final class RegionLanes {
    public static boolean isSurface(Level level, BlockPos worldPos) {
        return level instanceof ServerLevel server
                && server.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)
                && server.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating
                && BandLayout.isSurfaceOverworld(rotating.delegateForBlockX(worldPos.getX()));
    }

    private RegionLanes() {}
}
