package dimblend.fluid;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Nether-speed lava inside {@code dimblend:rotating}.
 *
 * <p>Vanilla {@code LavaFluid} reads {@code DimensionType.ultraWarm()} for tick delay
 * (30 → 10), slope find distance (2 → 4) and drop-off (2 → 1). The rotating dimension
 * type is a single {@code ultrawarm: false} entry shared by every band, and flipping it
 * would also evaporate water and change sponges everywhere. So the nether and Voidscape
 * bands answer {@code ultraWarm} per column instead: the three {@code LavaFluid}
 * readers (see {@link dimblend.mixin.LavaFluidUltrawarmMixin}) ask {@link #active}, which
 * resolves the lane of the column pushed by the fluid entry points
 * ({@link dimblend.mixin.FlowingFluidTickMixin}, {@link dimblend.mixin.LiquidBlockLavaFlowMixin}).
 * Those readers only receive a {@code LevelReader}, never a position, hence the
 * thread-local column.
 */
public final class NetherLavaFlow {
    private static final ThreadLocal<Integer> COLUMN = new ThreadLocal<>();

    private NetherLavaFlow() {
    }

    /** Lanes whose lava flows like the real Nether's ({@link BandLayout#laneName}). */
    public static boolean isNetherSpeedLane(String laneName) {
        return "nether".equals(laneName) || "voidscape".equals(laneName);
    }

    /**
     * Marks the column a lava update is running at. Returns the previous column and must
     * be paired with {@link #exit} in a {@code finally}.
     */
    @Nullable
    public static Integer enter(BlockPos pos) {
        Integer outer = COLUMN.get();
        COLUMN.set(pos.getX());
        return outer;
    }

    public static void exit(@Nullable Integer outer) {
        if (outer == null) {
            COLUMN.remove();
        } else {
            COLUMN.set(outer);
        }
    }

    /**
     * True when the lava update in progress sits in a nether-speed band of the rotating
     * dimension. Server only: the client never ticks fluids, and without a pushed column
     * (outside a lava update) the answer is the vanilla one.
     */
    public static boolean active(LevelReader level) {
        Integer blockX = COLUMN.get();
        if (blockX == null
                || !(level instanceof ServerLevel server)
                || server.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        ChunkGenerator generator = server.getChunkSource().getGenerator();
        return generator instanceof RotatingChunkGenerator rotating
                && isNetherSpeedLane(BandLayout.laneName(rotating.delegateForBlockX(blockX)));
    }
}
