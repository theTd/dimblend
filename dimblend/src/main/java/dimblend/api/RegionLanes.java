package dimblend.api;

import dimblend.DimBlendRegistries;
import dimblend.band.KnownBandSize;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Whether two block columns fall into the same rotating region.
     *
     * <p>Used by the photo-origin HUD ({@code dimblend_experience}): a picture remembers the
     * dimension and block X it was taken at, and the marker is shown only while the player is still
     * in that region. A different dimension is always another place; a non-rotating dimension has a
     * single region, so any position there matches. In the rotating dimension the band size comes
     * from the server generator and from {@link KnownBandSize} on the client (the generator is
     * server-side only); an unknown band size is treated as "different" rather than guessing.
     */
    public static boolean sameRegion(
            Level level, ResourceLocation originDimension, int originBlockX, int currentBlockX) {
        boolean sameDimension = originDimension != null
                && level.dimension().location().equals(originDimension);
        boolean rotatingDimension = level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL);
        int bandSize = rotatingDimension
                ? (level.isClientSide ? KnownBandSize.raw() : bandSize(level))
                : 0;
        return sameRegion(sameDimension, rotatingDimension, bandSize, originBlockX, currentBlockX);
    }

    /** Pure form of {@link #sameRegion(Level, ResourceLocation, int, int)}; tested without a level. */
    static boolean sameRegion(
            boolean sameDimension,
            boolean rotatingDimension,
            int bandSize,
            int originBlockX,
            int currentBlockX) {
        if (!sameDimension) {
            return false;
        }
        if (!rotatingDimension) {
            return true;
        }
        if (bandSize <= 0) {
            return false;
        }
        return BandLayout.regionOfBlockX(originBlockX, bandSize)
                == BandLayout.regionOfBlockX(currentBlockX, bandSize);
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
