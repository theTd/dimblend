package dimblend.spyglass.server;

import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.worldgen.StructureStateBand;
import dimblend.worldgen.StructureStateProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Enumerates the chunks where a whitelisted structure placement could have generated, for a given
 * chunk window and a specific structure state.
 *
 * <p>This is the expensive part of a scan: a spacing-1 placement visits every chunk in the window.
 * It runs on the scan executor, never on the server tick thread. The states handed in must have
 * gone through {@link StructureStateProvider#structureStatesFor} on the server thread, which has
 * ensured their lazy placement state (see that method's contract); everything here is then a pure
 * read ({@code getPlacementsForStructure}, {@code getRingPositionsFor},
 * {@code getPotentialStructureChunk}, {@code isStructureChunk}).
 */
final class StructureCandidates {
    /** Padding of the snapshot ring in chunks; must cover the server's movement refresh threshold. */
    static final int PAD_CHUNKS =
            (int) Math.ceil(SpyglassRefreshPolicy.SNAPSHOT_PAD_BLOCKS / 16.0);
    /**
     * Chunks that cover the outer display edge (1024 blocks). The inner edge is the client's reveal
     * distance (its terrain fog start, between 0 and 1024 blocks), so it can only shrink the ring; the
     * window is centered on the player and this radius always covers the whole remaining ring.
     */
    static final int VISIBLE_RADIUS_CHUNKS = (int) Math.ceil(SpyglassDistance.MAX_BLOCKS / 16.0);
    /** A structure's start chunk can sit farther out than its union center; keep a margin. */
    static final int START_MARGIN_CHUNKS = 32;
    static final int SCAN_RADIUS_CHUNKS =
            VISIBLE_RADIUS_CHUNKS + PAD_CHUNKS + START_MARGIN_CHUNKS;

    private StructureCandidates() {
    }

    /**
     * Server thread only: resolves the generator's structure states for the scan window together
     * with the chunk range each state owns.
     */
    static List<StructureStateBand> bandsFor(ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
        ChunkWindow window = windowFor(centerChunkX, centerChunkZ, radius);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof StructureStateProvider provider) {
            return provider.structureStatesFor(window.minX(), window.minZ(), window.maxX(), window.maxZ());
        }
        return List.of(new StructureStateBand(
                level.getChunkSource().getGeneratorState(),
                window.minX(),
                window.minZ(),
                window.maxX(),
                window.maxZ()));
    }

    static ChunkWindow windowFor(int centerChunkX, int centerChunkZ, int radius) {
        return new ChunkWindow(
                centerChunkX - radius,
                centerChunkZ - radius,
                centerChunkX + radius,
                centerChunkZ + radius);
    }

    /** Scan-executor safe: pure reads on states whose lazy placement state is already generated. */
    static List<StructureCandidate> find(List<StructureTarget> targets, List<StructureStateBand> bands) {
        Set<StructureCandidate> candidates = new LinkedHashSet<>();
        for (StructureTarget target : targets) {
            for (StructureStateBand band : bands) {
                ChunkGeneratorStructureState state = band.state();
                ChunkWindow window = new ChunkWindow(
                        band.minChunkX(), band.minChunkZ(), band.maxChunkX(), band.maxChunkZ());
                for (StructurePlacement placement : state.getPlacementsForStructure(target.holder())) {
                    collectForPlacement(target.id(), state, placement, window, candidates);
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static void collectForPlacement(
            ResourceLocation id,
            ChunkGeneratorStructureState state,
            StructurePlacement placement,
            ChunkWindow window,
            Set<StructureCandidate> output) {
        if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
            for (ChunkPos pos : randomSpreadChunks(randomSpread, state.getLevelSeed(), window)) {
                addIfVerified(id, state, placement, pos, window, output);
            }
        } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
            List<ChunkPos> positions = state.getRingPositionsFor(rings);
            if (positions != null) {
                for (ChunkPos pos : filterToWindow(positions, window)) {
                    addIfVerified(id, state, placement, pos, window, output);
                }
            }
        } else {
            for (int x = window.minX(); x <= window.maxX(); x++) {
                for (int z = window.minZ(); z <= window.maxZ(); z++) {
                    if (placement.isStructureChunk(state, x, z)) {
                        output.add(new StructureCandidate(id, x, z));
                    }
                }
            }
        }
    }

    private static void addIfVerified(
            ResourceLocation id,
            ChunkGeneratorStructureState state,
            StructurePlacement placement,
            ChunkPos pos,
            ChunkWindow window,
            Set<StructureCandidate> output) {
        if (window.contains(pos.x, pos.z) && placement.isStructureChunk(state, pos.x, pos.z)) {
            output.add(new StructureCandidate(id, pos.x, pos.z));
        }
    }

    /** Keeps only the positions inside the band window; the placement enumeration is window-agnostic. */
    static List<ChunkPos> filterToWindow(List<ChunkPos> positions, ChunkWindow window) {
        List<ChunkPos> inside = new ArrayList<>(positions.size());
        for (ChunkPos pos : positions) {
            if (window.contains(pos.x, pos.z)) {
                inside.add(pos);
            }
        }
        return inside;
    }

    static List<ChunkPos> randomSpreadChunks(
            RandomSpreadStructurePlacement placement, long seed, ChunkWindow window) {
        return randomSpreadChunks(
                placement.spacing(),
                window,
                (x, z) -> placement.getPotentialStructureChunk(seed, x, z));
    }

    static List<ChunkPos> randomSpreadChunks(
            int spacing, ChunkWindow window, PotentialChunkLookup lookup) {
        if (spacing <= 0) {
            return List.of();
        }
        int minCellX = Math.floorDiv(window.minX(), spacing);
        int maxCellX = Math.floorDiv(window.maxX(), spacing);
        int minCellZ = Math.floorDiv(window.minZ(), spacing);
        int maxCellZ = Math.floorDiv(window.maxZ(), spacing);
        List<ChunkPos> positions = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                ChunkPos pos = lookup.get(cellX * spacing, cellZ * spacing);
                if (window.contains(pos.x, pos.z)) {
                    positions.add(pos);
                }
            }
        }
        return positions;
    }

    @FunctionalInterface
    interface PotentialChunkLookup {
        ChunkPos get(int chunkX, int chunkZ);
    }

    record ChunkWindow(int minX, int minZ, int maxX, int maxZ) {
        ChunkWindow {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Inverted chunk window");
            }
        }

        boolean contains(int chunkX, int chunkZ) {
            return chunkX >= this.minX && chunkX <= this.maxX
                    && chunkZ >= this.minZ && chunkZ <= this.maxZ;
        }
    }
}
