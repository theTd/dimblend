package dimblend.spyglass.server;

import dimblend.worldgen.StructureStateBand;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * One prepared scan. Built on the server thread (cheap work only: player, whitelist, band states,
 * biome registry), consumed on the scan executor.
 *
 * <p>{@code playerX}/{@code playerZ} are the position the snapshot ring is built around, not the
 * player's position when the scan finishes: the client re-filters against its live position.
 *
 * <p>{@code revealBlocks} is the reveal distance the ring's inner edge is built for: the client's
 * terrain fog start, or {@link SpyglassRevealDefaults} until the client sent one. The client
 * re-filters against its live reveal distance, so this only decides which structures make it into the
 * snapshot.
 *
 * <p>{@code biomes} is the frozen biome registry, used by the shape extraction to decode each
 * section's biome palette off-thread; it is read-only at that point, exactly like vanilla's IO
 * workers reading chunks.
 *
 * <p>{@code prediction} carries the generators and registries a seed-only replay needs, resolved on
 * the server thread; {@code null} disables prediction for this request (a non-whitelisted generator
 * shape, or a level without structure generation). See {@link StructurePredictor}.
 */
record StructureScanRequest(
        UUID playerId,
        long sequence,
        double playerX,
        double playerZ,
        double revealBlocks,
        RegionReadContext region,
        Registry<Biome> biomes,
        List<StructureTarget> targets,
        List<StructureStateBand> bands,
        @Nullable PredictionContext prediction) {
    StructureScanRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(biomes, "biomes");
        targets = List.copyOf(targets);
        bands = List.copyOf(bands);
        if (!Double.isFinite(playerX) || !Double.isFinite(playerZ)) {
            throw new IllegalArgumentException("Non-finite player position");
        }
        if (!Double.isFinite(revealBlocks) || revealBlocks < 0.0) {
            throw new IllegalArgumentException("Invalid reveal distance: " + revealBlocks);
        }
    }

    ResourceKey<Level> dimension() {
        return this.region.dimension();
    }
}
