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
 *
 * <p>Lane names are {@link BandLayout#laneName}'s ({@code surface}, {@code underground},
 * {@code nether}, {@code end}, {@code twilight}, {@code starlight}, ... or {@code mod}).
 * Answers include {@link RegionOverrides} because the layout honours them.
 */
public final class RegionLanes {
    public static boolean isSurface(Level level, BlockPos worldPos) {
        RotatingChunkGenerator rotating = rotating(level);
        return rotating != null && BandLayout.isSurfaceOverworld(rotating.delegateForBlockX(worldPos.getX()));
    }

    /** Region width in blocks, or 0 when {@code level} is not the rotating dimension. */
    public static int bandSize(Level level) {
        RotatingChunkGenerator rotating = rotating(level);
        return rotating == null ? 0 : rotating.bandSize();
    }

    /** Region index of a world block X (negative on the negative side), or 0 outside the rotating dimension. */
    public static int regionOfBlockX(Level level, int blockX) {
        RotatingChunkGenerator rotating = rotating(level);
        return rotating == null ? 0 : BandLayout.regionOfBlockX(blockX, rotating.bandSize());
    }

    /**
     * Lane name of {@code region}, or the empty string outside the rotating dimension and before
     * the seeded layout exists.
     *
     * <p>{@code layoutOrNull} rather than {@code layout()}: callers on tick paths (the chase
     * director's nest candidates) may run before {@code createState} has published a runtime, and
     * {@code layout()} throws there. An empty answer means "not known yet", never "no lane".
     */
    public static String laneName(Level level, int region) {
        RotatingChunkGenerator rotating = rotating(level);
        if (rotating == null) {
            return "";
        }
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return "";
        }
        return BandLayout.laneName(layout.delegate(region));
    }

    private static RotatingChunkGenerator rotating(Level level) {
        return level instanceof ServerLevel server
                && server.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)
                && server.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating
                ? rotating
                : null;
    }

    private RegionLanes() {}
}
