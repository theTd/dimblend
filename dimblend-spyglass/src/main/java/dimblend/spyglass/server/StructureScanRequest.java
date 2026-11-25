package dimblend.spyglass.server;

import dimblend.worldgen.StructureStateBand;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One prepared scan. Built on the server thread (cheap work only: player, whitelist, band states),
 * consumed on the scan executor.
 *
 * <p>{@code playerX}/{@code playerZ} are the position the snapshot ring is built around, not the
 * player's position when the scan finishes: the client re-filters against its live position.
 *
 * <p>{@code prediction} carries the generators and registries a seed-only replay needs, resolved on
 * the server thread; {@code null} disables prediction for this request (a generator whose structure
 * state is not available, or a level without structure generation). See {@link StructurePredictor}.
 *
 * <p>{@code bandSize} is the rotating region width the request was built under, captured on the
 * server thread so the scan executor can keep every outline inside the player's own region
 * without touching a {@code Level} (see {@link dimblend.api.RegionLanes}); {@code 0} means "not the
 * rotating dimension, every position is the same region".
 */
record StructureScanRequest(
        UUID playerId,
        long sequence,
        double playerX,
        double playerZ,
        int bandSize,
        RegionReadContext region,
        List<StructureTarget> targets,
        List<StructureStateBand> bands,
        @Nullable PredictionContext prediction) {
    StructureScanRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(region, "region");
        targets = List.copyOf(targets);
        bands = List.copyOf(bands);
        if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)) {
            throw new IllegalArgumentException("Non-finite player position");
        }
    }

    ResourceKey<Level> dimension() {
        return this.region.dimension();
    }
}
